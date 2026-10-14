package app.fieldwatch.domain

/**
 * Tracker family + separation-state read from the broadcast alone — no
 * connection needed. Based on the public protocol analyses (arXiv
 * 2501.17452 "A Tale of Three Location Trackers", arXiv 2401.13584, the
 * DULT spec, and the AirGuard project).
 *
 * Honest by construction: family is stated only when a protocol feature
 * proves it; separation is stated only for Apple Find My, where the
 * offline-finding frame (type 0x12) is emitted *because* the accessory
 * lost its owner. SmartTag and Tile do not encode separation in the
 * broadcast — for those the verdict is UNKNOWN, and the wording says so.
 */
object TrackerClassifier {

    enum class Family { APPLE_FIND_MY, SAMSUNG_SMARTTAG, TILE }

    enum class Separation { SEPARATED, UNKNOWN }

    data class Assessment(
        val family: Family,
        /** Short label, e.g. "AirTag / Find My accessory". */
        val familyLabel: String,
        val separation: Separation,
        /** One honest sentence about the separation read. */
        val separationLabel: String,
        /** Protocol facts the verdict rests on. */
        val evidence: List<String>,
    )

    fun assess(device: Sighting): Assessment? {
        if (device.kind != RadioKind.BLE) return null
        return apple(device) ?: samsung(device) ?: tile(device)
    }

    // ------------------------------------------------------------------
    // Apple Find My (AirTag and licensed Find My accessories)
    // ------------------------------------------------------------------

    private fun apple(device: Sighting): Assessment? {
        val records = device.facts.mfgRecords.filter { it.companyId == 0x004C }
        if (records.isEmpty()) return null
        var offlineFinding = false
        var findMyBytes = 0
        var nearbyInfo = false
        for (rec in records) {
            val bytes = hexToBytes(rec.dataHex) ?: continue
            var i = 0
            while (i + 2 <= bytes.size) {
                val type = bytes[i].toInt() and 0xFF
                val len = bytes[i + 1].toInt() and 0xFF
                if (len <= 0 || i + 2 + len > bytes.size) break
                when (type) {
                    // Offline Finding: emitted only while the accessory is
                    // away from its owner, asking passing iPhones to relay it.
                    0x12 -> {
                        offlineFinding = true
                        findMyBytes = maxOf(findMyBytes, len)
                    }
                    0x10 -> nearbyInfo = true
                }
                i += 2 + len
            }
        }
        val fleetHit = device.fleetIds.any { it.contains("airtag") || it.contains("findmy") || it.contains("find-my") }
        val svcHit = device.serviceUuids.any { it.uppercase().contains("FD44") || it.uppercase().contains("FD43") }
        if (!offlineFinding && !fleetHit && !svcHit) return null
        val evidence = ArrayList<String>(4)
        if (offlineFinding) {
            evidence += "Find My offline-finding frame (Apple type 0x12, $findMyBytes bytes) — the frame an accessory sends only while separated"
        }
        if (nearbyInfo) evidence += "Apple Nearby Info (type 0x10) — generic Apple presence frame"
        if (svcHit) evidence += "Find My service UUID (FD43/FD44)"
        if (fleetHit) evidence += "Matched the AirTag catalog signature"
        return Assessment(
            family = Family.APPLE_FIND_MY,
            familyLabel = "AirTag / Find My accessory",
            separation = if (offlineFinding) Separation.SEPARATED else Separation.UNKNOWN,
            separationLabel = if (offlineFinding) {
                "Separated from its owner right now — it is asking nearby iPhones to relay its location."
            } else {
                "Separation unknown from this frame. AirTags near their owner go quiet; the offline-finding frame is the tell."
            },
            evidence = evidence,
        )
    }

    // ------------------------------------------------------------------
    // Samsung SmartTag / SmartTag+
    // ------------------------------------------------------------------

    private fun samsung(device: Sighting): Assessment? {
        val svcHit = device.serviceUuids.any { it.uppercase().contains("FD5A") }
        val mfgHit = device.facts.mfgRecords.any { it.companyId == 0x0075 }
        val fleetHit = device.fleetIds.any { it.contains("smarttag") }
        if (!svcHit && !fleetHit) return null
        val evidence = ArrayList<String>(3)
        if (svcHit) evidence += "SmartThings Find service UUID (FD5A)"
        if (mfgHit) evidence += "Samsung manufacturer data (company 0x0075)"
        if (fleetHit) evidence += "Matched the SmartTag catalog signature"
        return Assessment(
            family = Family.SAMSUNG_SMARTTAG,
            familyLabel = "Samsung SmartTag",
            separation = Separation.UNKNOWN,
            separationLabel = "SmartTags do not reveal separation state in the broadcast. " +
                "A tag that keeps appearing where you go is the signal — check its History.",
            evidence = evidence,
        )
    }

    // ------------------------------------------------------------------
    // Tile
    // ------------------------------------------------------------------

    private fun tile(device: Sighting): Assessment? {
        val svcs = device.serviceUuids.map { it.uppercase() }
        val svcHit = svcs.any { it.contains("FEED") || it.contains("FEEC") || it.contains("FEDD") }
        val mfgHit = device.facts.mfgRecords.any { it.companyId == 0x00C7 }
        val fleetHit = device.fleetIds.any { it.contains("tile") }
        if (!svcHit && !mfgHit && !fleetHit) return null
        val evidence = ArrayList<String>(3)
        if (svcHit) evidence += "Tile service UUID (FEED/FEEC/FEDD)"
        if (mfgHit) evidence += "Tile manufacturer data (company 0x00C7)"
        if (fleetHit) evidence += "Matched the Tile catalog signature"
        return Assessment(
            family = Family.TILE,
            familyLabel = "Tile tracker",
            separation = Separation.UNKNOWN,
            separationLabel = "Tiles advertise the same way whether or not the owner is near — " +
                "separation cannot be read from the broadcast. Repetition across places is the signal.",
            evidence = evidence,
        )
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.filter { it.isLetterOrDigit() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
