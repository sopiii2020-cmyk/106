package dev.voltguard.battery.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.service.InactivityShieldController
import dev.voltguard.battery.ui.screens.DiagnosticsScreen
import dev.voltguard.battery.ui.screens.HomeScreen
import dev.voltguard.battery.ui.screens.SettingsScreen
import dev.voltguard.battery.ui.screens.TrendsScreen
import dev.voltguard.battery.util.QuickSettingsExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class AppTab { HOME, TRENDS, DIAGNOSTICS, SETTINGS }

private val VoltGuardDarkColors = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color(0xFF022C22),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    background = Color(0xFF0A0F1C),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF182235),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF1E293B),
    error = Color(0xFFF43F5E),
    onError = Color(0xFF4C0519)
)

class MainActivity : ComponentActivity() {
    private var initialTab by mutableStateOf(AppTab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color(0xFF0A0F1C).toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color(0xFF0F172A).toArgb()

        QuickSettingsExecutor.registerTorchTracker(this)
        val app = application as VoltGuardApp
        if (app.preferences.isPersistentNotificationEnabled() ||
            app.preferences.getTileSelection() == TileSelection.INTEL ||
            app.preferences.isInactivityShieldEnabled()
        ) {
            ContextCompat.startForegroundService(this, Intent(this, BatteryMonitorService::class.java))
        } else {
            stopService(Intent(this, BatteryMonitorService::class.java))
        }
        routeIntent(intent)

        setContent {
            MaterialTheme(colorScheme = VoltGuardDarkColors) {
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    while (isActive) {
                        BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
                        delay(15_000L)
                    }
                }
                VoltGuardMainShell(
                    selectedTab = initialTab,
                    onSelectTab = { initialTab = it }
                )
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        InactivityShieldController.reportUserInteraction()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            initialTab = AppTab.HOME
        }
    }
}

@Composable
fun VoltGuardMainShell(selectedTab: AppTab, onSelectTab: (AppTab) -> Unit) {
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color(0xFF10B981),
        selectedTextColor = Color(0xFF10B981),
        indicatorColor = Color(0xFF064E3B),
        unselectedIconColor = Color(0xFF94A3B8),
        unselectedTextColor = Color(0xFF94A3B8)
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.HOME,
                    onClick = { onSelectTab(AppTab.HOME) },
                    icon = { Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null) },
                    label = { Text("Home") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.TRENDS,
                    onClick = { onSelectTab(AppTab.TRENDS) },
                    icon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                    label = { Text("Trends") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.DIAGNOSTICS,
                    onClick = { onSelectTab(AppTab.DIAGNOSTICS) },
                    icon = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null) },
                    label = { Text("Diagnostics") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { onSelectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    label = { Text("Settings") },
                    colors = navColors
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(Modifier.padding(innerPadding))
            AppTab.TRENDS -> TrendsScreen(Modifier.padding(innerPadding))
            AppTab.DIAGNOSTICS -> DiagnosticsScreen(Modifier.padding(innerPadding))
            AppTab.SETTINGS -> SettingsScreen(Modifier.padding(innerPadding))
        }
    }
}
