package app.fieldwatch.analysis

import android.content.Context
import app.fieldwatch.analysis.db.AnalysisDb
import app.fieldwatch.analysis.db.DeviceEntity
import app.fieldwatch.analysis.db.PresenceEntity
import app.fieldwatch.domain.Fleet
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
        store.ingest(seen)
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
        }
    }

    suspend fun spansFor(deviceKey: String): List<PresenceEntity> = store.spans(deviceKey)

    suspend fun placeCount(deviceKey: String): Int = store.placeCount(deviceKey)
}
