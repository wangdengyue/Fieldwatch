package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class LocateEngineTest {
    private val baseLat = 31.2304
    private val baseLon = 121.4737

    private fun offset(lat: Double, lon: Double, xM: Double, yM: Double): Pair<Double, Double> {
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        return lat + yM / 110_540.0 to lon + xM / mPerDegLon
    }

    private fun rssiAt(meters: Double, noise: Double = 0.0): Int =
        (-59.0 - 20.0 * log10(meters) + noise).toInt()

    /** Samples on a ring of [ringM] around (cxM, cyM), hearing a target at (txM, tyM). */
    private fun ring(
        txM: Double, tyM: Double,
        ringM: Double = 30.0,
        count: Int = 8,
        noiseAmp: Double = 2.0,
        arcFrom: Double = 0.0,
        arcTo: Double = 2 * Math.PI,
    ): List<LocateSample> {
        val out = ArrayList<LocateSample>()
        for (i in 0 until count) {
            val a = arcFrom + (arcTo - arcFrom) * i / (count - 1).coerceAtLeast(1)
            val sx = ringM * cos(a)
            val sy = ringM * sin(a)
            val (lat, lon) = offset(baseLat, baseLon, sx, sy)
            val dx = txM - sx
            val dy = tyM - sy
            val d = kotlin.math.hypot(dx, dy)
            val noise = if (i % 2 == 0) noiseAmp else -noiseAmp
            out += LocateSample(at = i * 1000L, lat = lat, lon = lon, rssi = rssiAt(d, noise))
        }
        return out
    }

    @Test
    fun surroundedTargetPinsWithinFifteenMeters() {
        val samples = ring(txM = 5.0, tyM = -8.0)
        val est = LocateEngine.estimate(samples)
        assertNotNull(est)
        est!!
        val (estLat0, estLon0) = offset(baseLat, baseLon, 5.0, -8.0)
        val err = Geo.meters(est.lat, est.lon, estLat0, estLon0)
        assertTrue("error ${err}m", err < 15.0)
        assertEquals(LocateQuality.GOOD, est.quality)
        assertTrue(est.coverageDeg >= 200)
    }

    @Test
    fun oneSidedPassStaysHonest() {
        // Walk past on one side only: estimate should still land nearby-ish,
        // but coverage is low, quality is not GOOD, radius inflated.
        val samples = ring(txM = 0.0, tyM = 25.0, arcFrom = Math.PI, arcTo = 2 * Math.PI)
        val est = LocateEngine.estimate(samples)!!
        assertTrue(est.coverageDeg < 200)
        assertTrue(est.quality == LocateQuality.POOR || est.quality == LocateQuality.FAIR)
    }

    @Test
    fun tooFewSamplesGivesNull() {
        assertNull(LocateEngine.estimate(ring(0.0, 0.0, count = 3)))
    }

    @Test
    fun unmeasuredRssiIsIgnored() {
        val good = ring(txM = 0.0, tyM = 0.0, count = 6)
        val bad = List(20) { LocateSample(it * 1000L, baseLat, baseLon, 0) }
        val est = LocateEngine.estimate(good + bad)!!
        assertEquals(6, est.samples)
    }
}
