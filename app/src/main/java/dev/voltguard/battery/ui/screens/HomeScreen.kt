package dev.voltguard.battery.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.voltguard.battery.R
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.data.db.CycleStatsEstimator
import dev.voltguard.battery.engine.ChargeNudge
import dev.voltguard.battery.engine.HeavyAppUsage
import dev.voltguard.battery.engine.HomeProfileMode
import dev.voltguard.battery.engine.ItemExecState
import dev.voltguard.battery.engine.PluggedSource
import dev.voltguard.battery.engine.PowerDecisionEngine
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.service.InactivityShieldController
import dev.voltguard.battery.tile.OptimizerTileService
import dev.voltguard.battery.util.OemChargeSettingsHelper
import dev.voltguard.battery.util.QuickSettingsExecutor
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val app = context.applicationContext as VoltGuardApp
    val scope = rememberCoroutineScope()

    val snapshot by BatteryMonitorService.latestSnapshot.collectAsState()
    val decision by BatteryMonitorService.latestDecision.collectAsState()
    val runtimeHeadline by BatteryMonitorService.liveRuntimeHeadline.collectAsState()
    val runtimeDetail by BatteryMonitorService.liveRuntimeDetail.collectAsState()
    val effectiveCurrentMa by BatteryMonitorService.effectiveCurrentMa.collectAsState()
    val shieldStatusText by InactivityShieldController.shieldStatusText.collectAsState()
    val lastExecResults by QuickSettingsExecutor.lastExecutionResults.collectAsState()
    val needsMiuiSecSetting by QuickSettingsExecutor.needsMiuiSecuritySetting.collectAsState()
    val banditMetrics by BatteryMonitorService.latestBanditMetrics.collectAsState()
    val batteryApps by BatteryMonitorService.heavyBatteryApps.collectAsState()
    val cpuTempApps by BatteryMonitorService.heavyCpuTempApps.collectAsState()
    val totalChargedUah by app.database.sampleDao().observeTotalChargedUah().collectAsState(0L)
    val cycles = CycleStatsEstimator.estimateCycles(totalChargedUah)

    val activeTile = snapshot?.tileSelection ?: app.preferences.getTileSelection()
    val syncedProfile = when (activeTile) {
        TileSelection.PRFO -> HomeProfileMode.PERFORMANCE
        TileSelection.ULTRA -> HomeProfileMode.ULTRA_SAFE
        TileSelection.INTEL -> HomeProfileMode.INTELLIGENT
        TileSelection.AUTO, TileSelection.OFF -> HomeProfileMode.AUTOMATIC
    }
    var selectedProfile by remember(syncedProfile) { mutableStateOf(syncedProfile) }
    var blockedSet by remember { mutableStateOf(app.preferences.getBlockedPackages()) }
    var guardAllBattery by remember { mutableStateOf(app.preferences.isGuardAllBatteryAppsEnabled()) }
    var guardAllCpu by remember { mutableStateOf(app.preferences.isGuardAllCpuAppsEnabled()) }
    var inactivityShieldOn by remember { mutableStateOf(app.preferences.isInactivityShieldEnabled()) }

    val onSelectProfile: (HomeProfileMode) -> Unit = { profile ->
        selectedProfile = profile
        app.preferences.setHomeProfile(profile)
        runCatching { OptimizerTileService.requestTileSync(context.applicationContext) }
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    val onTapHeavyApp: (HeavyAppUsage) -> Unit = { item ->
        OemChargeSettingsHelper.stopOrOpenForceStop(
            context = context,
            packageName = item.packageName,
            rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
        )
    }

    val onToggleBlockedApp: (HeavyAppUsage) -> Unit = { item ->
        val nowBlocked = app.preferences.toggleBlockedPackage(item.packageName)
        blockedSet = app.preferences.getBlockedPackages()
        if (!nowBlocked) {
            if (batteryApps.any { it.packageName == item.packageName } && guardAllBattery) {
                guardAllBattery = false
                app.preferences.setGuardAllBatteryAppsEnabled(false)
            }
            if (cpuTempApps.any { it.packageName == item.packageName } && guardAllCpu) {
                guardAllCpu = false
                app.preferences.setGuardAllCpuAppsEnabled(false)
            }
        } else {
            OemChargeSettingsHelper.enforceBlockedPackagesGuard(
                context = context,
                blockedPackages = setOf(item.packageName),
                rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
            )
        }
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    val onToggleAllBattery: (Boolean) -> Unit = { checked ->
        guardAllBattery = checked
        app.preferences.setGuardAllBatteryAppsEnabled(checked)
        val pkgs = batteryApps.map { it.packageName }.toSet()
        if (checked) {
            app.preferences.addBlockedPackages(pkgs)
            OemChargeSettingsHelper.enforceBlockedPackagesGuard(
                context = context,
                blockedPackages = pkgs,
                rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
            )
        } else {
            app.preferences.removeBlockedPackages(pkgs)
        }
        blockedSet = app.preferences.getBlockedPackages()
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    val onToggleAllCpu: (Boolean) -> Unit = { checked ->
        guardAllCpu = checked
        app.preferences.setGuardAllCpuAppsEnabled(checked)
        val pkgs = cpuTempApps.map { it.packageName }.toSet()
        if (checked) {
            app.preferences.addBlockedPackages(pkgs)
            OemChargeSettingsHelper.enforceBlockedPackagesGuard(
                context = context,
                blockedPackages = pkgs,
                rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
            )
        } else {
            app.preferences.removeBlockedPackages(pkgs)
        }
        blockedSet = app.preferences.getBlockedPackages()
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    val batteryPct = snapshot?.batteryLevelPct ?: 0
    val tempC = snapshot?.temperatureC ?: 30f
    val hotspotActive = snapshot?.isHotspotActive == true
    val isCharging = snapshot?.let { it.plugged != PluggedSource.UNPLUGGED && it.currentMa >= 0 } == true
    val activeModeLabel = when (activeTile) {
        TileSelection.OFF -> "OFF"
        TileSelection.AUTO -> "AUTOMATIC"
        TileSelection.PRFO -> "PERFORMANCE"
        TileSelection.ULTRA -> "ULTRA SAFE"
        TileSelection.INTEL -> "INTELLIGENT"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Header Card with Shield Crest & Circular SoC Gauge
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF064E3B), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (activeTile == TileSelection.OFF) {
                                R.drawable.ic_tile_voltguard_off
                            } else {
                                R.drawable.ic_tile_voltguard
                            }
                        ),
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "VoltGuard Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "QS: ${activeTile.name}  ·  $activeModeLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF34D399)
                    )
                }

                // Circular State-of-Charge Gauge
                Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(
                            color = Color(0xFF1E293B),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = stroke
                        )
                        drawArc(
                            color = if (activeTile == TileSelection.PRFO) Color(0xFFF59E0B) else Color(0xFF10B981),
                            startAngle = -90f,
                            sweepAngle = (batteryPct / 100f) * 360f,
                            useCenter = false,
                            style = stroke
                        )
                    }
                    Text(
                        text = if (snapshot != null) "$batteryPct%" else "--%",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                }
            }
        }

        // 2x2 Power Mode Matrix + Logical Scenario Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileButton(
                        title = "Automatic",
                        subtitle = "Tile: Auto",
                        selected = activeTile != TileSelection.OFF && selectedProfile == HomeProfileMode.AUTOMATIC,
                        modifier = Modifier.weight(1f)
                    ) { onSelectProfile(HomeProfileMode.AUTOMATIC) }

                    ProfileButton(
                        title = "Performance",
                        subtitle = "Tile: Prfo",
                        selected = activeTile != TileSelection.OFF && selectedProfile == HomeProfileMode.PERFORMANCE,
                        modifier = Modifier.weight(1f)
                    ) { onSelectProfile(HomeProfileMode.PERFORMANCE) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileButton(
                        title = "Ultra Safe",
                        subtitle = "Tile: Ultra",
                        selected = activeTile != TileSelection.OFF && selectedProfile == HomeProfileMode.ULTRA_SAFE,
                        modifier = Modifier.weight(1f)
                    ) { onSelectProfile(HomeProfileMode.ULTRA_SAFE) }

                    ProfileButton(
                        title = "Intelligent",
                        subtitle = "Tile: Intel",
                        selected = activeTile != TileSelection.OFF && selectedProfile == HomeProfileMode.INTELLIGENT,
                        modifier = Modifier.weight(1f)
                    ) { onSelectProfile(HomeProfileMode.INTELLIGENT) }
                }

                // Active Scenario Explanation Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Text(
                        text = PowerDecisionEngine.scenarioSummary(activeTile, hotspotActive, tempC, isCharging),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }

                // Spacious Stacked Live Battery Life Card (zero horizontal collision, EMA-smoothed mA)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF182235),
                    border = BorderStroke(1.dp, Color(0xFF23324D))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Battery Life (EMA Current)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = if (effectiveCurrentMa > 0) "+$effectiveCurrentMa mA" else "$effectiveCurrentMa mA",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                        Text(
                            text = runtimeHeadline,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = runtimeDetail,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF34D399),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (snapshot?.let { it.voltageMv in 1..3480 && it.batteryLevelPct >= 25 } == true) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF4C0519),
                        border = BorderStroke(1.dp, Color(0xFFF43F5E))
                    ) {
                        Text(
                            text = "Voltage Sag Warning (${snapshot?.voltageMv} mV at ${snapshot?.batteryLevelPct}%): Sudden drop to 1% reflects worn cell impedance — peak load reduced.",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFE4E6)
                        )
                    }
                }
            }
        }

        // Clean 2-Column Ultra Safe Telemetry Card (no vertical letter-by-letter squeeze)
        if (activeTile == TileSelection.ULTRA) {
            val wearAvoided = banditMetrics?.wearAvoidedPct ?: 18.4f
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF062C22),
                border = BorderStroke(1.dp, Color(0xFF059669))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ultra Safe Protections",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFECFDF5)
                        )
                        Text(
                            text = String.format(Locale.US, "Wear Avoided %.1f%%", wearAvoided),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UltraSafeTile("Wi-Fi Scan", "Throttled", Modifier.weight(1f))
                        UltraSafeTile("Screen Timeout", "Shortened", Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UltraSafeTile("Battery Saver", "Max Guard", Modifier.weight(1f))
                        UltraSafeTile("Charging Mode", "Fast 100%", Modifier.weight(1f))
                    }
                }
            }
        }

        // Zero-Dimming 8s-Untouched Sleep/Lock + System/BG Net Cut + 4-Tier Non-Root QS All-Off Shield Card
        val hasAdmin = OemChargeSettingsHelper.hasDeviceAdmin(context)
        val hasSecureQs = OemChargeSettingsHelper.hasWriteSecureSettings(context)
        val hasShizuku = QuickSettingsExecutor.hasShizukuPermission()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, if (inactivityShieldOn) Color(0xFF059669) else Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = inactivityShieldOn,
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981)),
                        onCheckedChange = { checked ->
                            inactivityShieldOn = checked
                            app.preferences.setInactivityShieldEnabled(checked)
                            if (checked) {
                                OemChargeSettingsHelper.ensureShieldPermissions(context)
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, BatteryMonitorService::class.java)
                                )
                            }
                            InactivityShieldController.syncWithPreference(context)
                            scope.launch {
                                BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
                            }
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inactivity Lifespan Shield (8s Untouched Protocol)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF8FAFC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (hasShizuku) "Non-Root Engine: Shizuku Ready · Hotspot & Rotation Untouched"
                            else "Non-Root Engine: ADB / SDK Fallback · Hotspot & Rotation Untouched",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF34D399),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "• No Dimming: Display brightness is never reduced automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "• 8s Untouched -> Sleep/Lock & Net Cut: If untouched for 8s, locks screen, cuts background/system app net drain, and turns OFF active QS toggles (Wi-Fi/BT before Location).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "• Strict Exclusion: NEVER reads, writes, or touches Wi-Fi Hotspot / tethering or Rotation Lock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            val netMsg = QuickSettingsExecutor.cutBackgroundAppNetwork(context)
                            val results = QuickSettingsExecutor.executeAllOff(context)
                            val doneCount = results.count { it.state == ItemExecState.DONE }
                            Toast.makeText(
                                context,
                                "Turn All Off: $doneCount OFF · $netMsg (Hotspot & Rotation untouched)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF022C22))
                ) {
                    Text("Turn All Off Now (Keep Hotspot & Rotation)", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (needsMiuiSecSetting) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF4C0519),
                        border = BorderStroke(1.dp, Color(0xFFF43F5E))
                    ) {
                        Text(
                            text = "MIUI Action Needed: Enable Developer Options -> USB debugging (Security settings) OR authorize Shizuku so MIUI permits writing system settings.",
                            modifier = Modifier.padding(9.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFE4E6)
                        )
                    }
                }

                if (!hasShizuku || !hasAdmin || !hasSecureQs) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasShizuku && QuickSettingsExecutor.isShizukuRunning()) {
                            OutlinedButton(
                                onClick = { QuickSettingsExecutor.requestShizukuPermission(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Grant Shizuku", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else if (!hasAdmin) {
                            OutlinedButton(
                                onClick = { OemChargeSettingsHelper.ensureShieldPermissions(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Enable 8s Lock Admin", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!hasSecureQs) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString("adb shell pm grant dev.voltguard.battery android.permission.WRITE_SECURE_SETTINGS"))
                                    Toast.makeText(context, "Copied ADB WRITE_SECURE_SETTINGS grant", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Copy ADB Grant", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                if (lastExecResults.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lastExecResults.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { res ->
                                    val badge = when (res.state) {
                                        ItemExecState.DONE -> "✔ ${res.backend.name.take(4)}"
                                        ItemExecState.SKIPPED_ALREADY_OFF -> "Skip OFF"
                                        ItemExecState.NEEDS_PERMISSION -> "⚠ Perm"
                                        ItemExecState.UNSUPPORTED -> "Open Panel"
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = res.state == ItemExecState.NEEDS_PERMISSION || res.state == ItemExecState.UNSUPPORTED) {
                                                QuickSettingsExecutor.openFallbackPanelForItem(context, res.key)
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF0F172A),
                                        border = BorderStroke(
                                            1.dp,
                                            if (res.state == ItemExecState.DONE) Color(0xFF059669)
                                            else if (res.state == ItemExecState.NEEDS_PERMISSION) Color(0xFFF59E0B)
                                            else Color(0xFF1E293B)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = res.key.title,
                                                fontSize = 10.sp,
                                                color = Color(0xFFE2E8F0),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = badge,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (res.state == ItemExecState.DONE) Color(0xFF34D399)
                                                else if (res.state == ItemExecState.NEEDS_PERMISSION) Color(0xFFFBBF24)
                                                else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0F172A)
                ) {
                    Text(
                        text = shieldStatusText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }

        // 3x2 Telemetry Grid — Current agrees 100% with Live Battery Life above
        val signedCurrentLabel = if (effectiveCurrentMa > 0) "+$effectiveCurrentMa mA" else "$effectiveCurrentMa mA"
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Battery", if (snapshot != null) "$batteryPct%" else "--", Modifier.weight(1f))
            MetricCard("Voltage", snapshot?.let { "${it.voltageMv} mV" } ?: "--", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Temperature", snapshot?.let { String.format(Locale.US, "%.1f °C", it.temperatureC) } ?: "--", Modifier.weight(1f))
            MetricCard("Current", if (snapshot != null) signedCurrentLabel else "--", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Est. Cycles", String.format(Locale.US, "%.2f", cycles), Modifier.weight(1f))
            MetricCard("Display", "${snapshot?.refreshRateHz?.toInt() ?: 60} Hz", Modifier.weight(1f))
        }

        // 2 Side-by-Side Heavy App Columns with Fixed "All" + Individual Persistent Force-Stop Guard Checkboxes
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111827),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Top Battery Apps",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    GuardAllRow(
                        checked = guardAllBattery,
                        accent = Color(0xFFF43F5E),
                        onToggle = { onToggleAllBattery(!guardAllBattery) }
                    )
                    if (batteryApps.isEmpty()) {
                        Text(
                            text = if (snapshot?.usageAccessGranted == false) "Grant Usage Access" else "No active drains",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                        if (snapshot?.usageAccessGranted == false) {
                            OutlinedButton(onClick = { OemChargeSettingsHelper.openUsageAccessSettings(context) }) {
                                Text("Enable")
                            }
                        }
                    } else {
                        batteryApps.forEach { item ->
                            val isChecked = blockedSet.contains(item.packageName) || item.isBlocked
                            HeavyAppRow(
                                title = item.appLabel,
                                stat = if (isChecked) "Force-Stop Guarded" else "-${item.estimatedMahPerHr} mAh · ${String.format(Locale.US, "%.1f", item.batteryPctPerHr)}%/h",
                                checked = isChecked,
                                onToggleCheck = { onToggleBlockedApp(item) },
                                onClick = { onTapHeavyApp(item) }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111827),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Top CPU / Temp",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    GuardAllRow(
                        checked = guardAllCpu,
                        accent = Color(0xFFF59E0B),
                        onToggle = { onToggleAllCpu(!guardAllCpu) }
                    )
                    if (cpuTempApps.isEmpty()) {
                        Text(
                            text = if (snapshot?.usageAccessGranted == false) "Grant Usage Access" else "CPU & temp idle",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        cpuTempApps.forEach { item ->
                            val isChecked = blockedSet.contains(item.packageName) || item.isBlocked
                            HeavyAppRow(
                                title = item.appLabel,
                                stat = if (isChecked) "Force-Stop Guarded" else "${item.cpuLoadPct}% · +${String.format(Locale.US, "%.1f", item.tempDeltaC)}°C",
                                checked = isChecked,
                                onToggleCheck = { onToggleBlockedApp(item) },
                                onClick = { onTapHeavyApp(item) }
                            )
                        }
                    }
                }
            }
        }

        when (val nudge = decision?.chargeNudge) {
            is ChargeNudge.FastChargeBoost -> {
                NudgeCard(
                    title = "Fast Charge Assist Active (+${nudge.chargeCurrentMa} mA)",
                    body = "Charging is never slowed down or capped — background drain trimmed & temperature watched (${String.format(Locale.US, "%.1f", nudge.tempC)}°C) so charging finishes rapidly to 100%."
                )
            }
            is ChargeNudge.GamingBypassPosture -> {
                NudgeCard(
                    title = "Gaming Bypass Posture (${String.format(Locale.US, "%.1f", nudge.tempC)}°C)",
                    body = "Plugged in during foreground workload — frame pacing preserved with zero charge interference."
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun UltraSafeTile(title: String, stateLabel: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0A3B2D),
        border = BorderStroke(1.dp, Color(0xFF10B981))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFECFDF5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6EE7B7),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GuardAllRow(
    checked: Boolean,
    accent: Color,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(10.dp),
        color = if (checked) Color(0xFF2B121B) else Color(0xFF0F172A),
        border = BorderStroke(1.dp, if (checked) accent else Color(0xFF23324D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = accent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "All",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    maxLines = 1
                )
                Text(
                    text = if (checked) "All Force-Stopped" else "Select & hold all",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (checked) Color(0xFFFDA4AF) else Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProfileButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFF064E3B) else Color(0xFF182235),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) Color(0xFF10B981) else Color(0xFF23324D)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) Color(0xFFECFDF5) else Color(0xFFE2E8F0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (selected) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HeavyAppRow(
    title: String,
    stat: String,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (checked) Color(0xFF3B0D19) else Color(0xFF182235),
        border = BorderStroke(1.dp, if (checked) Color(0xFFF43F5E) else Color(0xFF23324D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggleCheck() },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFF43F5E))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFF8FAFC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stat,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (checked) Color(0xFFFDA4AF) else Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF8FAFC)
            )
        }
    }
}

@Composable
private fun NudgeCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF182235),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
            if (actionLabel != null && onAction != null) {
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
}
