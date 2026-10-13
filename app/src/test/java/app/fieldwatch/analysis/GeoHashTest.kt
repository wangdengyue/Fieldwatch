package app.fieldwatch.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoHashTest {
    @Test
    fun knownCells() {
        // Well-known geohash reference values.
        assertEquals("u4pruydqqvj", GeoHash.encode(57.64911, 10.40744, 11))
        assertEquals("wtw3sjq", GeoHash.encode(31.2304, 121.4737, 7)) // Shanghai
    }

    @Test
    fun precisionSevenIsTheDefault() {
        assertEquals(7, GeoHash.encode(39.9042, 116.4074).length)
    }

    @Test
    fun nearbyPointsShareTheCellFarApartDoNot() {
        val a = GeoHash.encode(31.23040, 121.47370)
        val b = GeoHash.encode(31.23041, 121.47371)
        val c = GeoHash.encode(39.9042, 116.4074) // Beijing
        assertTrue(a.take(6) == b.take(6))
        assertTrue(a != c)
    }
}
