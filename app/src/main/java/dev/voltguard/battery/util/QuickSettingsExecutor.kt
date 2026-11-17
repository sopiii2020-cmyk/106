package dev.voltguard.battery.util

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaRouter
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.voltguard.battery.IShizukuShellService
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.BackendCapabilities
import dev.voltguard.battery.engine.BackendSelector
import dev.voltguard.battery.engine.CommandBlocklist
import dev.voltguard.battery.engine.ExecutorBackend
import dev.voltguard.battery.engine.ItemExecState
import dev.voltguard.battery.engine.QsItemExecutionResult
import dev.voltguard.battery.engine.QsTargetKey
import dev.voltguard.battery.service.ShizukuShellUserService
import dev.voltguard.battery.service.VoltGuardAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

object QuickSettingsExecutor {

    private const val SHIZUKU_REQ_CODE = 4201
    private val activeTorchCameraIds = mutableSetOf<String>()
    private var torchCallbackRegistered = false

    @Volatile private var shellService: IShizukuShellService? = null
    @Volatile private var shizukuBinding = false
    @Volatile private var restrictBgEnabledByUs = false

    private val _lastExecutionResults = MutableStateFlow<List<QsItemExecutionResult>>(emptyList())
    val lastExecutionResults: StateFlow<List<QsItemExecutionResult>> = _lastExecutionResults.asStateFlow()

    private val _needsMiuiSecuritySetting = MutableStateFlow(false)
    val needsMiuiSecuritySetting: StateFlow<Boolean> = _needsMiuiSecuritySetting.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShizukuShellService.Stub.asInterface(service)
            shizukuBinding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            shizukuBinding = false
        }
    }

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShizukuPermission(): Boolean = isShizukuRunning() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestShizukuPermission(context: Context) {
        if (!isShizukuRunning()) return
        if (!hasShizukuPermission()) {
            runCatching { Shizuku.requestPermission(SHIZUKU_REQ_CODE) }
        } else {
            ensureShizukuBound(context)
        }
    }

    fun ensureShizukuBound(context: Context) {
        if (!hasShizukuPermission() || shellService != null || shizukuBinding) return
        shizukuBinding = true
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("vg_shell")
            .debuggable(false)
            .version(1)
        runCatching { Shizuku.bindUserService(args, serviceConnection) }
            .onFailure { shizukuBinding = false }
    }

    fun registerTorchTracker(context: Context) {
        if (torchCallbackRegistered) return
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        torchCallbackRegistered = true
        runCatching {
            cm.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (enabled) activeTorchCameraIds.add(cameraId) else activeTorchCameraIds.remove(cameraId)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    fun inspectCapabilities(context: Context): BackendCapabilities {
        val app = context.applicationContext as VoltGuardApp
        return BackendCapabilities(
            shizukuReady = hasShizukuPermission(),
            hasWriteSecureSettings = OemChargeSettingsHelper.hasWriteSecureSettings(context),
            hasWriteSettings = Settings.System.canWrite(context),
            hasNotificationPolicyAccess = OemChargeSettingsHelper.hasNotificationPolicyAccess(context),
            hasBluetoothPermission = OemChargeSettingsHelper.hasBluetoothConnect(context),
            androidSdkInt = Build.VERSION.SDK_INT,
            accessibilityEnabled = app.preferences.isAccessibilityAutoOffEnabled() &&
                VoltGuardAccessibilityService.isRunning()
        )
    }

    fun readCurrentStateOn(context: Context, key: QsTargetKey): Boolean {
        val cr = context.contentResolver
        return when (key) {
            QsTargetKey.WIFI -> context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            QsTargetKey.BLUETOOTH -> runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(false)
            QsTargetKey.LOCATION -> runCatching {
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
            }.getOrDefault(false)
            QsTargetKey.AIRPLANE -> runCatching {
                Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }.getOrDefault(false)
            QsTargetKey.DND -> runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            }.getOrDefault(false)
            QsTargetKey.RINGER_MUTE -> runCatching {
                val am = context.getSystemService(AudioManager::class.java)
                am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL
            }.getOrDefault(false)
            QsTargetKey.FLASHLIGHT -> activeTorchCameraIds.isNotEmpty()
            QsTargetKey.NFC -> runCatching {
                NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
            }.getOrDefault(false)
            QsTargetKey.BATTERY_SAVER -> context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            QsTargetKey.DARK_MODE -> {
                val mask = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            QsTargetKey.AUTO_BRIGHTNESS -> runCatching {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrDefault(false)
            QsTargetKey.AUTO_SYNC -> runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(false)
            QsTargetKey.SCREEN_CAST -> runCatching {
                val mr = context.getSystemService(MediaRouter::class.java)
                val route = mr?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
                route != null && route != mr.defaultRoute
            }.getOrDefault(false)
            QsTargetKey.MOBILE_DATA -> runCatching {
                Settings.Global.getInt(cr, "mobile_data", 1) == 1
            }.getOrDefault(false)
        }
    }

    suspend fun executeAllOff(context: Context): List<QsItemExecutionResult> {
        val ctx = context.applicationContext
        val app = ctx as VoltGuardApp
        registerTorchTracker(ctx)
        ensureShizukuBound(ctx)
        val caps = inspectCapabilities(ctx)
        val results = mutableListOf<QsItemExecutionResult>()

        for (key in BackendSelector.ORDERED_TARGETS) {
            if (!app.preferences.isQsTargetEnabled(key)) continue
            val wasOn = readCurrentStateOn(ctx, key)
            val backend = BackendSelector.selectBestBackend(key, caps)
            if (!wasOn) {
                results.add(QsItemExecutionResult(key, false, true, backend, ItemExecState.SKIPPED_ALREADY_OFF, "Already OFF"))
                continue
            }

            val attemptedBackend = turnOffSingleItem(ctx, key, caps)
            delay(120L)
            val stillOn = readCurrentStateOn(ctx, key)
            val state = when {
                !stillOn -> ItemExecState.DONE
                attemptedBackend == ExecutorBackend.UNSUPPORTED -> ItemExecState.UNSUPPORTED
                else -> ItemExecState.NEEDS_PERMISSION
            }
            val msg = when (state) {
                ItemExecState.DONE -> "OFF via ${attemptedBackend.name}"
                ItemExecState.SKIPPED_ALREADY_OFF -> "Already OFF"
                ItemExecState.NEEDS_PERMISSION -> "Check Shizuku / ADB / Settings permission"
                ItemExecState.UNSUPPORTED -> "Open Settings panel"
            }
            results.add(QsItemExecutionResult(key, true, !stillOn, attemptedBackend, state, msg))
        }
        _lastExecutionResults.value = results
        return results
    }

    private fun runShizukuCmd(vararg candidates: String): Boolean {
        val svc = shellService ?: return false
        for (cmd in candidates) {
            if (!CommandBlocklist.isCommandAllowed(cmd)) continue
            val res = runCatching { svc.runCommand(cmd) }.getOrNull() ?: continue
            if (res.startsWith("EXIT=0")) return true
        }
        return false
    }

    private fun writeSecureIntSafe(cr: ContentResolver, key: String, value: Int, secureTable: Boolean = false): Boolean {
        CommandBlocklist.requireAllowed(key)
        return try {
            if (secureTable) Settings.Secure.putInt(cr, key, value)
            else Settings.Global.putInt(cr, key, value)
        } catch (se: SecurityException) {
            _needsMiuiSecuritySetting.value = true
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun turnOffSingleItem(context: Context, key: QsTargetKey, caps: BackendCapabilities): ExecutorBackend {
        val cr = context.contentResolver
        when (key) {
            QsTargetKey.WIFI -> {
                if (caps.shizukuReady && runShizukuCmd("svc wifi disable", "cmd wifi set-wifi-enabled disabled")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.accessibilityEnabled && VoltGuardAccessibilityService.triggerToggleOff(key)) {
                    return ExecutorBackend.ACCESSIBILITY
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.BLUETOOTH -> {
                if (caps.shizukuReady && runShizukuCmd("svc bluetooth disable", "cmd bluetooth_manager disable")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.hasBluetoothPermission && caps.androidSdkInt <= 32) {
                    val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
                    @Suppress("DEPRECATION")
                    if (runCatching { bt?.disable() == true }.getOrDefault(false)) return ExecutorBackend.PLAIN_SDK
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, Settings.Global.BLUETOOTH_ON, 0)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                if (caps.accessibilityEnabled && VoltGuardAccessibilityService.triggerToggleOff(key)) {
                    return ExecutorBackend.ACCESSIBILITY
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.LOCATION -> {
                if (caps.shizukuReady && runShizukuCmd("cmd location set-location-enabled false", "settings put secure location_mode 0")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF, secureTable = true)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                if (caps.accessibilityEnabled && VoltGuardAccessibilityService.triggerToggleOff(key)) {
                    return ExecutorBackend.ACCESSIBILITY
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.AIRPLANE -> {
                // Force OFF (never ON — enabling airplane mode kills hotspot)
                if (caps.shizukuReady && runShizukuCmd(
                        "cmd connectivity airplane-mode disable",
                        "settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                    )
                ) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, Settings.Global.AIRPLANE_MODE_ON, 0)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.DND -> {
                if (caps.hasNotificationPolicyAccess) {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    if (nm != null && runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); true }.getOrDefault(false)) {
                        return ExecutorBackend.PLAIN_SDK
                    }
                }
                if (caps.shizukuReady && runShizukuCmd("cmd notification set_dnd off")) {
                    return ExecutorBackend.SHIZUKU
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.RINGER_MUTE -> {
                if (caps.hasNotificationPolicyAccess) {
                    val am = context.getSystemService(AudioManager::class.java)
                    if (am != null && runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; true }.getOrDefault(false)) {
                        return ExecutorBackend.PLAIN_SDK
                    }
                }
                if (caps.shizukuReady && runShizukuCmd("cmd audio set-ringer-mode NORMAL")) {
                    return ExecutorBackend.SHIZUKU
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.FLASHLIGHT -> {
                val cm = context.getSystemService(CameraManager::class.java) ?: return ExecutorBackend.UNSUPPORTED
                var anyDone = false
                for (id in cm.cameraIdList) {
                    val hasFlash = runCatching {
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }.getOrDefault(false)
                    if (hasFlash) {
                        runCatching { cm.setTorchMode(id, false); anyDone = true }
                    }
                }
                return if (anyDone) ExecutorBackend.PLAIN_SDK else ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.NFC -> {
                if (caps.shizukuReady && runShizukuCmd("svc nfc disable")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, "nfc_on", 0, secureTable = true)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                if (caps.accessibilityEnabled && VoltGuardAccessibilityService.triggerToggleOff(key)) {
                    return ExecutorBackend.ACCESSIBILITY
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.BATTERY_SAVER -> {
                if (caps.shizukuReady && runShizukuCmd("cmd power set-mode 0", "settings put global low_power 0")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, "low_power", 0)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.DARK_MODE -> {
                if (caps.shizukuReady && runShizukuCmd("cmd uimode night no")) {
                    return ExecutorBackend.SHIZUKU
                }
                val umm = context.getSystemService(UiModeManager::class.java)
                if (umm != null && runCatching { umm.nightMode = UiModeManager.MODE_NIGHT_NO; true }.getOrDefault(false)) {
                    return ExecutorBackend.PLAIN_SDK
                }
                if (caps.hasWriteSecureSettings && writeSecureIntSafe(cr, "ui_night_mode", 1, secureTable = true)) {
                    return ExecutorBackend.WRITE_SECURE_SETTINGS
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.AUTO_BRIGHTNESS -> {
                // Only disables AUTO mode -> MANUAL; NEVER touches brightness level or screen rotation!
                if (caps.hasWriteSettings) {
                    val ok = runCatching {
                        Settings.System.putInt(
                            cr,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                    }.getOrDefault(false)
                    if (ok) return ExecutorBackend.PLAIN_SDK
                }
                if (caps.shizukuReady && runShizukuCmd("settings put system screen_brightness_mode 0")) {
                    return ExecutorBackend.SHIZUKU
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.AUTO_SYNC -> {
                return if (runCatching { ContentResolver.setMasterSyncAutomatically(false); true }.getOrDefault(false)) {
                    ExecutorBackend.PLAIN_SDK
                } else {
                    ExecutorBackend.UNSUPPORTED
                }
            }
            QsTargetKey.SCREEN_CAST -> {
                val mr = context.getSystemService(MediaRouter::class.java)
                val disconnected = runCatching {
                    mr?.defaultRoute?.let { mr.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, it) }
                    true
                }.getOrDefault(false)
                if (disconnected) return ExecutorBackend.PLAIN_SDK
                if (caps.shizukuReady && runShizukuCmd("settings put global wifi_display_on 0")) {
                    return ExecutorBackend.SHIZUKU
                }
                return ExecutorBackend.UNSUPPORTED
            }
            QsTargetKey.MOBILE_DATA -> {
                // Only runs when explicitly enabled by user in Settings (default OFF)
                if (caps.shizukuReady && runShizukuCmd("svc data disable")) {
                    return ExecutorBackend.SHIZUKU
                }
                if (caps.accessibilityEnabled && VoltGuardAccessibilityService.triggerToggleOff(key)) {
                    return ExecutorBackend.ACCESSIBILITY
                }
                return ExecutorBackend.UNSUPPORTED
            }
        }
    }

    fun cutBackgroundAppNetwork(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val pm = context.packageManager
        var trimmed = 0
        runCatching {
            val pkgs = pm.getInstalledApplications(0)
            for (info in pkgs) {
                if (info.packageName != context.packageName &&
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                ) {
                    am?.killBackgroundProcesses(info.packageName)
                    trimmed++
                }
            }
        }
        if (hasShizukuPermission()) {
            ensureShizukuBound(context)
            if (runShizukuCmd("cmd netpolicy set restrict-background true")) {
                restrictBgEnabledByUs = true
                return "Shizuku Data-Saver ON + $trimmed BG apps trimmed"
            }
        }
        return "$trimmed BG apps trimmed"
    }

    fun restoreBackgroundNetworkAfterUnlock(context: Context) {
        if (restrictBgEnabledByUs && hasShizukuPermission()) {
            ensureShizukuBound(context)
            runShizukuCmd("cmd netpolicy set restrict-background false")
            restrictBgEnabledByUs = false
        }
    }

    fun lockViaShizukuIfAvailable(): Boolean =
        hasShizukuPermission() && runShizukuCmd("input keyevent 26")

    fun openFallbackPanelForItem(context: Context, key: QsTargetKey) {
        val action = when (key) {
            QsTargetKey.WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            QsTargetKey.MOBILE_DATA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            QsTargetKey.NFC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            QsTargetKey.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            QsTargetKey.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
