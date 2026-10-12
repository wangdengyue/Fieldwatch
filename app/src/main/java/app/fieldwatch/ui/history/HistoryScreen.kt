package app.fieldwatch.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.ui.component.SectionCard

/** M1 skeleton: engine counters. Alerts / tracked radios / baseline land in M2-M4. */
@Composable
fun HistoryScreen(app: FieldwatchApp) {
    val status by app.analysis.status.collectAsState()
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
            Text(
                "Semi-fixed trackers and rotating groups land here (M2/M3).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "Baseline") {
            Text(
                "Per-place learning progress lands here (M4).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

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
