package dev.voltguard.battery

import dev.voltguard.battery.engine.BackendCapabilities
import dev.voltguard.battery.engine.BackendSelector
import dev.voltguard.battery.engine.ExecutorBackend
import dev.voltguard.battery.engine.QsTargetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {

    @Test
    fun shizukuIsPrimaryForRadiosAndSystemModes() {
        val caps = BackendCapabilities(
            shizukuReady = true,
            hasWriteSecureSettings = true,
            hasWriteSettings = true,
            hasNotificationPolicyAccess = true,
            hasBluetoothPermission = true,
            androidSdkInt = 31,
            accessibilityEnabled = false
        )
        assertEquals(ExecutorBackend.SHIZUKU, BackendSelector.selectBestBackend(QsTargetKey.WIFI, caps))
        assertEquals(ExecutorBackend.SHIZUKU, BackendSelector.selectBestBackend(QsTargetKey.LOCATION, caps))
        assertEquals(ExecutorBackend.SHIZUKU, BackendSelector.selectBestBackend(QsTargetKey.NFC, caps))
        assertEquals(ExecutorBackend.PLAIN_SDK, BackendSelector.selectBestBackend(QsTargetKey.FLASHLIGHT, caps))
        assertEquals(ExecutorBackend.PLAIN_SDK, BackendSelector.selectBestBackend(QsTargetKey.AUTO_SYNC, caps))
    }

    @Test
    fun fallsBackToWriteSecureSettingsAndPlainSdkWhenShizukuMissing() {
        val caps = BackendCapabilities(
            shizukuReady = false,
            hasWriteSecureSettings = true,
            hasWriteSettings = true,
            hasNotificationPolicyAccess = true,
            hasBluetoothPermission = true,
            androidSdkInt = 31,
            accessibilityEnabled = false
        )
        assertEquals(ExecutorBackend.PLAIN_SDK, BackendSelector.selectBestBackend(QsTargetKey.BLUETOOTH, caps))
        assertEquals(ExecutorBackend.WRITE_SECURE_SETTINGS, BackendSelector.selectBestBackend(QsTargetKey.LOCATION, caps))
        assertEquals(ExecutorBackend.PLAIN_SDK, BackendSelector.selectBestBackend(QsTargetKey.DND, caps))
        assertEquals(ExecutorBackend.PLAIN_SDK, BackendSelector.selectBestBackend(QsTargetKey.AUTO_BRIGHTNESS, caps))
        assertEquals(ExecutorBackend.UNSUPPORTED, BackendSelector.selectBestBackend(QsTargetKey.WIFI, caps))
    }

    @Test
    fun wifiAndBluetoothRunStrictlyBeforeLocation() {
        val wifiIdx = BackendSelector.ORDERED_TARGETS.indexOf(QsTargetKey.WIFI)
        val btIdx = BackendSelector.ORDERED_TARGETS.indexOf(QsTargetKey.BLUETOOTH)
        val locIdx = BackendSelector.ORDERED_TARGETS.indexOf(QsTargetKey.LOCATION)
        assertTrue(wifiIdx < locIdx)
        assertTrue(btIdx < locIdx)
    }
}
