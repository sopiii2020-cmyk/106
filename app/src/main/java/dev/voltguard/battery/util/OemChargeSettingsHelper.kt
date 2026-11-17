package dev.voltguard.battery.util

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.voltguard.battery.service.VoltGuardDeviceAdminReceiver

object OemChargeSettingsHelper {

    fun enforceBlockedPackagesGuard(
        context: Context,
        blockedPackages: Set<String>,
        rootOptInEnabled: Boolean
    ) {
        val am = context.getSystemService(ActivityManager::class.java)
        for (pkg in blockedPackages) {
            runCatching { am?.killBackgroundProcesses(pkg) }
            if (rootOptInEnabled) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg")).waitFor()
                }
            }
        }
    }

    fun stopOrOpenForceStop(context: Context, packageName: String, rootOptInEnabled: Boolean): Boolean {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.killBackgroundProcesses(packageName)
        }
        if (rootOptInEnabled) {
            val ok = runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
                proc.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return true
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return false
    }

    fun hasNativeChargeLimiter(context: Context): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("xiaomi") ||
            brand.contains("poco") ||
            brand.contains("redmi") ||
            brand.contains("google") ||
            brand.contains("samsung") ||
            brand.contains("oneplus")
    }

    fun openNativeBatteryProtection(context: Context) {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerMainActivity"
                )
            ),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openIgnoreBatteryOptimization(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isAdminActive(ComponentName(context, VoltGuardDeviceAdminReceiver::class.java))
    }

    fun hasNotificationPolicyAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun hasBluetoothConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun openNotificationPolicySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun ensureShieldPermissions(context: Context) {
        if (!hasDeviceAdmin(context)) {
            val admin = ComponentName(context, VoltGuardDeviceAdminReceiver::class.java)
            val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required so the 8-second untouched Inactivity Lifespan Shield can lock the screen automatically."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(adminIntent) }
            return
        }
        if (!Settings.System.canWrite(context)) {
            val writeIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(writeIntent) }
            return
        }
        if (!hasNotificationPolicyAccess(context)) {
            openNotificationPolicySettings(context)
        }
    }

    fun openDeveloperOptions(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
