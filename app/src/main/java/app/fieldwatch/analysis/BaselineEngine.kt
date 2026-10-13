package app.fieldwatch.analysis

import app.fieldwatch.analysis.db.AnalysisDb
import app.fieldwatch.analysis.db.BaselineEntity
import kotlin.math.roundToInt

/**
 * Learns the normal RF crowd for each (place, hour-of-day) bucket.
 *
 * A bucket starts alarming only after [ARM_VISITS] visits. A visit is counted
 * once per bucket per clock hour; each visit merges the OUIs, fleet classes,
 * and device keys observed into the bucket's normal set (all sets capped) and
 * Welford-updates the device-count mean/variance.
 */
class BaselineEngine(
    private val db: AnalysisDb,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val ARM_VISITS = 3
        const val MAX_OUIS = 400
        const val MAX_KINDS = 40
        const val MAX_KEYS = 800

        fun bucketKey(geohash: String, hourOfDay: Int): String = "$geohash|$hourOfDay"

        /** Pure merge, unit-tested without Room. */
        fun learn(
            bucket: BaselineEntity?,
            key: String,
            geohash: String,
            hourOfDay: Int,
            ouis: Set<String>,
            kinds: Set<String>,
            deviceKeys: Set<String>,
            deviceCount: Int,
            now: Long,
        ): BaselineEntity {
            val prevVisits = bucket?.visits ?: 0
            val prevMean = bucket?.deviceCountMean ?: 0.0
            val prevM2 = bucket?.deviceCountM2 ?: 0.0
            // Welford online mean/variance over per-visit device counts.
            val n = prevVisits + 1
            val delta = deviceCount - prevMean
            val mean = prevMean + delta / n
            val m2 = prevM2 + delta * (deviceCount - mean)
            return BaselineEntity(
                key = key,
                geohash = geohash,
                hourOfDay = hourOfDay,
                visits = n,
                deviceCountMean = mean,
                deviceCountM2 = m2,
                ouis = mergeCapped(bucket?.ouis, ouis, MAX_OUIS),
                fleetKinds = mergeCapped(bucket?.fleetKinds, kinds, MAX_KINDS),
                deviceKeys = mergeCapped(bucket?.deviceKeys, deviceKeys, MAX_KEYS),
                updatedAt = now,
            )
        }

        private fun mergeCapped(existing: String?, add: Set<String>, cap: Int): String {
            val set = LinkedHashSet<String>()
            existing?.split(',')?.filter { it.isNotBlank() }?.forEach(set::add)
            set.addAll(add.filter { it.isNotBlank() })
            return set.take(cap).joinToString(",")
        }

        fun splitCsv(csv: String): Set<String> =
            csv.split(',').filter { it.isNotBlank() }.toSet()
    }

    /** Buckets already counted this clock hour, so a long sit is one visit, not ninety. */
    private val visitedThisHour = HashMap<String, Long>()

    /** Learn one batch. Returns the (possibly newly created) bucket. */
    suspend fun learn(geohash: String, hourOfDay: Int, batch: BatchProfile): BaselineEntity {
        val key = bucketKey(geohash, hourOfDay)
        val hourStamp = now() / 3_600_000L
        val existing = db.baseline().find(key)
        // Count at most one visit per bucket per clock hour; always refresh the sets.
        val isNewVisit = visitedThisHour[key] != hourStamp
        val merged = if (isNewVisit) {
            visitedThisHour[key] = hourStamp
            learn(existing, key, geohash, hourOfDay, batch.ouis, batch.kinds, batch.deviceKeys, batch.deviceKeys.size, now())
        } else {
            // Same visit: extend sets without bumping the visit count or stats.
            existing?.copy(
                ouis = mergeCapped(existing.ouis, batch.ouis, MAX_OUIS),
                fleetKinds = mergeCapped(existing.fleetKinds, batch.kinds, MAX_KINDS),
                deviceKeys = mergeCapped(existing.deviceKeys, batch.deviceKeys, MAX_KEYS),
                updatedAt = now(),
            ) ?: learn(null, key, geohash, hourOfDay, batch.ouis, batch.kinds, batch.deviceKeys, batch.deviceKeys.size, now())
        }
        db.baseline().upsert(merged)
        return merged
    }

    suspend fun bucket(geohash: String, hourOfDay: Int): BaselineEntity? =
        db.baseline().find(bucketKey(geohash, hourOfDay))

    fun armed(bucket: BaselineEntity?): Boolean = (bucket?.visits ?: 0) >= ARM_VISITS

    suspend fun buckets(limit: Int = 60): List<BaselineEntity> = db.baseline().latest(limit)
}

/** What one batch looks like to the baseline: who is around, in sets. */
data class BatchProfile(
    val ouis: Set<String>,
    val kinds: Set<String>,
    val deviceKeys: Set<String>,
)

/** Human summary for the UI: "12 devices usual" etc. */
fun BaselineEntity.usualCount(): Int = deviceCountMean.roundToInt()
