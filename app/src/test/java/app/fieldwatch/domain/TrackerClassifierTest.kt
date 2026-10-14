package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerClassifierTest {

    private fun bleSighting(
        mfg: List<MfgRecord> = emptyList(),
        serviceUuids: List<String> = emptyList(),
        fleetIds: Set<String> = emptySet(),
    ) = Sighting(
        key = "k", kind = RadioKind.BLE, mac = "AA:BB:CC:DD:EE:FF", name = "",
        rssi = -60, rssiMin = -60, rssiMax = -60, channel = 0, frequencyMhz = 0,
        vendor = null, randomized = true, hiddenSsid = false,
        serviceUuids = serviceUuids, manufacturerId = mfg.firstOrNull()?.companyId,
        manufacturerDataHex = mfg.firstOrNull()?.dataHex ?: "", rawHex = "", extras = "",
        firstSeen = 0, lastSeen = 0, hitCount = 1, fleetIds = fleetIds,
        rssiHistory = emptyList(), presence = emptyList(),
        facts = RadioFacts(mfgRecords = mfg),
    )

    /** Apple offline-finding frame: TLV type 0x12, len 0x19, status + key fragment. */
    private fun appleFindMyFrame(): MfgRecord {
        val tlv = "12" + "19" + "04" + "AA".repeat(24)
        return MfgRecord(0x004C, tlv)
    }

    @Test
    fun findMyOfflineFrameMeansSeparatedAirTag() {
        val a = TrackerClassifier.assess(bleSighting(mfg = listOf(appleFindMyFrame())))
        assertEquals(TrackerClassifier.Family.APPLE_FIND_MY, a?.family)
        assertEquals(TrackerClassifier.Separation.SEPARATED, a?.separation)
        assertTrue(a!!.evidence.any { it.contains("0x12") })
    }

    @Test
    fun appleNearbyInfoAloneIsNotClassified() {
        // Type 0x10 Nearby Info only: an iPhone, not enough to call it a tracker.
        val tlv = "10" + "05" + "00".repeat(5)
        val a = TrackerClassifier.assess(bleSighting(mfg = listOf(MfgRecord(0x004C, tlv))))
        assertNull(a)
    }

    @Test
    fun airtagFleetHitWithoutFindMyFrameStaysUnknown() {
        val tlv = "10" + "05" + "00".repeat(5)
        val a = TrackerClassifier.assess(
            bleSighting(mfg = listOf(MfgRecord(0x004C, tlv)), fleetIds = setOf("fleet-airtag")),
        )
        assertEquals(TrackerClassifier.Family.APPLE_FIND_MY, a?.family)
        assertEquals(TrackerClassifier.Separation.UNKNOWN, a?.separation)
    }

    @Test
    fun smartTagByServiceUuid() {
        val a = TrackerClassifier.assess(bleSighting(serviceUuids = listOf("FD5A")))
        assertEquals(TrackerClassifier.Family.SAMSUNG_SMARTTAG, a?.family)
        assertEquals(TrackerClassifier.Separation.UNKNOWN, a?.separation)
    }

    @Test
    fun tileByServiceUuidAndCompany() {
        val a = TrackerClassifier.assess(
            bleSighting(serviceUuids = listOf("FEED"), mfg = listOf(MfgRecord(0x00C7, "0201"))),
        )
        assertEquals(TrackerClassifier.Family.TILE, a?.family)
    }

    @Test
    fun wifiAndUnrelatedBleAreNotTrackers() {
        assertNull(TrackerClassifier.assess(bleSighting(mfg = listOf(MfgRecord(0x00E0, "00")))))
        assertNull(TrackerClassifier.assess(bleSighting().copy(kind = RadioKind.WIFI)))
    }
}
