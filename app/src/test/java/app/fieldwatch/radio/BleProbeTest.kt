package app.fieldwatch.radio

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM coverage for BleProbe's UUID helpers and report model. */
class BleProbeTest {

    @Test
    fun sigBaseUuidShortensToFourHex() {
        assertEquals("180A", BleProbe.shortId(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")))
        assertEquals("2A19", BleProbe.shortId(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")))
        assertEquals("2902", BleProbe.shortId(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")))
    }

    @Test
    fun vendorUuidStaysFull() {
        val u = "7DFC9000-7D1C-4951-86AA-8D9728F8D66C"
        assertEquals(u, BleProbe.shortId(UUID.fromString(u)))
    }

    @Test
    fun knownNamesResolve() {
        assertEquals("Device Information", BleProbe.serviceName(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")))
        assertEquals("Battery Level", BleProbe.charName(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")))
        assertEquals("AirTag / Find My accessory", BleProbe.serviceName(UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C")))
    }

    @Test
    fun unknownUuidHasNoName() {
        assertNull(BleProbe.serviceName(UUID.fromString("00001899-0000-1000-8000-00805F9B34FB")))
        assertNull(BleProbe.charName(UUID.fromString("00002A99-0000-1000-8000-00805F9B34FB")))
    }

    @Test
    fun reportConnectedOnlyWhenNoFailure() {
        val ok = BleProbe.Report("AA:BB:CC:DD:EE:FF", 0L, 1000L, null, emptyList(), 87, emptyList())
        val refused = ok.copy(failure = "Connect refused (status 133)")
        assertEquals(true, ok.connected)
        assertEquals(false, refused.connected)
    }
}
