package app.fieldwatch.radio

import android.content.Context
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import app.fieldwatch.domain.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One cell tower, flattened across radio technologies. */
data class CellTower(
    val kind: String, // LTE / NR / WCDMA / GSM / TDSCDMA / CDMA
    val serving: Boolean,
    val mcc: String,
    val mnc: String,
    /** TAC (LTE/NR) or LAC (legacy). */
    val area: Int,
    val cellId: Long,
    /** Physical cell id where the technology has one, else -1. */
    val pci: Int,
    /** EARFCN / NRARFCN / UARFCN / ARFCN. */
    val channel: Int,
    /** RSRP where available, else best-effort RSSI. */
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
) {
    val identity: String get() = "$kind|$mcc$mnc|$area|$cellId|$pci"
    val operator: String get() = operatorName(mcc, mnc)

    companion object {
        fun operatorName(mcc: String, mnc: String): String = when (mcc + mnc) {
            "46000", "46002", "46004", "46007", "46008", "46013" -> "中国移动"
            "46001", "46006", "46009", "46010" -> "中国联通"
            "46003", "46005", "46011", "46012" -> "中国电信"
            "46015" -> "中国广电"
            else -> if (mcc.isNotBlank()) "MCC $mcc MNC $mnc" else "unknown"
        }
    }
}

/** One serving-cell handover. [whileStationary] is the fake-tower smell. */
data class CellChange(
    val at: Long,
    val from: String,
    val to: String,
    val whileStationary: Boolean,
)

data class CellSnapshot(
    val serving: CellTower? = null,
    val neighbors: List<CellTower> = emptyList(),
    val changes: List<CellChange> = emptyList(),
    val updatedAt: Long = 0L,
)

/**
 * Polls the modem for serving + neighbor cells and keeps a handover log.
 *
 * The detection idea (from EFF's Rayhunter, as far as no-root Android allows):
 * a serving-cell change while the phone is *not moving* is worth a second look —
 * real handovers happen because you moved.
 */
class CellMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val pollMs: Long = 15_000L,
    /** Phone position provider: (lat, lon, fixAgeMs) or null. */
    private val fixProvider: () -> Triple<Double, Double, Long>?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _state = MutableStateFlow(CellSnapshot())
    val state: StateFlow<CellSnapshot> = _state.asStateFlow()

    /** Fired once per suspicious handover (change while stationary). */
    var onStationaryHandover: ((CellChange) -> Unit)? = null

    private var lastServingId: String? = null
    private var lastLabel: String = ""
    private val changes = ArrayDeque<CellChange>()
    private val recentFixes = ArrayDeque<Pair<Long, Pair<Double, Double>>>()

    fun start() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { poll() }
                delay(pollMs)
            }
        }
    }

    private fun poll() {
        noteFix()
        val cells = runCatching { tm.allCellInfo }.getOrNull() ?: return
        val towers = cells.mapNotNull { it.toTower() }
        val serving = towers.firstOrNull { it.serving }
        val now = now()

        val id = serving?.identity
        if (id != null && lastServingId != null && id != lastServingId) {
            val change = CellChange(
                at = now,
                from = lastLabel,
                to = serving.label(),
                whileStationary = isStationary(now),
            )
            changes.addFirst(change)
            while (changes.size > 100) changes.removeLast()
            if (change.whileStationary) onStationaryHandover?.invoke(change)
        }
        if (id != null) {
            lastServingId = id
            lastLabel = serving?.label() ?: lastLabel
        }
        _state.value = CellSnapshot(
            serving = serving,
            neighbors = towers.filter { !it.serving },
            changes = changes.toList(),
            updatedAt = now,
        )
    }

    private fun noteFix() {
        val fix = fixProvider() ?: return
        recentFixes.addLast(now() to (fix.first to fix.second))
        while (recentFixes.isNotEmpty() && now() - recentFixes.first().first > 120_000L) {
            recentFixes.removeFirst()
        }
    }

    /** Moved less than 30 m in the last two minutes (with a fresh fix). */
    private fun isStationary(now: Long): Boolean {
        if (recentFixes.size < 2) return false
        val first = recentFixes.first()
        if (now - first.first < 60_000L) return false // not enough history to call it parked
        val last = recentFixes.last()
        val d = Geo.meters(
            first.second.first, first.second.second,
            last.second.first, last.second.second,
        )
        return d < 30.0
    }

    private fun CellTower.label(): String =
        "$kind ${operator} · cell $cellId" + if (pci >= 0) " · pci $pci" else ""
}

private fun CellInfo.toTower(): CellTower? {
    val tower: CellTower? = when (this) {
    is CellInfoLte -> CellTower(
        "LTE", isRegistered,
        cellIdentity.mccString.orEmpty(), cellIdentity.mncString.orEmpty(),
        cellIdentity.tac, cellIdentity.ci.toLong(), cellIdentity.pci,
        cellIdentity.earfcn, cellSignalStrength.rsrp, cellSignalStrength.rsrq,
    )
    is CellInfoNr -> {
        val id = cellIdentity as android.telephony.CellIdentityNr
        val ss = cellSignalStrength as android.telephony.CellSignalStrengthNr
        CellTower(
            "NR", isRegistered,
            id.mccString.orEmpty(), id.mncString.orEmpty(),
            id.tac, id.nci, id.pci, id.nrarfcn,
            ss.ssRsrp, ss.ssRsrq,
        )
    }
    is CellInfoWcdma -> CellTower(
        "WCDMA", isRegistered,
        cellIdentity.mccString.orEmpty(), cellIdentity.mncString.orEmpty(),
        cellIdentity.lac, cellIdentity.cid.toLong(), cellIdentity.psc,
        cellIdentity.uarfcn, cellSignalStrength.dbm, null,
    )
    is CellInfoGsm -> CellTower(
        "GSM", isRegistered,
        cellIdentity.mccString.orEmpty(), cellIdentity.mncString.orEmpty(),
        cellIdentity.lac, cellIdentity.cid.toLong(), -1,
        cellIdentity.arfcn, cellSignalStrength.dbm, null,
    )
    is CellInfoTdscdma -> CellTower(
        "TDSCDMA", isRegistered,
        cellIdentity.mccString.orEmpty(), cellIdentity.mncString.orEmpty(),
        cellIdentity.lac, cellIdentity.cid.toLong(), -1,
        cellIdentity.uarfcn, cellSignalStrength.dbm, null,
    )
    is CellInfoCdma -> CellTower(
        "CDMA", isRegistered, "", "",
        cellIdentity.basestationId, cellIdentity.networkId.toLong(), -1,
        -1, cellSignalStrength.dbm, null,
    )
    else -> null
}
    return tower?.takeIf { it.cellId != Long.MAX_VALUE && it.cellId > 0 }
}
