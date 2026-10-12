package app.fieldwatch.analysis

import android.content.Context
import app.fieldwatch.analysis.db.AnalysisDb
import app.fieldwatch.analysis.db.DeviceEntity
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
 * M1: persist fixed/semi-fixed radios to Room and expose counters.
 * M2 adds presence spans, M3 MAC-merge, M4 baseline alerts.
 */
class AnalysisEngine(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val db by lazy { AnalysisDb.get(context.applicationContext) }
    private val _status = MutableStateFlow(AnalysisStatus())
    val status: StateFlow<AnalysisStatus> = _status.asStateFlow()

    @Volatile var enabled = true
    private val writeMutex = Mutex()
    private var lastRefreshAt = 0L

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
            if (now - lastRefreshAt >= 10_000L) {
                lastRefreshAt = now
                refreshCounts()
            }
        }
    }

    private suspend fun persist(seen: List<Sighting>) = writeMutex.withLock {
        val now = System.currentTimeMillis()
        for (s in seen) {
            val rotClass = when {
                !s.randomized -> RotClass.FIXED
                else -> RotClass.ROTATING
            }
            // M1: skip rotating radios until MacMerge lands (M3).
            if (rotClass == RotClass.ROTATING) continue
            val key = "mac:${s.kind.name}:${MacUtil.normalize(s.mac)}"
            val existing = db.devices().find(key)
            val row = DeviceEntity(
                deviceKey = key,
                kind = s.kind.name,
                oui = s.oui,
                vendor = s.vendor,
                name = s.name,
                fleets = s.fleetIds.joinToString(","),
                rotClass = rotClass.name,
                stableKey = "",
                firstSeen = existing?.firstSeen ?: s.firstSeen,
                lastSeen = now,
                hitCount = (existing?.hitCount ?: 0L) + 1,
                sessionCount = existing?.sessionCount ?: 1,
                lastMac = MacUtil.normalize(s.mac),
            )
            db.devices().upsert(row)
        }
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
        }
    }
}
