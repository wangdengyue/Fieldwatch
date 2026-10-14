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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fieldwatch.radio.LanHost
import app.fieldwatch.ui.FieldwatchViewModel
import app.fieldwatch.ui.component.SectionCard

/** LAN scan of the network this phone is on. Opened from a Wi-Fi radio's detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScanScreen(vm: FieldwatchViewModel, onBack: () -> Unit) {
    val app = vm.fieldwatchApp
    val scan by app.lanScanner.state.collectAsState()

    LaunchedEffect(Unit) {
        if (!scan.running && scan.hosts.isEmpty() && scan.error == null) {
            app.lanScanner.scan()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network scan") },
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
            SectionCard(title = "This network") {
                Text(
                    "${scan.ssid.ifBlank { "unknown SSID" }} · you are ${scan.ownIp.ifBlank { "…" }} · gateway ${scan.gatewayIp.ifBlank { "…" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (scan.running) {
                    LinearProgressIndicator(
                        progress = { if (scan.totalHosts > 0) scan.scannedHosts.toFloat() / scan.totalHosts else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Sweeping ${scan.scannedHosts}/${scan.totalHosts} · SSDP + ONVIF probes out",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                scan.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (!scan.running && scan.error == null) {
                    val suspects = scan.hosts.count { it.cameraSuspect }
                    Text(
                        "${scan.hosts.size} hosts · $suspects camera suspect${if (suspects == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (suspects > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (scan.hosts.isNotEmpty()) {
                SectionCard(title = "Hosts") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scan.hosts.forEach { HostRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRow(h: LanHost) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                h.ip,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (h.cameraSuspect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                h.vendor ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                if (h.mac.isNotBlank()) append(h.mac).append(" · ")
                if (h.openPorts.isNotEmpty()) append("ports ").append(h.openPorts.joinToString(","))
                if (h.onvif) append(" · ONVIF")
                if (h.fleetNames.isNotEmpty()) append(" · ").append(h.fleetNames.joinToString(", "))
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        h.ssdp?.let {
            Text(
                it.lineSequence().take(2).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (h.cameraSuspect) {
            Text(
                "camera suspect — check ports / ONVIF / vendor above",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
