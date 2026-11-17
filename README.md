# VoltGuard — Android Battery Optimizer (Kotlin, minSdk 26+)

VoltGuard is a transparent, non-root-first Android battery longevity and thermal companion built for the POCO X3 (Snapdragon 732G / 5160 mAh Li-Po) and modern Android 8.0+ devices.

## Architecture & Control Surfaces

1. **Quick Settings Tile Entry (Zero App Drawer Icon) + Jetpack Compose UI**
   - `AndroidManifest.xml` omits both `ACTION_MAIN` and `CATEGORY_LAUNCHER` so **zero app icon appears in your launcher drawer**, while remaining visible in `Settings > Apps` and opening `MainActivity` directly when you **long-press the Quick Settings Tile** (`ACTION_QS_TILE_PREFERENCES` + `CATEGORY_DEFAULT`).
2. **Quick Settings Tile (`OptimizerTileService`) & Home Screen Widget**
   - **Single tap** on the VoltGuard tile cycles 1-to-1 with Home profiles: `Off → Auto → Prfo → Ultra → Intel → Off` and displays live battery % & °C in the subtitle.
   - **Long-press** opens VoltGuard (`MainActivity`) directly via `ACTION_QS_TILE_PREFERENCES`.
   - **Home-Screen Widget (`VoltGuardAllOffWidgetProvider`)**: 1-tap **"Turn All Off"** trigger executing the non-rooted Quick Settings shutoff protocol.
3. **Inactivity Lifespan Shield (8s Untouched Sleep/Lock · Net Cut · Non-Root QS All-Off, Zero Dimming)**
   - **Does NOT dim screen brightness**.
   - If the screen goes **8 seconds untouched** (or immediately on manual power lock / OS sleep), VoltGuard:
     1. Locks/sleeps the display (`DevicePolicyManager.lockNow()`).
     2. Cuts background/system app network & idle drain (`restrict-background` + appops/standby trim when Shizuku is granted + cached process trim).
     3. Runs the **Layered Non-Root Quick Settings Executor** sequentially (Wi-Fi & Bluetooth before Location) to turn OFF selected active toggles:
        **Wi-Fi, Bluetooth, Location/GPS, Airplane Mode (forced OFF), DND OFF, Mute/Silent/Vibrate OFF (Normal), Flashlight OFF, NFC OFF, Battery Saver OFF, Dark Mode OFF, Auto-Brightness OFF (MANUAL mode only), Auto-Sync OFF, Screen Cast stopped, and Mobile Data (OFF by default — opt-in in Settings)**.
   - **Strictly NEVER reads, writes, or touches**:
     - **Wi-Fi Hotspot / Tethering** (zero tethering API calls; hard-coded blocklist prevents `tether`, `softap`, `hotspot` commands)
     - **Rotation Lock** (hard-coded blocklist prevents `accelerometer_rotation` and `user_rotation`)
4. **Fast Charge Assist**: Never throttles or slows down charging; trims background wakeup apps and idle overhead while plugged in so heat stays low and battery reaches 100% faster.

---

## Non-Rooted Xiaomi POCO X3 (MIUI 12–14, Android 10–12) Setup Steps

Because Android 10+ sandboxes public SDK radio toggles on non-rooted devices, VoltGuard uses a 4-tier runtime executor:
1. **Primary — Shizuku (`dev.rikka.shizuku`)**:
   - Install **Shizuku**, enable **Developer options → Wireless debugging** (Android 11+), pair Shizuku, and start it. *(Note: MIUI requires restarting Shizuku after every reboot via Wireless debugging.)*
   - In MIUI **Developer options**, enable **USB debugging (Security settings)** — otherwise MIUI throws `SecurityException` when writing secure settings.
2. **Secondary — ADB `WRITE_SECURE_SETTINGS` (One-Time)**:
   ```bash
   adb shell pm grant dev.voltguard.battery android.permission.WRITE_SECURE_SETTINGS
   ```
3. **Tertiary — Plain SDK**:
   - Flashlight (`CameraManager.setTorchMode`), Bluetooth (`BluetoothAdapter.disable` on targetSdk 32), Ringer/DND (`ACCESS_NOTIFICATION_POLICY`), Auto-Brightness (`WRITE_SETTINGS`), Auto-Sync (`WRITE_SYNC_SETTINGS`).
4. **Optional Last-Resort — AccessibilityService (Disabled by default, opt-in)**:
   - Supports MIUI Control Center (split shade) and classic shade in English and Persian locales, strictly skipping Hotspot / نقطه اتصال and Rotation / چرخش tiles.

### MIUI Background Reliability Checklist
- Enable **Autostart** for **VoltGuard Battery** in `Settings > Apps > Manage apps`.
- Set **Battery saver** for VoltGuard Battery to **No restrictions** so the 8-second Shield, Tile, and Widget keep running reliably.

---

## How to Build the APK on GitHub

This project includes a pre-configured **GitHub Actions** workflow at `.github/workflows/build-apk.yml`.

1. Extract the downloaded ZIP (`VoltGuardBatteryOptimizer-AndroidStudio.zip`).
2. Push all files (including `.github/`) to a GitHub repository.
3. Open **Actions → Build Android APK**.
4. Workflow runs unit tests (`CommandBlocklistTest`, `BackendSelectorTest`) and builds `app-debug.apk` (and release APK).
5. Download the APK artifact and sideload on your POCO X3.
