package app.fieldwatch.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.analysis.db.DeviceEntity
import app.fieldwatch.analysis.db.PresenceEntity
import app.fieldwatch.ui.component.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** M2: engine counters + long-term tracked radios with presence spans. Alerts/baseline land in M4. */
@Composable
fun HistoryScreen(app: FieldwatchApp) {
    val status by app.analysis.status.collectAsState()
    val tracked by app.analysis.tracked.collectAsState()
    var detail by remember { mutableStateOf<DeviceEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionCard(title = "Analysis") {
            Text(
                if (status.enabled) "Recording long-term history" else "Analysis is off (Settings)",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("batches", status.ingestedBatches)
                Stat("radios", status.deviceRows.toLong())
                Stat("rotating", status.rotatingGroups.toLong())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("spans", status.presenceSpans.toLong())
                Stat("baseline", status.baselineBuckets.toLong())
                Stat("alerts", status.alertsUnread.toLong())
            }
        }
        SectionCard(title = "Alerts") {
            Text(
                "Baseline alerts land here (M4).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "Tracked radios") {
            if (tracked.isEmpty()) {
                Text(
                    "Nothing yet. Fixed-MAC radios are recorded as they are heard; rotating stacks join in M3.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tracked.forEach { d -> TrackedRow(d) { detail = d } }
                }
            }
        }
        SectionCard(title = "Baseline") {
            Text(
                "Per-place learning progress lands here (M4).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    detail?.let { d ->
        DeviceDetailDialog(app, d) { detail = null }
    }
}

@Composable
private fun TrackedRow(d: DeviceEntity, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                d.name.ifBlank { d.lastMac },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (d.name.isBlank()) FontFamily.Monospace else null,
            )
            Text(
                ago(d.lastSeen),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            buildString {
                append(d.kind)
                d.vendor?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                append(" · ").append(d.sessionCount).append(if (d.sessionCount == 1) " session" else " sessions")
                append(" · since ").append(dayOf(d.firstSeen))
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeviceDetailDialog(app: FieldwatchApp, d: DeviceEntity, onDismiss: () -> Unit) {
    val spans by produceState<List<PresenceEntity>?>(initialValue = null, d.deviceKey) {
        value = app.analysis.spansFor(d.deviceKey)
    }
    val places by produceState(initialValue = 0, d.deviceKey) {
        value = app.analysis.placeCount(d.deviceKey)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(d.name.ifBlank { d.lastMac }) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${d.kind} · ${d.vendor ?: "unknown vendor"} · ${d.fleets.ifBlank { "no signature" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "First seen ${dayOf(d.firstSeen)} · ${d.hitCount} hearings · $places place${if (places == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    spans == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    spans!!.isEmpty() -> Text("No presence spans.", style = MaterialTheme.typography.bodySmall)
                    else -> spans!!.forEach { s -> SpanRow(s) }
                }
            }
        },
    )
}

@Composable
private fun SpanRow(s: PresenceEntity) {
    val range = "${timeOf(s.startTs)} – ${if (s.endTs == 0L) "now" else timeOf(s.endTs)}"
    val where = when {
        s.geohash.isNotBlank() && s.lat != null && s.lon != null ->
            "%.4f,%.4f (%s)".format(Locale.US, s.lat, s.lon, s.geohash)
        s.geohash.isNotBlank() -> s.geohash
        else -> "no fix"
    }
    Text(
        "$range · ${dayOf(s.startTs)}\n$where · rssi ${s.rssiMin}…${s.rssiMax} avg ${s.rssiAvg} · ${s.samples} samples",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun ago(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000L -> "now"
        d < 3_600_000L -> "${d / 60_000L}m ago"
        d < 86_400_000L -> "${d / 3_600_000L}h ago"
        else -> "${d / 86_400_000L}d ago"
    }
}

private fun dayOf(ts: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(ts))

private fun timeOf(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

@Composable
private fun Stat(label: String, value: Long) {
    Column {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            value.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
