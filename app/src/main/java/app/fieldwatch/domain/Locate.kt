package app.fieldwatch.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One phone-at-hear-time fix: where the phone stood and how strong the target was. */
data class LocateSample(
    val at: Long,
    val lat: Double,
    val lon: Double,
    val rssi: Int,
)

enum class LocateQuality { INSUFFICIENT, POOR, FAIR, GOOD }

data class LocateEstimate(
    val lat: Double,
    val lon: Double,
    /** Confidence circle radius in meters. Honest: RSSI distance is log-noisy. */
    val radiusM: Double,
    val samples: Int,
    /** Angular coverage of sample positions around the estimate, 0..360. Low = one-sided pass. */
    val coverageDeg: Int,
    val quality: LocateQuality,
)

/**
 * Multilateration from walked samples: convert each RSSI to a rough distance
 * (log-distance path loss), then grid-search the point that best fits all
 * circles, refining the window four times.
 *
 * Honest limits: reflections and body shadowing make any single RSSI reading
 * lie by 10-20 dB. This finds the *likely* spot, not a pin. One-sided passes
 * get a wide circle — walk around the target to tighten it.
 */
object LocateEngine {
    const val MIN_SAMPLES = 4

    /** RSSI at 1 m for a typical BLE beacon; path-loss exponent for indoor-ish air. */
    private const val RSSI_AT_1M = -59.0
    private const val PATH_LOSS = 2.0
    private const val GRID = 25

    fun estimate(samples: List<LocateSample>): LocateEstimate? {
        val usable = samples.filter { it.rssi < 0 && it.rssi > -100 }
        if (usable.size < MIN_SAMPLES) return null

        // Local meters around the sample centroid.
        val lat0 = usable.map { it.lat }.average()
        val lon0 = usable.map { it.lon }.average()
        val mPerDegLat = 110_540.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
        fun xOf(lon: Double) = (lon - lon0) * mPerDegLon
        fun yOf(lat: Double) = (lat - lat0) * mPerDegLat

        val xs = usable.map { xOf(it.lon) }
        val ys = usable.map { yOf(it.lat) }
        val ds = usable.map { distM(it.rssi) }
        val ws = ds.map { 1.0 / max(1.0, it) } // closer hearings are less noisy

        // Search box: sample bounds expanded by the largest estimated distance.
        val pad = ds.max().coerceIn(20.0, 400.0)
        var minX = xs.min() - pad
        var maxX = xs.max() + pad
        var minY = ys.min() - pad
        var maxY = ys.max() + pad

        fun cost(px: Double, py: Double): Double {
            var c = 0.0
            var wSum = 0.0
            for (i in xs.indices) {
                val d = sqrt((px - xs[i]) * (px - xs[i]) + (py - ys[i]) * (py - ys[i]))
                val r = d - ds[i]
                c += ws[i] * r * r
                wSum += ws[i]
            }
            return c / wSum
        }

        var bestX = 0.0
        var bestY = 0.0
        var bestC = Double.MAX_VALUE
        repeat(4) {
            val stepX = (maxX - minX) / (GRID - 1)
            val stepY = (maxY - minY) / (GRID - 1)
            var roundBestC = bestC
            for (gx in 0 until GRID) {
                for (gy in 0 until GRID) {
                    val px = minX + gx * stepX
                    val py = minY + gy * stepY
                    val c = cost(px, py)
                    if (c < roundBestC) {
                        roundBestC = c
                        bestX = px
                        bestY = py
                        bestC = c
                    }
                }
            }
            // Refine around the winner.
            minX = bestX - stepX
            maxX = bestX + stepX
            minY = bestY - stepY
            maxY = bestY + stepY
        }

        // Angular coverage around the estimate: 360 minus the largest gap.
        val angles = xs.indices
            .map { Math.toDegrees(kotlin.math.atan2(ys[it] - bestY, xs[it] - bestX) + PI) } // 0..360
            .sorted()
        var maxGap = 360.0
        if (angles.size >= 2) {
            maxGap = 0.0
            for (i in angles.indices) {
                val next = if (i + 1 < angles.size) angles[i + 1] else angles[0] + 360.0
                maxGap = max(maxGap, next - angles[i])
            }
        }
        val coverage = (360.0 - maxGap).coerceIn(0.0, 360.0)

        var radius = sqrt(bestC).coerceIn(5.0, 500.0)
        if (coverage < 120.0) radius *= 1.5 // one-sided pass: admit the far side

        val quality = when {
            usable.size >= 8 && coverage >= 200 && radius <= 30 -> LocateQuality.GOOD
            usable.size >= 6 && coverage >= 120 -> LocateQuality.FAIR
            else -> LocateQuality.POOR
        }

        return LocateEstimate(
            lat = lat0 + bestY / mPerDegLat,
            lon = lon0 + bestX / mPerDegLon,
            radiusM = radius,
            samples = usable.size,
            coverageDeg = coverage.toInt(),
            quality = quality,
        )
    }

    /** Log-distance path loss: how far a signal this strong probably traveled. */
    fun distM(rssi: Int): Double =
        10.0.pow((RSSI_AT_1M - rssi) / (10.0 * PATH_LOSS)).coerceIn(0.5, 500.0)
}
