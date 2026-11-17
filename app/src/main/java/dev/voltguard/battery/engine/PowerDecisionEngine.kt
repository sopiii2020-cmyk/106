package dev.voltguard.battery.engine

import android.os.PowerManager

enum class TileSelection { OFF, AUTO, PRFO, ULTRA, INTEL }
enum class HomeProfileMode { AUTOMATIC, PERFORMANCE, ULTRA_SAFE, INTELLIGENT }
enum class AppliedPowerMode { PERFORMANCE, BALANCED, LONGEVITY }
enum class PluggedSource { UNPLUGGED, AC, USB, WIRELESS }

data class HeavyAppUsage(
    val packageName: String,
    val appLabel: String,
    val estimatedMahPerHr: Int,
    val batteryPctPerHr: Float,
    val cpuLoadPct: Int,
    val tempDeltaC: Float,
    val isBlocked: Boolean = false
)

data class TelemetrySnapshot(
    val timestampMs: Long,
    val batteryLevelPct: Int,
    val voltageMv: Int,
    val temperatureC: Float,
    val tempTrendCPer10Min: Float = 0f,
    val currentMa: Int,
    val previousChargeCurrentMa: Int,
    val plugged: PluggedSource,
    val screenOn: Boolean,
    val refreshRateHz: Float,
    val displayBrightnessPct: Int,
    val hourOfDay: Int,
    val thermalStatus: Int, // PowerManager.THERMAL_STATUS_*
    val thermalHeadroom: Float, // 0f..1.5f via PowerManager.getThermalHeadroom()
    val isPowerSaveMode: Boolean,
    val isDeviceIdleDoze: Boolean,
    val isIgnoringBatteryOpt: Boolean,
    val foregroundPackage: String,
    val isGameOrCameraForeground: Boolean,
    val isNavigationForeground: Boolean = false,
    val isGpsActive: Boolean = false,
    val isBedtimeVideoForeground: Boolean = false,
    val isWifiConnected: Boolean,
    val stayAwakeEnabled: Boolean,
    val usbDebuggingEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val aggressiveness: Int, // 0 (Battery-first) .. 100 (Performance-first)
    val tileSelection: TileSelection,
    val oemNativeChargeLimitSupported: Boolean,
    val isHotspotActive: Boolean = false,
    val isInactivityShieldEnabled: Boolean = false
)

data class WorkConstraintsDecision(
    val requiresBatteryNotLow: Boolean,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val selfThrottleOwnWork: Boolean
)

sealed class ChargeNudge {
    data object None : ChargeNudge()
    data class FastChargeBoost(val chargeCurrentMa: Int, val tempC: Float) : ChargeNudge()
    data class GamingBypassPosture(val packageName: String, val tempC: Float) : ChargeNudge()
    data class UnplugAt80(val levelPct: Int) : ChargeNudge()
    data object OemNativeChargeLimitLink : ChargeNudge()
    data class JeitaThermalSlowdown(val tempC: Float, val currentMa: Int) : ChargeNudge()
    data class PredictiveChargePacing(val hoursUntilUnplug: Float, val predictedUnplugHour: Int) : ChargeNudge()
}

data class DecisionOutput(
    val appliedMode: AppliedPowerMode,
    val statusLine: String,
    val keepRadiosAndGpsFull: Boolean = false,
    val workConstraints: WorkConstraintsDecision,
    val chargeNudge: ChargeNudge,
    val confidencePct: Int = 100,
    val usedSafeFallback: Boolean = false,
    val explainabilityReason: String = statusLine
)

/**
 * Baseline Part 1 rules engine (also acts as the guaranteed Safe Fallback for Part 1B).
 */
object PowerDecisionEngine {

    fun cycleTile(current: TileSelection): TileSelection = when (current) {
        TileSelection.OFF -> TileSelection.AUTO
        TileSelection.AUTO -> TileSelection.PRFO
        TileSelection.PRFO -> TileSelection.ULTRA
        TileSelection.ULTRA -> TileSelection.INTEL
        TileSelection.INTEL -> TileSelection.OFF
    }

    fun scenarioSummary(
        selection: TileSelection,
        hotspotActive: Boolean,
        tempC: Float,
        isCharging: Boolean = false
    ): String {
        val hotspotNote = if (hotspotActive) {
            " Hotspot ON: protected & managing CPU/thermal load (${tempC.toInt()}°C)."
        } else {
            ""
        }
        val chargeNote = if (isCharging) {
            " Charging: Fast Charge Boost active (zero slowdown, BG drain trimmed to reach 100% faster)."
        } else {
            ""
        }
        return when (selection) {
            TileSelection.OFF ->
                "Stock Android power posture — VoltGuard optimizations paused." + hotspotNote + chargeNote
            TileSelection.AUTO ->
                "Automatic (Auto): Smooth 120Hz responsiveness when active; saves sync & wakeups during idle/warm periods." + hotspotNote + chargeNote
            TileSelection.PRFO ->
                "Performance (Prfo): Full CPU headroom & 120Hz frame pacing prioritized; background deferral lifted unless severe heat." + hotspotNote + chargeNote
            TileSelection.ULTRA ->
                "Ultra Safe (Ultra): Max battery lifespan — conservative current draw, cached BG trim, and 60s low-overhead loop." + hotspotNote + chargeNote
            TileSelection.INTEL ->
                "Intelligent (Intel): Persistent AI Fusion (Kalman + HMM + Bandit) stays pinned until Quick Tile is tapped off." + hotspotNote + chargeNote
        }
    }

    fun evaluate(snapshot: TelemetrySnapshot): DecisionOutput {
        val isThermalSevere = snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            snapshot.thermalHeadroom >= 1.0f

        val isCharging = snapshot.plugged != PluggedSource.UNPLUGGED && snapshot.currentMa >= 0
        val isOvernight = snapshot.hourOfDay >= 22 || snapshot.hourOfDay < 6
        val isNavigationContext = snapshot.screenOn && snapshot.isNavigationForeground && snapshot.isGpsActive
        val isLongevityCandidate = !isCharging && !snapshot.screenOn && (
            snapshot.isDeviceIdleDoze ||
                isOvernight ||
                snapshot.aggressiveness <= 35
            )

        val isVoltageSagRisk = snapshot.plugged == PluggedSource.UNPLUGGED &&
            ((snapshot.voltageMv in 1..3480 && snapshot.batteryLevelPct >= 25) || snapshot.voltageMv in 1..3380)

        val (appliedMode, statusLine) = when (snapshot.tileSelection) {
            TileSelection.OFF -> {
                AppliedPowerMode.BALANCED to
                    "VoltGuard OFF via Quick Settings — stock Android power behavior."
            }
            TileSelection.INTEL -> {
                if (isCharging) {
                    AppliedPowerMode.PERFORMANCE to
                        "Intelligent (Intel) Fast Charge Assist — trimming BG drain & keeping device cool for peak charge speed."
                } else {
                    AppliedPowerMode.LONGEVITY to
                        "Intelligent (Intel) AI Fusion locked via Quick Settings — stays pinned until tapped off."
                }
            }
            TileSelection.PRFO -> {
                if (isThermalSevere) {
                    AppliedPowerMode.BALANCED to
                        "Thermal limit reached — self-throttling background work despite Performance (Prfo) tile."
                } else {
                    AppliedPowerMode.PERFORMANCE to
                        "Performance (Prfo) pinned — 120Hz & CPU boosts active, deferral paused."
                }
            }
            TileSelection.ULTRA -> {
                if (isNavigationContext) {
                    AppliedPowerMode.BALANCED to
                        "Navigation + GPS active — keeping radios & location responsive while honoring Ultra Safe."
                } else if (isCharging) {
                    AppliedPowerMode.BALANCED to
                        "Ultra Safe Fast Charge Assist — halting background drain so full charger current reaches the cell."
                } else {
                    AppliedPowerMode.LONGEVITY to
                        "Ultra Safe (Ultra) pinned — maximum battery longevity & sync/refresh conservation."
                }
            }
            TileSelection.AUTO -> when {
                isVoltageSagRisk -> AppliedPowerMode.LONGEVITY to
                    "Voltage sag detected — reducing peak load to prevent abrupt cell cutoff."
                isCharging -> AppliedPowerMode.PERFORMANCE to
                    "Fast Charge Assist active — zero charge throttling; trimming idle wakeups to charge faster to 100%."
                snapshot.isHotspotActive && snapshot.temperatureC >= 37.5f -> AppliedPowerMode.LONGEVITY to
                    "Hotspot active — keeping tethering ON while throttling CPU/temp to normal."
                isThermalSevere -> AppliedPowerMode.LONGEVITY to
                    "Severe thermal status — deferring own work to let SoC and battery cool."
                isNavigationContext -> AppliedPowerMode.PERFORMANCE to
                    "Navigation + GPS session active — keeping radios and location responsive."
                snapshot.screenOn && snapshot.isGameOrCameraForeground && snapshot.aggressiveness >= 25 ->
                    AppliedPowerMode.PERFORMANCE to
                        "Performance mode active — foreground workload protected from throttling."
                isLongevityCandidate -> AppliedPowerMode.LONGEVITY to
                    "Longevity mode active — screen off and idle, own sync deferred."
                snapshot.screenOn && snapshot.aggressiveness >= 75 ->
                    AppliedPowerMode.PERFORMANCE to
                        "Performance mode active — screen on with performance-first slider."
                else -> AppliedPowerMode.BALANCED to
                    "Balanced mode active — monitoring battery, thermal, and usage context."
            }
        }

        val isGameFg = snapshot.screenOn && snapshot.isGameOrCameraForeground
        val selfThrottle = (isThermalSevere || (isCharging && !isGameFg) || appliedMode == AppliedPowerMode.LONGEVITY) && !isNavigationContext
        val constraints = WorkConstraintsDecision(
            requiresBatteryNotLow = true,
            requiresCharging = appliedMode == AppliedPowerMode.LONGEVITY &&
                snapshot.plugged == PluggedSource.UNPLUGGED &&
                snapshot.batteryLevelPct < 40,
            requiresDeviceIdle = selfThrottle,
            selfThrottleOwnWork = selfThrottle
        )

        val chargeNudge = when {
            isCharging && snapshot.screenOn && snapshot.isGameOrCameraForeground ->
                ChargeNudge.GamingBypassPosture(snapshot.foregroundPackage, snapshot.temperatureC)
            isCharging ->
                ChargeNudge.FastChargeBoost(snapshot.currentMa.coerceAtLeast(0), snapshot.temperatureC)
            else -> ChargeNudge.None
        }

        return DecisionOutput(
            appliedMode = appliedMode,
            statusLine = statusLine,
            keepRadiosAndGpsFull = isNavigationContext,
            workConstraints = constraints,
            chargeNudge = chargeNudge
        )
    }
}
