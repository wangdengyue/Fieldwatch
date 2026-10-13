package app.fieldwatch.analysis

import app.fieldwatch.domain.Sighting
import kotlin.math.sqrt

sealed interface MergeEvent {
    val stableKey: String

    /** A MAC for this fingerprint went quiet and another appeared within [gapMs]. */
    data class Rotation(
        override val stableKey: String,
        val fromMac: String,
        val toMac: String,
        val gapMs: Long,
        val macCount: Int,
    ) : MergeEvent

    /** Two MACs with this fingerprint rose and fell together — likely one radio. */
    data class Correlated(
        override val stableKey: String,
        val macA: String,
        val macB: String,
        val r: Double,
        val buckets: Int,
    ) : MergeEvent
}

/**
 * Honest random-MAC merge, in-memory:
 *
 * 1. Rotation detection — same fingerprint, MAC A goes quiet, MAC B appears
 *    within [rotationGapMs] → one rotating group.
 * 2. RSSI correlation — parallel MACs under one fingerprint whose per-bucket
 *    RSSI Pearson correlation clears [correlationFloor] → "probably the same
 *    physical radio". A hint, never an identity claim.
 *
 * State is per-process; the durable part (group rows, recent MACs) is written
 * by the engine through HistoryStore.
 */
class MacMerge(
    private val windowMs: Long = 300_000L,
    private val bucketMs: Long = 30_000L,
    private val rotationGapMs: Long = 600_000L,
    private val correlationFloor: Double = 0.8,
    private val minBuckets: Int = 5,
    private val alertCooldownMs: Long = 6 * 3_600_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class MacState {
        var lastSeen: Long = 0L
        val samples = ArrayDeque<Pair<Long, Int>>()
    }

    private val groups = HashMap<String, HashMap<String, MacState>>()
    private val lastRotationAlert = HashMap<String, Long>()
    private val lastPairAlert = HashMap<String, Long>()

    fun observe(s: Sighting, stableKey: String): List<MergeEvent> {
        val now = now()
        val mac = s.mac.uppercase()
        val group = groups.getOrPut(stableKey) { HashMap() }
        val events = ArrayList<MergeEvent>(2)

        // Rotation: a different, recently-live MAC hands over to this one.
        if (!group.containsKey(mac)) {
            val freshestOther = group.entries
                .filter { now - it.value.lastSeen <= rotationGapMs }
                .maxByOrNull { it.value.lastSeen }
            if (freshestOther != null) {
                val lastAlert = lastRotationAlert[stableKey]
                if (lastAlert == null || now - lastAlert >= alertCooldownMs) {
                    lastRotationAlert[stableKey] = now
                    events += MergeEvent.Rotation(
                        stableKey = stableKey,
                        fromMac = freshestOther.key,
                        toMac = mac,
                        gapMs = now - freshestOther.value.lastSeen,
                        macCount = group.size + 1,
                    )
                }
            }
        }

        val state = group.getOrPut(mac) { MacState() }
        state.lastSeen = now
        state.samples.addLast(now to s.rssi)
        while (state.samples.isNotEmpty() && now - state.samples.first().first > windowMs) {
            state.samples.removeFirst()
        }

        // Correlation against parallel MACs still alive in the window.
        for ((otherMac, other) in group) {
            if (otherMac == mac) continue
            if (other.samples.size < minBuckets) continue
            val r = correlation(state.samples, other.samples)
            if (r != null) {
                val pairKey = stableKey + "|" + listOf(mac, otherMac).sorted().joinToString("+")
                val lastAlert = lastPairAlert[pairKey]
                if (lastAlert == null || now - lastAlert >= alertCooldownMs) {
                    lastPairAlert[pairKey] = now
                    events += MergeEvent.Correlated(stableKey, mac, otherMac, r.first, r.second)
                }
            }
        }
        return events
    }

    fun macsFor(stableKey: String): List<String> =
        groups[stableKey]?.keys?.sorted() ?: emptyList()

    /** Pearson on per-bucket mean RSSI, aligned buckets only. Null = not enough overlap. */
    internal fun correlation(
        a: Collection<Pair<Long, Int>>,
        b: Collection<Pair<Long, Int>>,
    ): Pair<Double, Int>? {
        val bucketsA = bucketize(a)
        val bucketsB = bucketize(b)
        val common = bucketsA.keys.intersect(bucketsB.keys)
        if (common.size < minBuckets) return null
        val xs = common.map { bucketsA.getValue(it) }
        val ys = common.map { bucketsB.getValue(it) }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in xs.indices) {
            val ux = xs[i] - mx
            val uy = ys[i] - my
            num += ux * uy
            dx += ux * ux
            dy += uy * uy
        }
        if (dx <= 0.0 || dy <= 0.0) return null
        val r = num / sqrt(dx * dy)
        if (r < correlationFloor) return null
        return r to common.size
    }

    private fun bucketize(samples: Collection<Pair<Long, Int>>): Map<Long, Double> =
        samples.groupBy({ it.first / bucketMs }, { it.second.toDouble() })
            .mapValues { (_, v) -> v.average() }
}
