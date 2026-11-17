package dev.voltguard.battery.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.util.OemChargeSettingsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snapshot by BatteryMonitorService.latestSnapshot.collectAsState()
    val decisionLogs by BatteryMonitorService.decisionLogs.collectAsState()
    val kalman by BatteryMonitorService.latestKalmanTelemetry.collectAsState()
    val hmm by BatteryMonitorService.latestHmmPrediction.collectAsState()
    val bandit by BatteryMonitorService.latestBanditMetrics.collectAsState()
    val formatter = SimpleDateFormat("HH:mm", Locale.US)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Diagnostics & Explainability",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF8FAFC)
        )

        if (bandit != null || hmm != null || kalman != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111827),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Internal Control Diagnostics (Bandit · HMM · Kalman)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    bandit?.let { b ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "Arm=%s · Realized=%.2f vs Baseline=%.2f · Regret=%.3f · Guardrail=%s",
                                b.chosenArm.name,
                                b.realizedReward,
                                b.osBaselineReward,
                                b.banditRegret,
                                if (b.hardThermalOverrideApplied) "OVERRIDE" else "NORMAL"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    hmm?.let { h ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "HMM State=%s · P(unplug <=2h)=%.0f%% · Error(MAE)=%dm · Explore=%s",
                                h.dominantState.name,
                                h.pUnplugNext120Min * 100f,
                                h.hmmMaeMinutes,
                                if (h.widenedExploration) "WIDENED" else "CONVERGED"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    kalman?.let { k ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "Kalman slopes: dT/dt=%+.2f °C/min · dV/dt=%+.1f mV/min (14-dim state)",
                                k.dTdtCPerMin,
                                k.dVdtMvPerMin
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF34D399)
                        )
                    }
                }
            }
        }

        if (decisionLogs.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111827),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Intelligence Decision Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                    decisionLogs.take(4).forEach { log ->
                        Text(
                            text = "${formatter.format(Date(log.timestampMs))} · ${log.decidedMode.name} (${log.confidencePct}%): ${log.plainReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }
        }

        val current = snapshot
        if (current == null) {
            Text("Waiting for first BatteryMonitorService telemetry sample...", color = Color(0xFF94A3B8))
            return@Column
        }

        if (current.stayAwakeEnabled || current.usbDebuggingEnabled) {
            DiagnosticCard(
                title = "Developer Options Flagged",
                detail = "Stay Awake or USB Debugging is enabled in Settings.Global, keeping display/wakelocks active while plugged.",
                actionLabel = "Open Developer Options",
                onAction = { OemChargeSettingsHelper.openDeveloperOptions(context) }
            )
        }

        if (current.thermalHeadroom >= 0.85f) {
            DiagnosticCard(
                title = "Elevated Thermal Headroom",
                detail = "PowerManager.getThermalHeadroom() reports sustained heat. Reduce 120Hz gaming wattage or screen brightness.",
                actionLabel = "Open Battery Settings",
                onAction = { OemChargeSettingsHelper.openNativeBatteryProtection(context) }
            )
        }

        if (!current.usageAccessGranted) {
            DiagnosticCard(
                title = "Usage Access Not Granted",
                detail = "Grant Usage Access so VoltGuard can detect foreground games/navigation and identify background wakeup culprits.",
                actionLabel = "Grant Usage Access",
                onAction = { OemChargeSettingsHelper.openUsageAccessSettings(context) }
            )
        }

        DiagnosticCard(
            title = "Doze & Battery Optimization Cooperation",
            detail = "Power Save: ${current.isPowerSaveMode} · Doze Idle: ${current.isDeviceIdleDoze} · Exempted: ${current.isIgnoringBatteryOpt}.",
            actionLabel = "View Exemption Setting",
            onAction = { OemChargeSettingsHelper.openIgnoreBatteryOptimization(context) }
        )
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCBD5E1))
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF022C22))
            ) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
