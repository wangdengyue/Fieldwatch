package app.fieldwatch.analysis

import app.fieldwatch.domain.MfgRecord
import app.fieldwatch.domain.RadioFacts
import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private fun sighting(
    mac: String,
    name: String = "",
    oui: String = "",
    mfg: List<MfgRecord> = emptyList(),
    services: List<String> = emptyList(),
    ies: List<String> = emptyList(),
) = Sighting(
    key = mac,
    kind = RadioKind.BLE,
    mac = mac,
    name = name,
    rssi = -60,
    rssiMin = -65,
    rssiMax = -55,
    channel = 0,
    frequencyMhz = 0,
    vendor = null,
    randomized = true,
    hiddenSsid = false,
    serviceUuids = services,
    manufacturerId = null,
    manufacturerDataHex = "",
    rawHex = "",
    extras = "",
    firstSeen = 0L,
    lastSeen = 0L,
    hitCount = 1,
    fleetIds = emptySet(),
    rssiHistory = emptyList(),
    presence = emptyList(),
    vendorIeOuis = ies,
    facts = RadioFacts(mfgRecords = mfg),
)

class FingerprintTest {
    @Test
    fun rotationKeepsTheKey() {
        // Same content, different (rotated) MAC → same fingerprint.
        val a = sighting(mac = "5A:11:22:33:44:55", name = "Tag", services = listOf("FEAA"))
        val b = sighting(mac = "6F:66:77:88:99:AA", name = "Tag", services = listOf("FEAA"))
        assertEquals(Fingerprint.stableKey(a), Fingerprint.stableKey(b))
    }

    @Test
    fun differentContentChangesTheKey() {
        val a = sighting(mac = "5A:11:22:33:44:55", name = "Tag", services = listOf("FEAA"))
        val b = sighting(mac = "6F:66:77:88:99:AA", name = "Tag", services = listOf("FE95"))
        assertNotEquals(Fingerprint.stableKey(a), Fingerprint.stableKey(b))
    }

    @Test
    fun mfgShapeUsesCompanyLengthAndTypeNotPayload() {
        // Same shape, different payload bytes → same key (rotating EID payloads).
        val a = sighting(mac = "5A:11:22:33:44:55", mfg = listOf(MfgRecord(0x004C, "1010AABBCC")))
        val b = sighting(mac = "6F:66:77:88:99:AA", mfg = listOf(MfgRecord(0x004C, "1010DDEEFF")))
        assertEquals(Fingerprint.stableKey(a), Fingerprint.stableKey(b))
        // Different length or type → different key.
        val c = sighting(mac = "71:00:11:22:33:44", mfg = listOf(MfgRecord(0x004C, "1210AABBCC")))
        assertNotEquals(Fingerprint.stableKey(a), Fingerprint.stableKey(c))
    }

    @Test
    fun fullUuidCollapsesToShortForm() {
        val a = sighting(mac = "5A:11:22:33:44:55", services = listOf("FEAA"))
        val b = sighting(mac = "6F:66:77:88:99:AA", services = listOf("0000FEAA-0000-1000-8000-00805F9B34FB"))
        assertEquals(Fingerprint.stableKey(a), Fingerprint.stableKey(b))
    }
}
