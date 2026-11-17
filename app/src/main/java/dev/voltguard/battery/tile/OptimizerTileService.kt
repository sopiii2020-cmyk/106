package dev.voltguard.battery.tile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.voltguard.battery.R
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.PowerDecisionEngine
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OptimizerTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        syncTileUi()
        tileScope.launch {
            BatteryMonitorService.collectAndEvaluateNow(applicationContext, postNotification = false)
            syncTileUi()
        }
    }

    override fun onClick() {
        super.onClick()
        val app = application as VoltGuardApp
        val next = PowerDecisionEngine.cycleTile(app.preferences.getTileSelection())
        app.preferences.setTileSelection(next)

        if (app.preferences.isPersistentNotificationEnabled() ||
            next == TileSelection.INTEL ||
            app.preferences.isInactivityShieldEnabled()
        ) {
            val refreshIntent = Intent(this, BatteryMonitorService::class.java).apply {
                action = BatteryMonitorService.ACTION_REFRESH_NOW
            }
            ContextCompat.startForegroundService(this, refreshIntent)
        } else {
            stopService(Intent(this, BatteryMonitorService::class.java))
            tileScope.launch {
                BatteryMonitorService.collectAndEvaluateNow(applicationContext, postNotification = false)
                syncTileUi()
            }
        }

        syncTileUi()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun syncTileUi() {
        val tile = qsTile ?: return
        val app = application as VoltGuardApp
        val selection = app.preferences.getTileSelection()
        val snap = BatteryMonitorService.latestSnapshot.value
        val nightMask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDarkSystemShade = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val resIcon = Icon.createWithResource(
            this,
            if (selection == TileSelection.OFF) R.drawable.ic_tile_voltguard_off else R.drawable.ic_tile_voltguard
        )
        if (selection == TileSelection.OFF) {
            // Match system inactive tile foreground (dark gray in Light QS like NFC/GPS/Edit, muted slate in Dark QS)
            resIcon.setTint(if (isDarkSystemShade) 0xFFCBD5E1.toInt() else 0xFF424242.toInt())
        } else {
            resIcon.setTint(android.graphics.Color.WHITE)
        }
        tile.icon = resIcon
        when (selection) {
            TileSelection.OFF -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "VoltGuard: Off"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Stock posture"
                }
            }
            TileSelection.AUTO -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Auto"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (snap != null) "${snap.batteryLevelPct}% · ${snap.temperatureC.toInt()}°C" else "Automatic"
                }
            }
            TileSelection.PRFO -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Prfo"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Performance"
                }
            }
            TileSelection.ULTRA -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Ultra"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Ultra Safe"
                }
            }
            TileSelection.INTEL -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Intel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Intelligent"
                }
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileSync(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, OptimizerTileService::class.java)
            )
        }
    }
}
