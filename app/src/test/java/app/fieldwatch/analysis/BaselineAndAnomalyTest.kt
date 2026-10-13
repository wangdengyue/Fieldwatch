package app.fieldwatch.analysis

import app.fieldwatch.analysis.db.BaselineEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineEngineTest {
    private fun visit(
        bucket: BaselineEntity?,
        ouis: Set<String>,
        keys: Set<String>,
        count: Int,
        now: Long = 0L,
    ) = BaselineEngine.learn(bucket, "g|9", "g", 9, ouis, emptySet(), keys, count, now)

    @Test
    fun visitsAccumulateAndArmAtThree() {
        var b: BaselineEntity? = null
        b = visit(b, setOf("AA:BB:CC"), setOf("mac:BLE:AA"), 5)
        assertEquals(1, b.visits)
        b = visit(b, setOf("AA:BB:CC"), setOf("mac:BLE:AA"), 7)
        b = visit(b, setOf("AA:BB:CC"), setOf("mac:BLE:AA"), 6)
        assertEquals(3, b.visits)
        assertTrue(b.visits >= BaselineEngine.ARM_VISITS)
    }

    @Test
    fun welfordTracksMeanAndSpread() {
        var b: BaselineEntity? = null
        for (c in listOf(4, 6, 5, 5, 8)) b = visit(b, emptySet(), emptySet(), c)
        assertEquals(5.6, b!!.deviceCountMean, 0.001)
        assertTrue(b.deviceCountM2 > 0.0)
    }

    @Test
    fun setsMergeWithoutDuplicatesAndCap() {
        var b: BaselineEntity? = null
        b = visit(b, setOf("AA:BB:CC"), setOf("k1"), 1)
        b = visit(b, setOf("AA:BB:CC", "DD:EE:FF"), setOf("k1", "k2"), 2)
        assertEquals(setOf("AA:BB:CC", "DD:EE:FF"), BaselineEngine.splitCsv(b.ouis))
        assertEquals(setOf("k1", "k2"), BaselineEngine.splitCsv(b.deviceKeys))
    }
}

class AnomalyDetectorTest {
    private var clock = 0L

    @Test
    fun strangerMustPersistTwoMinutes() {
        clock = 0L
        val d = AnomalyDetector(now = { clock })
        assertTrue(d.observe("b", setOf("k1")).isEmpty())
        clock = 60_000L
        assertTrue(d.observe("b", setOf("k1")).isEmpty())
        clock = 130_000L
        assertEquals(setOf("k1"), d.observe("b", setOf("k1")))
    }

    @Test
    fun leavingResetsCandidacy() {
        clock = 0L
        val d = AnomalyDetector(now = { clock })
        d.observe("b", setOf("k1"))
        clock = 90_000L
        d.observe("b", emptySet()) // gone — candidacy resets
        clock = 200_000L
        assertTrue(d.observe("b", setOf("k1")).isEmpty()) // must persist again
        clock = 330_000L
        assertEquals(setOf("k1"), d.observe("b", setOf("k1")))
    }

    @Test
    fun promotesOncePerStay() {
        clock = 0L
        val d = AnomalyDetector(now = { clock })
        d.observe("b", setOf("k1"))
        clock = 150_000L
        assertEquals(setOf("k1"), d.observe("b", setOf("k1")))
        clock = 200_000L
        assertTrue(d.observe("b", setOf("k1")).isEmpty()) // already promoted this stay
    }

    @Test
    fun bucketsAreIndependent() {
        clock = 0L
        val d = AnomalyDetector(now = { clock })
        d.observe("home", setOf("k1"))
        clock = 150_000L
        assertEquals(setOf("k1"), d.observe("home", setOf("k1")))
        // Same device at another bucket starts fresh there.
        assertTrue(d.observe("work", setOf("k1")).isEmpty())
    }
}
