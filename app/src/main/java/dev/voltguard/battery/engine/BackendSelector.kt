package dev.voltguard.battery.engine

enum class QsTargetKey(val title: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    LOCATION("Location / GPS"),
    AIRPLANE("Airplane Mode (Force OFF)"),
    DND("Do Not Disturb"),
    RINGER_MUTE("Mute / Silent / Vibrate"),
    FLASHLIGHT("Flashlight"),
    NFC("NFC"),
    BATTERY_SAVER("Battery Saver"),
    DARK_MODE("Dark Mode"),
    AUTO_BRIGHTNESS("Auto-Brightness"),
    AUTO_SYNC("Auto-Sync"),
    SCREEN_CAST("Screen Cast"),
    MOBILE_DATA("Mobile Data")
}

enum class ExecutorBackend {
    SHIZUKU,
    WRITE_SECURE_SETTINGS,
    PLAIN_SDK,
    ACCESSIBILITY,
    UNSUPPORTED
}

enum class ItemExecState {
    DONE,
    SKIPPED_ALREADY_OFF,
    NEEDS_PERMISSION,
    UNSUPPORTED
}

data class BackendCapabilities(
    val shizukuReady: Boolean,
    val hasWriteSecureSettings: Boolean,
    val hasWriteSettings: Boolean,
    val hasNotificationPolicyAccess: Boolean,
    val hasBluetoothPermission: Boolean,
    val androidSdkInt: Int,
    val accessibilityEnabled: Boolean
)

data class QsItemExecutionResult(
    val key: QsTargetKey,
    val wasOn: Boolean,
    val isNowOff: Boolean,
    val backend: ExecutorBackend,
    val state: ItemExecState,
    val message: String
)

object BackendSelector {
    val ORDERED_TARGETS = listOf(
        // Strict requirement: Wi-Fi & Bluetooth disabled BEFORE Location
        QsTargetKey.WIFI,
        QsTargetKey.BLUETOOTH,
        QsTargetKey.LOCATION,
        QsTargetKey.AIRPLANE,
        QsTargetKey.DND,
        QsTargetKey.RINGER_MUTE,
        QsTargetKey.FLASHLIGHT,
        QsTargetKey.NFC,
        QsTargetKey.BATTERY_SAVER,
        QsTargetKey.DARK_MODE,
        QsTargetKey.AUTO_BRIGHTNESS,
        QsTargetKey.AUTO_SYNC,
        QsTargetKey.SCREEN_CAST,
        QsTargetKey.MOBILE_DATA
    )

    fun selectBestBackend(key: QsTargetKey, caps: BackendCapabilities): ExecutorBackend = when (key) {
        QsTargetKey.WIFI -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.BLUETOOTH -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasBluetoothPermission && caps.androidSdkInt <= 32 -> ExecutorBackend.PLAIN_SDK
            caps.hasWriteSecureSettings -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.LOCATION -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasWriteSecureSettings -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.AIRPLANE -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasWriteSecureSettings && caps.androidSdkInt <= 29 -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.NFC -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasWriteSecureSettings -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.MOBILE_DATA -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.BATTERY_SAVER -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasWriteSecureSettings -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.DARK_MODE -> when {
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.hasWriteSecureSettings -> ExecutorBackend.WRITE_SECURE_SETTINGS
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.DND -> when {
            caps.hasNotificationPolicyAccess -> ExecutorBackend.PLAIN_SDK
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            caps.accessibilityEnabled -> ExecutorBackend.ACCESSIBILITY
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.RINGER_MUTE -> when {
            caps.hasNotificationPolicyAccess -> ExecutorBackend.PLAIN_SDK
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.FLASHLIGHT -> ExecutorBackend.PLAIN_SDK
        QsTargetKey.AUTO_BRIGHTNESS -> when {
            caps.hasWriteSettings -> ExecutorBackend.PLAIN_SDK
            caps.shizukuReady -> ExecutorBackend.SHIZUKU
            else -> ExecutorBackend.UNSUPPORTED
        }
        QsTargetKey.AUTO_SYNC -> ExecutorBackend.PLAIN_SDK
        QsTargetKey.SCREEN_CAST -> if (caps.shizukuReady) ExecutorBackend.SHIZUKU else ExecutorBackend.PLAIN_SDK
    }
}
