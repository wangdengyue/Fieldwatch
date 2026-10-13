package app.fieldwatch.analysis

import androidx.room.withTransaction
import app.fieldwatch.analysis.db.AlertEntity
import app.fieldwatch.analysis.db.AnalysisDb
import app.fieldwatch.analysis.db.DeviceEntity
import app.fieldwatch.analysis.db.PresenceEntity
import app.fieldwatch.analysis.db.RotClass
import app.fieldwatch.domain.MacUtil
import app.fieldwatch.domain.Sighting

/** One classified observation ready to persist. [deviceKey] is MAC-based for
 *  FIXED / SEMI_FIXED radios and fingerprint-based for ROTATING groups. */
data class TrackObs(
    val deviceKey: String,
    val rotClass: RotClass,
    val stableKey: String,
    val sighting: Sighting,
    /** Rotating groups: current MAC roll, newest first. Empty otherwise. */
    val recentMacs: String = "",
)

/** What to do with a device's presence span when a new observation arrives. */
sealed interface SpanAction {
    /** No open span, or the last hearing is older than the gap: close any open span, start a new one. */
    data class OpenNew(val closeSpanId: Long?, val closeAt: Long?) : SpanAction

    /** Extend the open span with this sample. */
    data class Extend(val spanId: Long) : SpanAction
}

/**
 * M2 persistence façade: long-lived device rows + presence spans.
 *
 * A presence span closes after [GAP_MS] of silence. While open it aggregates
 * RSSI (running mean/min/max), a centroid of phone-at-hear-time fixes, and the
 * geohash-7 of the latest fix.
 */
class HistoryStore(
    private val db: AnalysisDb,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Silence gap that closes a presence span. */
        const val GAP_MS: Long = 5 * 60_000L

        /** Pure decision, unit-tested without Room. */
        fun decideSpan(prevLastSeen: Long?, openSpanId: Long?, now: Long, gapMs: Long = GAP_MS): SpanAction =
            when {
                openSpanId == null -> SpanAction.OpenNew(null, null)
                prevLastSeen == null || now - prevLastSeen > gapMs ->
                    SpanAction.OpenNew(openSpanId, prevLastSeen)
                else -> SpanAction.Extend(openSpanId)
            }

        fun deviceKeyOf(s: Sighting): String =
            "mac:${s.kind.name}:${MacUtil.normalize(s.mac)}"
    }

    /** Batched ingest of classified observations. One transaction per batch. */
    suspend fun ingest(observations: List<TrackObs>) = db.withTransaction {
        val now = now()
        if (observations.isEmpty()) return@withTransaction
        val keys = observations.map { it.deviceKey }.distinct()
        val existing = db.devices().findByKeys(keys).associateBy { it.deviceKey }
        val openSpans = db.presence().openSpansFor(keys).associateBy { it.deviceKey }

        for (obs in observations) {
            val s = obs.sighting
            val key = obs.deviceKey
            val prev = existing[key]
            val span = openSpans[key]
            val action = decideSpan(prev?.lastSeen, span?.id?.takeIf { it != 0L }, now)
            when (action) {
                is SpanAction.OpenNew -> {
                    action.closeSpanId?.let { id ->
                        span?.let { db.presence().upsert(it.copy(endTs = action.closeAt ?: now)) }
                    }
                    db.presence().upsert(
                        PresenceEntity(
                            deviceKey = key,
                            startTs = now,
                            endTs = 0,
                            rssiAvg = s.rssi,
                            rssiMin = s.rssiMin,
                            rssiMax = s.rssiMax,
                            samples = 1,
                            geohash = geoOf(s),
                            lat = s.latitude,
                            lon = s.longitude,
                        ),
                    )
                }
                is SpanAction.Extend -> {
                    span?.let { db.presence().upsert(extend(it, s)) }
                }
            }
            val newSession = action is SpanAction.OpenNew
            db.devices().upsert(
                DeviceEntity(
                    deviceKey = key,
                    kind = s.kind.name,
                    oui = s.oui,
                    vendor = s.vendor,
                    name = s.name,
                    fleets = s.fleetIds.joinToString(","),
                    rotClass = obs.rotClass.name,
                    stableKey = obs.stableKey,
                    firstSeen = prev?.firstSeen ?: s.firstSeen,
                    lastSeen = now,
                    hitCount = (prev?.hitCount ?: 0L) + 1,
                    sessionCount = (prev?.sessionCount ?: 0) + if (newSession) 1 else 0,
                    lastMac = MacUtil.normalize(s.mac),
                    recentMacs = obs.recentMacs.ifBlank { prev?.recentMacs ?: "" },
                ),
            )
        }
    }

    suspend fun alert(alert: AlertEntity) {
        db.alerts().insert(alert)
    }

    private fun extend(span: PresenceEntity, s: Sighting): PresenceEntity {
        val n = span.samples
        val avg = ((span.rssiAvg.toLong() * n + s.rssi) / (n + 1)).toInt()
        val lat = when {
            s.latitude == null -> span.lat
            span.lat == null -> s.latitude
            else -> (span.lat * n + s.latitude) / (n + 1)
        }
        val lon = when {
            s.longitude == null -> span.lon
            span.lon == null -> s.longitude
            else -> (span.lon * n + s.longitude) / (n + 1)
        }
        return span.copy(
            rssiAvg = avg,
            rssiMin = minOf(span.rssiMin, s.rssiMin),
            rssiMax = maxOf(span.rssiMax, s.rssiMax),
            samples = n + 1,
            geohash = geoOf(s).ifEmpty { span.geohash },
            lat = lat,
            lon = lon,
        )
    }

    private fun geoOf(s: Sighting): String {
        val lat = s.latitude ?: return ""
        val lon = s.longitude ?: return ""
        return GeoHash.encode(lat, lon)
    }

    /** Rolling retention: closed spans, stale devices, and old alerts older than [olderThan]. */
    suspend fun prune(olderThan: Long) {
        db.presence().pruneBefore(olderThan)
        db.devices().pruneBefore(olderThan)
        db.alerts().pruneBefore(olderThan)
    }

    suspend fun tracked(limit: Int = 200): List<DeviceEntity> = db.devices().recent(limit)

    suspend fun spans(deviceKey: String, limit: Int = 50): List<PresenceEntity> =
        db.presence().spansFor(deviceKey, limit)

    suspend fun placeCount(deviceKey: String): Int = db.presence().placeCount(deviceKey)
}
