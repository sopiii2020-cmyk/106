package dev.voltguard.battery.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.QsTargetKey

class VoltGuardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            InactivityShieldController.reportUserInteraction()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun clickAllowedTileOff(targetKey: QsTargetKey): Boolean {
        val app = applicationContext as VoltGuardApp
        if (!app.preferences.isAccessibilityAutoOffEnabled()) return false

        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        val root = rootInActiveWindow ?: return false
        val keywords = keywordsForTarget(targetKey)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
            if (isForbiddenTileText(text)) continue
            if (text.isNotBlank() && keywords.any { text.contains(it) }) {
                val isCheckedOrSelected = node.isChecked || node.isSelected || text.contains("on") || text.contains("روشن")
                if (isCheckedOrSelected && node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    companion object {
        @Volatile private var instance: VoltGuardAccessibilityService? = null

        private val FORBIDDEN_TILE_KEYWORDS = listOf(
            "hotspot",
            "tether",
            "portable",
            "نقطه اتصال",
            "هات اسپات",
            "هات‌اسپات",
            "auto-rotate",
            "autorotate",
            "rotation",
            "lock orientation",
            "چرخش",
            "قفل چرخش"
        )

        fun isRunning(): Boolean = instance != null

        fun isForbiddenTileText(raw: String): Boolean {
            val lower = raw.lowercase()
            return FORBIDDEN_TILE_KEYWORDS.any { lower.contains(it) }
        }

        fun triggerToggleOff(key: QsTargetKey): Boolean {
            val svc = instance ?: return false
            return svc.clickAllowedTileOff(key)
        }

        private fun keywordsForTarget(key: QsTargetKey): List<String> = when (key) {
            QsTargetKey.WIFI -> listOf("wi-fi", "wifi", "wlan", "وایفای", "وای‌فای")
            QsTargetKey.BLUETOOTH -> listOf("bluetooth", "بلوتوث")
            QsTargetKey.LOCATION -> listOf("location", "gps", "مکان", "جی‌پی‌اس")
            QsTargetKey.NFC -> listOf("nfc", "ان‌اف‌سی")
            QsTargetKey.MOBILE_DATA -> listOf("mobile data", "cellular", "داده همراه", "اینترنت همراه")
            QsTargetKey.FLASHLIGHT -> listOf("flashlight", "torch", "چراغ قوه")
            QsTargetKey.DND -> listOf("do not disturb", "dnd", "مزاحم نشوید")
            QsTargetKey.DARK_MODE -> listOf("dark mode", "dark theme", "حالت تاریک")
            QsTargetKey.BATTERY_SAVER -> listOf("battery saver", "power saving", "ذخیره باتری")
            QsTargetKey.AUTO_BRIGHTNESS -> listOf("auto-brightness", "روشنایی خودکار")
            QsTargetKey.AUTO_SYNC -> listOf("sync", "همگام")
            QsTargetKey.AIRPLANE -> listOf("airplane", "حالت هواپیما")
            QsTargetKey.RINGER_MUTE -> listOf("mute", "silent", "بی‌صدا")
            QsTargetKey.SCREEN_CAST -> listOf("cast", "wireless display", "ارسال صفحه")
        }
    }
}
