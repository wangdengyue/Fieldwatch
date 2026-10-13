package app.fieldwatch.analysis

/**
 * Minimal geohash encoder. Precision 7 is a ~150 m cell — "this block" granularity.
 * Pure JVM, no Android deps, unit-testable.
 */
object GeoHash {
    private const val ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        require(precision in 1..12) { "precision out of range" }
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        val out = StringBuilder(precision)
        var bit = 0
        var ch = 0
        var even = true
        while (out.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonLo = mid
                } else {
                    lonHi = mid
                }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latLo = mid
                } else {
                    latHi = mid
                }
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                out.append(ALPHABET[ch])
                bit = 0
                ch = 0
            }
        }
        return out.toString()
    }
}
