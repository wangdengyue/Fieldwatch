package app.fieldwatch.analysis

import app.fieldwatch.domain.RadioKind
import app.fieldwatch.domain.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sighting(mac: String, rssi: Int) = Sighting(
    key = mac,
    kind = RadioKind.BLE,
    mac = mac,
    name = "",
    rssi = rssi,
    rssiMin = rssi,
    rssiMax = rssi,
    channel = 0,
    frequencyMhz = 0,
    vendor = null,
    randomized = true,
    hiddenSsid = false,
    serviceUuids = emptyList(),
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
)

class MacMergeTest {
    private var clock = 0L
    private fun merge() = MacMerge(now = { clock })

    @Test
    fun handoverWithinGapIsARotation() {
        val m = merge()
        clock = 0L
        assertTrue(m.observe(sighting("5A:00:00:00:00:01", -60), "sk1").isEmpty())
        clock = 60_000L // A goes quiet, B appears a minute later
        val events = m.observe(sighting("5A:00:00:00:00:02", -62), "sk1")
        val rotation = events.filterIsInstance<MergeEvent.Rotation>().single()
        assertEquals("5A:00:00:00:00:01", rotation.fromMac)
        assertEquals("5A:00:00:00:00:02", rotation.toMac)
        assertEquals(2, rotation.macCount)
    }

    @Test
    fun handoverAfterGapIsNotARotation() {
        val m = merge()
        clock = 0L
        m.observe(sighting("5A:00:00:00:00:01", -60), "sk1")
        clock = 20 * 60_000L // 20 minutes later — outside the 10-minute window
        val events = m.observe(sighting("5A:00:00:00:00:02", -62), "sk1")
        assertTrue(events.filterIsInstance<MergeEvent.Rotation>().isEmpty())
    }

    @Test
    fun sameMacNeverRotates() {
        val m = merge()
        clock = 0L
        m.observe(sighting("5A:00:00:00:00:01", -60), "sk1")
        clock = 30_000L
        assertTrue(m.observe(sighting("5A:00:00:00:00:01", -61), "sk1").isEmpty())
    }

    @Test
    fun synchronizedRssiCorrelates() {
        val m = merge()
        // Two MACs climb and fall in lockstep over 5+ buckets (30s each).
        val pattern = listOf(-80, -72, -66, -62, -60, -62, -66, -72)
        var t = 0L
        val events = mutableListOf<MergeEvent>()
        for (p in pattern) {
            clock = t
            events += m.observe(sighting("5A:00:00:00:00:01", p), "sk2")
            events += m.observe(sighting("5A:00:00:00:00:02", p - 1), "sk2")
            t += 30_000L
        }
        val corr = events.filterIsInstance<MergeEvent.Correlated>()
        assertTrue("expected a correlation event, got $events", corr.isNotEmpty())
        assertTrue(corr.first().r > 0.8)
    }

    @Test
    fun independentRssiDoesNotCorrelate() {
        val m = merge()
        var t = 0L
        val events = mutableListOf<MergeEvent>()
        // A climbs while B falls — anti-correlated.
        val a = listOf(-80, -75, -70, -65, -60, -65, -70, -75)
        val b = listOf(-60, -65, -70, -75, -80, -75, -70, -65)
        for (i in a.indices) {
            clock = t
            events += m.observe(sighting("5A:00:00:00:00:01", a[i]), "sk3")
            events += m.observe(sighting("5A:00:00:00:00:02", b[i]), "sk3")
            t += 30_000L
        }
        assertTrue(events.filterIsInstance<MergeEvent.Correlated>().isEmpty())
    }

    @Test
    fun alertsCoolDown() {
        val m = merge()
        clock = 0L
        m.observe(sighting("5A:00:00:00:00:01", -60), "sk4")
        clock = 60_000L
        m.observe(sighting("5A:00:00:00:00:02", -62), "sk4")
        clock = 120_000L
        // Third MAC within the window: no second rotation alert inside the cooldown.
        val events = m.observe(sighting("5A:00:00:00:00:03", -63), "sk4")
        assertTrue(events.filterIsInstance<MergeEvent.Rotation>().isEmpty())
    }
}
