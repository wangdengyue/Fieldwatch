package app.fieldwatch.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.domain.Geo
import app.fieldwatch.domain.LocateEngine
import app.fieldwatch.domain.LocateQuality
import app.fieldwatch.domain.LocateSample
import app.fieldwatch.ui.FieldwatchViewModel
import kotlin.math.cos
import kotlin.math.max

/**
 * Multilateration view: walk around while the target is heard, and the map
 * narrows down where it sits. Samples are the phone's own fixes tagged with
 * the target's RSSI at that moment (its gpsTrail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocateScreen(
    vm: FieldwatchViewModel,
    onBack: () -> Unit,
) {
    val target by vm.locateTarget.collectAsStateWithLifecycle()
    val t = target
    val samples = t?.gpsTrail
        ?.filter { it.rssi < 0 }
        ?.map { LocateSample(it.at, it.lat, it.lon, it.rssi) }
        ?: emptyList()
    val estimate = LocateEngine.estimate(samples)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locate") },
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
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (t == null) {
                Text(
                    "Target left the air. Its last fixes stay on the plot.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    (t.name.ifBlank { t.mac }) + " · rssi " + t.rssi,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            val est = estimate
            Text(
                when {
                    samples.size < LocateEngine.MIN_SAMPLES ->
                        "Walk with the phone. ${samples.size}/${LocateEngine.MIN_SAMPLES} fixes so far — GPS tagging must be on."
                    est == null -> "Not enough usable fixes yet."
                    else -> buildString {
                        append("Estimate: ~")
                        append(est.radiusM.toInt())
                        append(" m circle · ")
                        append(est.samples)
                        append(" fixes · coverage ")
                        append(est.coverageDeg)
                        append("° · ")
                        append(
                            when (est.quality) {
                                LocateQuality.GOOD -> "good"
                                LocateQuality.FAIR -> "fair"
                                else -> "rough"
                            },
                        )
                    }
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (est != null && est.coverageDeg < 200) {
                Text(
                    "One-sided so far — walk around to the other side of the signal to tighten the circle.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LocatePlot(
                samples = samples,
                estLat = est?.lat,
                estLon = est?.lon,
                estRadiusM = est?.radiusM ?: 0.0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            )

            Text(
                "RSSI lies by 10-20 dB through walls and bodies. The circle is where it likely sits, not a pin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** North-up plot: your walked path, per-fix dots, and the estimate circle. */
@Composable
private fun LocatePlot(
    samples: List<LocateSample>,
    estLat: Double?,
    estLon: Double?,
    estRadiusM: Double,
    modifier: Modifier = Modifier,
) {
    val pathColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val estColor = MaterialTheme.colorScheme.error
    val youColor = Color(0xFF4CAF50)

    Canvas(modifier) {
        if (samples.isEmpty()) return@Canvas
        val lat0 = samples.map { it.lat }.average()
        val lon0 = samples.map { it.lon }.average()
        val mPerDegLat = 110_540.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

        fun px(lat: Double, lon: Double): Offset {
            val xM = (lon - lon0) * mPerDegLon
            val yM = (lat - lat0) * mPerDegLat
            return Offset(xM.toFloat(), (-yM).toFloat()) // north up
        }

        val pts = samples.map { px(it.lat, it.lon) }
        val allX = pts.map { it.x } + listOfNotNull(estLat?.let { px(it, estLon!!).x })
        val allY = pts.map { it.y } + listOfNotNull(estLat?.let { px(it, estLon!!).y })
        val spanX = (allX.max() - allX.min()).coerceAtLeast((estRadiusM * 2).toFloat()).coerceAtLeast(10f)
        val spanY = (allY.max() - allY.min()).coerceAtLeast((estRadiusM * 2).toFloat()).coerceAtLeast(10f)
        val scale = minOf(size.width / spanX, size.height / spanY) * 0.8f
        val cx = (allX.min() + allX.max()) / 2f
        val cy = (allY.min() + allY.max()) / 2f
        val ox = size.width / 2f - cx * scale
        val oy = size.height / 2f - cy * scale

        fun Offset.mapped() = Offset(x * scale + ox, y * scale + oy)

        // Walked path.
        for (i in 1 until pts.size) {
            drawLine(pathColor, pts[i - 1].mapped(), pts[i].mapped(), strokeWidth = 3f)
        }
        // Per-fix dots, stronger signal = bigger dot.
        val rssiMin = samples.minOf { it.rssi }
        val rssiMax = samples.maxOf { it.rssi }
        samples.forEachIndexed { i, s ->
            val span = max(1, rssiMax - rssiMin)
            val r = 3f + 5f * (s.rssi - rssiMin) / span
            drawCircle(dotColor, radius = r, center = pts[i].mapped())
        }
        // Estimate.
        if (estLat != null && estLon != null) {
            val c = px(estLat, estLon).mapped()
            drawCircle(
                estColor,
                radius = (estRadiusM.toFloat() * scale).coerceAtLeast(12f),
                center = c,
                style = Stroke(width = 2f),
            )
            drawLine(estColor, Offset(c.x - 10, c.y), Offset(c.x + 10, c.y), strokeWidth = 2f)
            drawLine(estColor, Offset(c.x, c.y - 10), Offset(c.x, c.y + 10), strokeWidth = 2f)
        }
        // You are here (last fix).
        drawCircle(youColor, radius = 7f, center = pts.last().mapped())
    }
}
