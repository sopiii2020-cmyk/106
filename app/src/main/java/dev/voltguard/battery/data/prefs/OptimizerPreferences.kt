package dev.voltguard.battery.data.prefs

import android.content.Context
import dev.voltguard.battery.engine.HomeProfileMode
import dev.voltguard.battery.engine.QsTargetKey
import dev.voltguard.battery.engine.TileSelection

class OptimizerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("voltguard_prefs", Context.MODE_PRIVATE)

    fun getTileSelection(): TileSelection {
        val raw = prefs.getString(KEY_TILE_SELECTION, TileSelection.AUTO.name)
        return when (raw) {
            "INTL" -> TileSelection.INTEL
            "LONGEVITY" -> TileSelection.ULTRA
            "PERFORMANCE" -> TileSelection.PRFO
            else -> runCatching { TileSelection.valueOf(raw ?: TileSelection.AUTO.name) }
                .getOrDefault(TileSelection.AUTO)
        }
    }

    fun setTileSelection(mode: TileSelection) {
        val syncedProfile = when (mode) {
            TileSelection.PRFO -> HomeProfileMode.PERFORMANCE
            TileSelection.ULTRA -> HomeProfileMode.ULTRA_SAFE
            TileSelection.INTEL -> HomeProfileMode.INTELLIGENT
            TileSelection.AUTO, TileSelection.OFF -> HomeProfileMode.AUTOMATIC
        }
        prefs.edit()
            .putString(KEY_TILE_SELECTION, mode.name)
            .putString(KEY_HOME_PROFILE, syncedProfile.name)
            .apply()
    }

    fun getHomeProfile(): HomeProfileMode {
        val raw = prefs.getString(KEY_HOME_PROFILE, HomeProfileMode.AUTOMATIC.name)
        return if (raw == "BALANCE") {
            HomeProfileMode.PERFORMANCE
        } else {
            runCatching { HomeProfileMode.valueOf(raw ?: HomeProfileMode.AUTOMATIC.name) }
                .getOrDefault(HomeProfileMode.AUTOMATIC)
        }
    }

    fun setHomeProfile(profile: HomeProfileMode) {
        val (tile, agg) = when (profile) {
            HomeProfileMode.AUTOMATIC -> TileSelection.AUTO to 45
            HomeProfileMode.PERFORMANCE -> TileSelection.PRFO to 92
            HomeProfileMode.ULTRA_SAFE -> TileSelection.ULTRA to 10
            HomeProfileMode.INTELLIGENT -> TileSelection.INTEL to 35
        }
        prefs.edit()
            .putString(KEY_HOME_PROFILE, profile.name)
            .putString(KEY_TILE_SELECTION, tile.name)
            .putInt(KEY_AGGRESSIVENESS, agg)
            .apply()
    }

    fun getAggressiveness(): Int = prefs.getInt(KEY_AGGRESSIVENESS, 45)
    fun setAggressiveness(value: Int) {
        prefs.edit().putInt(KEY_AGGRESSIVENESS, value.coerceIn(0, 100)).apply()
    }

    fun getTypicalUnplugHour(): Int = prefs.getInt(KEY_UNPLUG_HOUR, 7)
    fun getDivergenceCount(): Int = prefs.getInt(KEY_DIVERGENCE, 0)

    fun isNudge80Enabled(): Boolean = prefs.getBoolean(KEY_NUDGE_80, false)
    fun setNudge80Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NUDGE_80, enabled).apply()
    }

    fun isPersistentNotificationEnabled(): Boolean = prefs.getBoolean(KEY_PERSISTENT_NOTIF, false)
    fun setPersistentNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_NOTIF, enabled).apply()
    }

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, true)
    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isRootBridgeOptInEnabled(): Boolean = prefs.getBoolean(KEY_ROOT_OPT_IN, false)
    fun setRootBridgeOptInEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_OPT_IN, enabled).apply()
    }

    fun getBlockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun addBlockedPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        val updated = getBlockedPackages().toMutableSet()
        updated.addAll(packages)
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, updated).apply()
    }

    fun removeBlockedPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        val updated = getBlockedPackages().toMutableSet()
        updated.removeAll(packages)
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, updated).apply()
    }

    fun toggleBlockedPackage(packageName: String): Boolean {
        val updated = getBlockedPackages().toMutableSet()
        val nowBlocked = if (updated.contains(packageName)) {
            updated.remove(packageName)
            false
        } else {
            updated.add(packageName)
            true
        }
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, updated).apply()
        return nowBlocked
    }

    fun isGuardAllBatteryAppsEnabled(): Boolean = prefs.getBoolean(KEY_GUARD_ALL_BATTERY, false)
    fun setGuardAllBatteryAppsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GUARD_ALL_BATTERY, enabled).apply()
    }

    fun isGuardAllCpuAppsEnabled(): Boolean = prefs.getBoolean(KEY_GUARD_ALL_CPU, false)
    fun setGuardAllCpuAppsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GUARD_ALL_CPU, enabled).apply()
    }

    fun isInactivityShieldEnabled(): Boolean = prefs.getBoolean(KEY_INACTIVITY_SHIELD, false)
    fun setInactivityShieldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INACTIVITY_SHIELD, enabled).apply()
    }

    fun isAccessibilityAutoOffEnabled(): Boolean = prefs.getBoolean(KEY_ACCESSIBILITY_AUTO_OFF, false)
    fun setAccessibilityAutoOffEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ACCESSIBILITY_AUTO_OFF, enabled).apply()
    }

    fun isQsTargetEnabled(key: QsTargetKey): Boolean {
        // Mobile Data defaults to false so Wi-Fi Hotspot stays connected with internet; Hotspot & Rotation Lock are never configurable.
        val defaultEnabled = key != QsTargetKey.MOBILE_DATA
        return prefs.getBoolean(KEY_QS_TARGET_PREFIX + key.name, defaultEnabled)
    }

    fun setQsTargetEnabled(key: QsTargetKey, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QS_TARGET_PREFIX + key.name, enabled).apply()
    }

    companion object {
        private const val KEY_TILE_SELECTION = "tile_selection"
        private const val KEY_HOME_PROFILE = "home_profile"
        private const val KEY_AGGRESSIVENESS = "aggressiveness"
        private const val KEY_UNPLUG_HOUR = "typical_unplug_hour"
        private const val KEY_DIVERGENCE = "prediction_divergence"
        private const val KEY_NUDGE_80 = "nudge_80"
        private const val KEY_PERSISTENT_NOTIF = "persistent_notification_card"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ROOT_OPT_IN = "root_opt_in"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages_guard"
        private const val KEY_GUARD_ALL_BATTERY = "guard_all_battery_apps"
        private const val KEY_GUARD_ALL_CPU = "guard_all_cpu_apps"
        private const val KEY_INACTIVITY_SHIELD = "inactivity_lifespan_shield"
        private const val KEY_ACCESSIBILITY_AUTO_OFF = "accessibility_auto_off"
        private const val KEY_QS_TARGET_PREFIX = "qs_target_enabled_"
    }
}
