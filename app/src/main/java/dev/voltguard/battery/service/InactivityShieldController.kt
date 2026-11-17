package dev.voltguard.battery.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.util.QuickSettingsExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

object InactivityShieldController : SensorEventListener {

    private const val UNTOUCHED_LOCK_TIMEOUT_MS = 8_000L

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sensorManager: SensorManager? = null
    private var appContext: Context? = null
    private var isWatching = false
    @Volatile private var lastInteractionMs = System.currentTimeMillis()
    private var lastMag = 9.81f
    @Volatile private var lockedForCurrentIdle = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _shieldStatusText = MutableStateFlow("Ready — 8s untouched -> Sleep/Lock, System Net Cut & Non-Root QS All-Off (no dimming)")
    val shieldStatusText: StateFlow<String> = _shieldStatusText.asStateFlow()

    private val tickRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val app = ctx.applicationContext as VoltGuardApp
            if (!app.preferences.isInactivityShieldEnabled() || !isWatching) return

            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive == true) {
                val idleMs = System.currentTimeMillis() - lastInteractionMs
                if (idleMs >= UNTOUCHED_LOCK_TIMEOUT_MS && !lockedForCurrentIdle) {
                    lockedForCurrentIdle = true
                    triggerLockNetCutAndQsOff(ctx, shouldLockNow = true, reason = "8s Untouched Sleep")
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun reportUserInteraction() {
        lastInteractionMs = System.currentTimeMillis()
        lockedForCurrentIdle = false
    }

    fun syncWithPreference(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val enabled = (ctx as VoltGuardApp).preferences.isInactivityShieldEnabled()
        if (enabled && !isWatching) {
            val sm = ctx.getSystemService(SensorManager::class.java)
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager = sm
            lastInteractionMs = System.currentTimeMillis()
            lockedForCurrentIdle = false
            if (accel != null) {
                sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            }
            isWatching = true
            mainHandler.removeCallbacks(tickRunnable)
            mainHandler.postDelayed(tickRunnable, 1000L)
            _shieldStatusText.value = "Shield ACTIVE — 8s untouched locks screen, cuts BG net & turns active QS OFF"
        } else if (!enabled && isWatching) {
            stopWatching()
            _shieldStatusText.value = "Inactivity Shield disabled"
        }
    }

    fun stopWatching() {
        isWatching = false
        mainHandler.removeCallbacks(tickRunnable)
        sensorManager?.unregisterListener(this)
    }

    fun onScreenOff(context: Context) {
        val ctx = context.applicationContext
        val app = ctx as VoltGuardApp
        if (app.preferences.isInactivityShieldEnabled() && !lockedForCurrentIdle) {
            lockedForCurrentIdle = true
            triggerLockNetCutAndQsOff(ctx, shouldLockNow = false, reason = "Sleep / Lock Screen")
        } else {
            lockedForCurrentIdle = true
        }
    }

    fun onScreenOn(context: Context) {
        lastInteractionMs = System.currentTimeMillis()
        lockedForCurrentIdle = false
    }

    fun onUserUnlocked(context: Context) {
        val ctx = context.applicationContext
        lastInteractionMs = System.currentTimeMillis()
        lockedForCurrentIdle = false
        controllerScope.launch {
            QuickSettingsExecutor.restoreBackgroundNetworkAfterUnlock(ctx)
            _shieldStatusText.value = "Unlocked — foreground network resumed (QS remains OFF per policy)"
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val vals = event.values ?: return
        if (vals.size < 3) return
        val mag = sqrt(vals[0] * vals[0] + vals[1] * vals[1] + vals[2] * vals[2])
        if (kotlin.math.abs(mag - lastMag) > 0.45f) {
            lastInteractionMs = System.currentTimeMillis()
        }
        lastMag = mag
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun triggerLockNetCutAndQsOff(context: Context, shouldLockNow: Boolean, reason: String) {
        controllerScope.launch {
            val netCutMsg = QuickSettingsExecutor.cutBackgroundAppNetwork(context)
            val results = QuickSettingsExecutor.executeAllOff(context)
            val doneCount = results.count { it.state == dev.voltguard.battery.engine.ItemExecState.DONE }
            _shieldStatusText.value = "$reason — $netCutMsg · $doneCount QS OFF (Hotspot & Rotation untouched)"

            if (shouldLockNow) {
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(context, VoltGuardDeviceAdminReceiver::class.java)
                if (dpm?.isAdminActive(admin) == true) {
                    mainHandler.post { dpm.lockNow() }
                } else {
                    QuickSettingsExecutor.lockViaShizukuIfAvailable()
                }
            }
        }
    }
}
