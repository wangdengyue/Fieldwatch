package app.fieldwatch.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class SpanDecisionTest {
    private val t0 = 1_000_000_000L

    @Test
    fun noOpenSpanOpensFresh() {
        val a = HistoryStore.decideSpan(prevLastSeen = null, openSpanId = null, now = t0)
        assertEquals(SpanAction.OpenNew(null, null), a)
    }

    @Test
    fun withinGapExtendsOpenSpan() {
        val a = HistoryStore.decideSpan(prevLastSeen = t0 - 60_000L, openSpanId = 7L, now = t0)
        assertEquals(SpanAction.Extend(7L), a)
    }

    @Test
    fun exactlyAtGapStillExtends() {
        val a = HistoryStore.decideSpan(
            prevLastSeen = t0 - HistoryStore.GAP_MS,
            openSpanId = 7L,
            now = t0,
        )
        assertEquals(SpanAction.Extend(7L), a)
    }

    @Test
    fun pastGapClosesAtLastHearingAndOpensNew() {
        val last = t0 - HistoryStore.GAP_MS - 1
        val a = HistoryStore.decideSpan(prevLastSeen = last, openSpanId = 7L, now = t0)
        assertEquals(SpanAction.OpenNew(closeSpanId = 7L, closeAt = last), a)
    }

    @Test
    fun staleOpenSpanWithoutLastSeenClosesWithoutEndTime() {
        val a = HistoryStore.decideSpan(prevLastSeen = null, openSpanId = 9L, now = t0)
        assertEquals(SpanAction.OpenNew(closeSpanId = 9L, closeAt = null), a)
    }
}
