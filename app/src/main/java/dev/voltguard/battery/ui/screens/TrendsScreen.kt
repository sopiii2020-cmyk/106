package dev.voltguard.battery.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.service.BatteryMonitorService
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TrendsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as VoltGuardApp
    var windowDays by remember { mutableStateOf(1) }
    val sinceMs = remember(windowDays) {
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(windowDays.toLong())
    }
    val samples by app.database.sampleDao().observeSince(sinceMs).collectAsState(emptyList())
    val outcome by BatteryMonitorService.latestOutcomeFeedback.collectAsState()
    val banditMetrics by BatteryMonitorService.latestBanditMetrics.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Battery & Thermal Trends",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF8FAFC)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrendStatCard("Cycles Saved", String.format(Locale.US, "%.1f", outcome?.estimatedCyclesSaved ?: 0f), Modifier.weight(1f))
            TrendStatCard("Extra Lifespan", String.format(Locale.US, "+%.1f mo", outcome?.estimatedExtraMonths ?: 0f), Modifier.weight(1f))
            TrendStatCard("Wear Avoided", String.format(Locale.US, "%.1f%%", banditMetrics?.wearAvoidedPct ?: 0f), Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "24h", 7 to "7d", 30 to "30d").forEach { (days, label) ->
                FilterChip(
                    selected = windowDays == days,
                    onClick = { windowDays = days },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF064E3B),
                        selectedLabelColor = Color(0xFF6EE7B7)
                    )
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Charge Level (%) — ${samples.size} Room samples", style = MaterialTheme.typography.labelLarge, color = Color(0xFFE2E8F0))
                Spacer(Modifier.height(12.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    if (samples.size < 2) return@Canvas
                    val path = Path()
                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                    samples.forEachIndexed { idx, s ->
                        val x = idx * stepX
                        val y = size.height - (s.batteryPct / 100f) * size.height
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = Color(0xFF10B981), style = Stroke(width = 4f))
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery Temperature (°C)", style = MaterialTheme.typography.labelLarge, color = Color(0xFFE2E8F0))
                Spacer(Modifier.height(12.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                    if (samples.size < 2) return@Canvas
                    val path = Path()
                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                    samples.forEachIndexed { idx, s ->
                        val x = idx * stepX
                        val normTemp = ((s.temperatureC - 20f) / 30f).coerceIn(0f, 1f)
                        val y = size.height - normTemp * size.height
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = Color(0xFFF59E0B), style = Stroke(width = 4f))
                    drawLine(
                        color = Color(0x44F43F5E),
                        start = Offset(0f, size.height * 0.3f),
                        end = Offset(size.width, size.height * 0.3f),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF34D399)
            )
        }
    }
}
