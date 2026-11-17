package dev.voltguard.battery.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.QsTargetKey
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.service.VoltGuardAccessibilityService
import dev.voltguard.battery.tile.OptimizerTileService
import dev.voltguard.battery.util.OemChargeSettingsHelper
import dev.voltguard.battery.util.QuickSettingsExecutor

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = (context.applicationContext as VoltGuardApp).preferences

    var autoMode by remember { mutableStateOf(prefs.getTileSelection() == TileSelection.AUTO) }
    var aggressiveness by remember { mutableFloatStateOf(prefs.getAggressiveness().toFloat()) }
    var persistentNotif by remember { mutableStateOf(prefs.isPersistentNotificationEnabled()) }
    var nudge80 by remember { mutableStateOf(prefs.isNudge80Enabled()) }
    var rootOptIn by remember { mutableStateOf(prefs.isRootBridgeOptInEnabled()) }
    var accessibilityAutoOff by remember { mutableStateOf(prefs.isAccessibilityAutoOffEnabled()) }
    val targetStates = remember {
        mutableStateMapOf<QsTargetKey, Boolean>().apply {
            QsTargetKey.entries.forEach { put(it, prefs.isQsTargetEnabled(it)) }
        }
    }

    val shizukuRunning = QuickSettingsExecutor.isShizukuRunning()
    val shizukuGranted = QuickSettingsExecutor.hasShizukuPermission()
    val writeSecureGranted = OemChargeSettingsHelper.hasWriteSecureSettings(context)
    val writeSettingsGranted = Settings.System.canWrite(context)
    val dndGranted = OemChargeSettingsHelper.hasNotificationPolicyAccess(context)
    val adminGranted = OemChargeSettingsHelper.hasDeviceAdmin(context)
    val a11yRunning = VoltGuardAccessibilityService.isRunning()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Optimizer Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF8FAFC)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic Context Mode", fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Let AdaptiveIntelligenceEngine fuse signals and switch modes automatically.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    Switch(checked = autoMode, onCheckedChange = {
                        autoMode = it
                        prefs.setTileSelection(if (it) TileSelection.AUTO else TileSelection.OFF)
                        runCatching { OptimizerTileService.requestTileSync(context.applicationContext) }
                    })
                }

                Column {
                    Text("Aggressiveness: ${aggressiveness.toInt()} (Battery-first ↔ Performance-first)", fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                    Slider(
                        value = aggressiveness,
                        onValueChange = { aggressiveness = it },
                        onValueChangeFinished = { prefs.setAggressiveness(aggressiveness.toInt()) },
                        valueRange = 0f..100f
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Persistent Shade Notification Card", fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Off (Recommended): Runs silently via Quick Settings Tile & WorkManager with zero notification card in the shade.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    Switch(checked = persistentNotif, onCheckedChange = {
                        persistentNotif = it
                        prefs.setPersistentNotificationEnabled(it)
                        val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                        if (it) {
                            ContextCompat.startForegroundService(context, serviceIntent)
                        } else {
                            context.stopService(serviceIntent)
                        }
                    })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full Charge (100%) Completion Alert", fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Off by default: enable only if you want a notification when Fast Charge Assist reaches 99–100%.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    Switch(checked = nudge80, onCheckedChange = {
                        nudge80 = it
                        prefs.setNudge80Enabled(it)
                    })
                }
            }
        }

        // Quick Settings "All Off" Targets (Hotspot & Rotation Lock are permanently excluded and NEVER appear)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quick Settings All-Off Targets", fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Text(
                    "Wi-Fi Hotspot & Rotation Lock are strictly excluded and never appear here. Mobile Data defaults OFF so Hotspot keeps internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                QsTargetKey.entries.forEach { key ->
                    val checked = targetStates[key] ?: (key != QsTargetKey.MOBILE_DATA)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !checked
                                targetStates[key] = next
                                prefs.setQsTargetEnabled(key, next)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { next ->
                                targetStates[key] = next
                                prefs.setQsTargetEnabled(key, next)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981))
                        )
                        Text(
                            text = if (key == QsTargetKey.MOBILE_DATA) "${key.title} (Default: Do Not Touch)" else key.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }
        }

        // Non-Rooted Permissions & MIUI POCO X3 Setup Panel
        val shizukuStatusLabel = if (shizukuGranted) "GRANTED" else if (shizukuRunning) "RUNNING" else "OFF"
        val writeSecureLabel = if (writeSecureGranted) "OK" else "NO"
        val writeSettingsLabel = if (writeSettingsGranted) "OK" else "NO"
        val dndLabel = if (dndGranted) "OK" else "NO"
        val adminLabel = if (adminGranted) "OK" else "NO"
        val a11yStatusLabel = if (a11yRunning) "Running" else "Off"
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions & Non-Root Backends (POCO X3)", fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Text(
                    "Shizuku=$shizukuStatusLabel · ADB_SECURE=$writeSecureLabel · WRITE_SETTINGS=$writeSettingsLabel · DND=$dndLabel · LOCK_ADMIN=$adminLabel",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF34D399)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { QuickSettingsExecutor.requestShizukuPermission(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (shizukuGranted) "Shizuku OK" else "Shizuku Grant") }
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString("adb shell pm grant dev.voltguard.battery android.permission.WRITE_SECURE_SETTINGS"))
                            Toast.makeText(context, "Copied ADB WRITE_SECURE_SETTINGS", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy ADB Cmd") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { OemChargeSettingsHelper.ensureShieldPermissions(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Admin / DND / Write") }
                    OutlinedButton(
                        onClick = { OemChargeSettingsHelper.openDeveloperOptions(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("MIUI Dev Opts") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Opt-in Accessibility QS Fallback ($a11yStatusLabel)", fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Last-resort helper if Shizuku/ADB unavailable (never clicks Hotspot or Rotation).", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    Switch(checked = accessibilityAutoOff, onCheckedChange = {
                        accessibilityAutoOff = it
                        prefs.setAccessibilityAutoOffEnabled(it)
                        if (it && !a11yRunning) OemChargeSettingsHelper.openAccessibilitySettings(context)
                    })
                }

                Text(
                    "MIUI Checklist: Enable Developer Options -> USB debugging + USB debugging (Security settings) for ADB writes; set Battery saver = No restrictions and enable Autostart. If Shizuku uses Wireless debugging, it stops on reboot until restarted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transparent Permissions", fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Text("Usage Access lets VoltGuard pause throttling during games/navigation. Battery Optimization exemption prevents premature service termination.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { OemChargeSettingsHelper.openUsageAccessSettings(context) }) {
                        Text("Usage Access")
                    }
                    OutlinedButton(onClick = { OemChargeSettingsHelper.openIgnoreBatteryOptimization(context) }) {
                        Text("Monitor Exemption")
                    }
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
                Text("Advanced (Root) — Optional Manual Opt-In", fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Inert unless you have already rooted this device manually (e.g., Magisk) and approve the superuser prompt yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rootOptIn) "Manual Opt-In Armed (Requires Magisk grant)" else "Inactive (Stock Non-Root Mode)",
                        color = Color(0xFFCBD5E1)
                    )
                    Switch(checked = rootOptIn, onCheckedChange = {
                        rootOptIn = it
                        prefs.setRootBridgeOptInEnabled(it)
                    })
                }
            }
        }
    }
}
