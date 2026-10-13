package app.fieldwatch.analysis

import android.content.Context
import app.fieldwatch.analysis.db.AlertEntity
import app.fieldwatch.analysis.db.AnalysisDb
import app.fieldwatch.analysis.db.DeviceEntity
import app.fieldwatch.analysis.db.PresenceEntity
import app.fieldwatch.analysis.db.RotClass
import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.MacUtil
import app.fieldwatch.domain.Sighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AnalysisStatus(
    val enabled: Boolean = false,
    val ingestedBatches: Long = 0,
    val deviceRows: Int = 0,
    val rotatingGroups: Int = 0,
    val presenceSpans: Int = 0,
    val baselineBuckets: Int = 0,
    val alertsUnread: Int = 0,
)

/**
 * Analysis layer entry point. Fed from ScanService right after DeviceStore.ingestBatch.
 * M2: devices + presence spans persist through HistoryStore; retention prunes hourly.
 * M3 adds MAC-merge, M4 baseline alerts.
 */
class AnalysisEngine(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val db by lazy { AnalysisDb.get(context.applicationContext) }
    private val store by lazy { HistoryStore(db) }
    private val _status = MutableStateFlow(AnalysisStatus())
    val status: StateFlow<AnalysisStatus> = _status.asStateFlow()

    /** Long-term tracked radios for the History tab, refreshed with the counters. */
    private val _tracked = MutableStateFlow<List<DeviceEntity>>(emptyList())
    val tracked: StateFlow<List<DeviceEntity>> = _tracked.asStateFlow()

    /** Alert timeline (rotation clusters, correlated MACs; baseline alerts land in M4). */
    private val _alerts = MutableStateFlow<List<AlertEntity>>(emptyList())
    val alerts: StateFlow<List<AlertEntity>> = _alerts.asStateFlow()

    private val macMerge = MacMerge()

    @Volatile var enabled = true

    /** Retention for devices / spans / alerts. Mirrors Settings.analysisRetainDays. */
    @Volatile var retainDays = 30

    private val writeMutex = Mutex()
    private var lastRefreshAt = 0L
    private var lastPruneAt = 0L

    /** Non-blocking: batches arrive on the scan pump; never stall it. */
    fun ingest(seen: List<Sighting>, fleets: List<Fleet>) {
        if (!enabled || seen.isEmpty()) return
        scope.launch(Dispatchers.Default) {
            runCatching { persist(seen) }
            _status.value = _status.value.copy(
                enabled = enabled,
                ingestedBatches = _status.value.ingestedBatches + 1,
            )
            val now = System.currentTimeMillis()
            if (now - lastPruneAt >= 3_600_000L) {
                lastPruneAt = now
                runCatching { store.prune(now - retainDays * 86_400_000L) }
            }
            if (now - lastRefreshAt >= 10_000L) {
                lastRefreshAt = now
                refreshCounts()
            }
        }
    }

    private suspend fun persist(seen: List<Sighting>) = writeMutex.withLock {
        val observations = ArrayList<TrackObs>(seen.size)
        val newAlerts = ArrayList<AlertEntity>()
        for (s in seen) {
            observations += classify(s, newAlerts)
        }
        store.ingest(observations)
        for (a in newAlerts) store.alert(a)
    }

    /**
     * SEMI_FIXED (separated tracker holding one address) and FIXED radios key on
     * the MAC; everything else with a rotating address keys on its content
     * fingerprint and feeds MacMerge.
     */
    private fun classify(s: Sighting, alerts: MutableList<AlertEntity>): TrackObs {
        val macKey = "mac:${s.kind.name}:${MacUtil.normalize(s.mac)}"
        if (!s.randomized) {
            return TrackObs(macKey, RotClass.FIXED, "", s)
        }
        if (isSeparatedTracker(s)) {
            return TrackObs(macKey, RotClass.SEMI_FIXED, "", s)
        }
        val sk = Fingerprint.stableKey(s)
        for (event in macMerge.observe(s, sk)) {
            alerts += alertFor(event, s)
        }
        return TrackObs(
            deviceKey = "sk:$sk",
            rotClass = RotClass.ROTATING,
            stableKey = sk,
            sighting = s,
            recentMacs = macMerge.macsFor(sk).take(16).joinToString(","),
        )
    }

    private fun alertFor(event: MergeEvent, s: Sighting): AlertEntity {
        val label = s.name.ifBlank { s.vendor ?: "unnamed" }
        val geo = if (s.latitude != null && s.longitude != null) {
            GeoHash.encode(s.latitude, s.longitude)
        } else {
            ""
        }
        return when (event) {
            is MergeEvent.Rotation -> AlertEntity(
                ts = System.currentTimeMillis(),
                type = "ROTATING_CLUSTER",
                deviceKey = "sk:${event.stableKey}",
                title = "Rotating radio: $label",
                detail = "Same content fingerprint on ${event.macCount} MACs. " +
                    "${event.fromMac} went quiet, ${event.toMac} appeared " +
                    "${event.gapMs / 1000}s later. Likely one rotating device.",
                geohash = geo,
            )
            is MergeEvent.Correlated -> AlertEntity(
                ts = System.currentTimeMillis(),
                type = "ROTATING_CLUSTER",
                deviceKey = "sk:${event.stableKey}",
                title = "MACs move together: $label",
                detail = "${event.macA} and ${event.macB} share a fingerprint and their RSSI " +
                    "moves together (r=${"%.2f".format(java.util.Locale.US, event.r)} over ${event.buckets} buckets). " +
                    "Probably one physical radio.",
                geohash = geo,
            )
        }
    }

    /**
     * A tracker separated from its owner holds one address for about a day —
     * long enough to track by MAC. Detect via DULT / Find Hub separated frames,
     * or an Apple Find My payload with the maintained bit clear.
     */
    private fun isSeparatedTracker(s: Sighting): Boolean {
        for (rec in s.facts.serviceData) {
            val uuid = rec.uuid.filter { it.isLetterOrDigit() }.uppercase()
            val hex = rec.dataHex.filter { it.isLetterOrDigit() }.uppercase()
            when {
                uuid.endsWith("FEAA") && hex.length >= 2 -> if (hex.take(2) == "41") return true
                uuid.endsWith("FCB2") && hex.length >= 4 -> {
                    if (hex.substring(2, 4).toIntOrNull(16)?.let { it and 1 == 0 } == true) return true
                }
            }
        }
        for (m in s.facts.mfgRecords) {
            if (m.companyId != 0x004C) continue
            val hex = m.dataHex.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length >= 4 && hex.startsWith("10")) {
                val status = hex.substring(2, 4).toIntOrNull(16) ?: continue
                if (status and 0x04 == 0) return true
            }
        }
        return false
    }

    private suspend fun refreshCounts() {
        runCatching {
            _status.value = _status.value.copy(
                deviceRows = db.devices().count(),
                rotatingGroups = db.devices().rotatingCount(),
                presenceSpans = db.presence().count(),
                baselineBuckets = db.baseline().count(),
                alertsUnread = db.alerts().unreadCount(),
            )
            _tracked.value = store.tracked()
            _alerts.value = db.alerts().latest(50)
        }
    }

    suspend fun spansFor(deviceKey: String): List<PresenceEntity> = store.spans(deviceKey)

    suspend fun placeCount(deviceKey: String): Int = store.placeCount(deviceKey)

    suspend fun markAlertRead(id: Long) {
        runCatching {
            db.alerts().markRead(id)
            _alerts.value = db.alerts().latest(50)
            _status.value = _status.value.copy(alertsUnread = db.alerts().unreadCount())
        }
    }
}
