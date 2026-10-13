package app.fieldwatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.radio.CellChange
import app.fieldwatch.radio.CellTower
import app.fieldwatch.ui.component.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serving + neighbor cells and the handover log. The fake-tower watch lives here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellsScreen(app: FieldwatchApp, onBack: () -> Unit) {
    val cells by app.cells.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cell towers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard(title = "Serving cell") {
                val s = cells.serving
                if (s == null) {
                    Text(
                        "No cell info. Needs an active SIM and Location permission.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    TowerBlock(s)
                }
            }
            SectionCard(title = "Handovers") {
                if (cells.changes.isEmpty()) {
                    Text(
                        "No serving-cell changes yet this session. A change while the phone is parked is flagged below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cells.changes.forEach { ChangeRow(it) }
                    }
                }
            }
            SectionCard(title = "Neighbors (${cells.neighbors.size})") {
                if (cells.neighbors.isEmpty()) {
                    Text(
                        "None reported by the modem.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cells.neighbors.forEach { TowerBlock(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TowerBlock(t: CellTower) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${t.kind} · ${t.operator}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                t.rsrpDbm?.let { "$it dBm" } ?: "",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            buildString {
                append("cell ${t.cellId} · area ${t.area}")
                if (t.pci >= 0) append(" · pci ${t.pci}")
                if (t.channel > 0) append(" · ch ${t.channel}")
                t.rsrqDb?.let { append(" · rsrq $it dB") }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ChangeRow(c: CellChange) {
    Column {
        Text(
            timeOf(c.at) + "  " + c.from + "  →  " + c.to,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (c.whileStationary) {
            Text(
                "⚠ switched while the phone was parked — a classic fake-tower sign (or a network-side handover)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun timeOf(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
