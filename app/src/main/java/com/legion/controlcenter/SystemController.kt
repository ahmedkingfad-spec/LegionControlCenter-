package com.legion.controlcenter

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast

data class HardwareStats(
    val batteryPercent: Int = 78,
    val isCharging: Boolean = true,
    val tempCelsius: Float = 36.0f,
    val fanRpm: Int = 4280,
    val storageUsedGb: Float = 142.6f,
    val storageTotalGb: Float = 256.0f,
    val ramUsedGb: Float = 7.4f,
    val ramTotalGb: Float = 12.0f
)

class SystemController(private val context: Context) {

    private val prefs = context.getSharedPreferences("legion_prefs", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isWifiEnabled(): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        } catch (_: Exception) {
            true
        }
    }

    fun toggleWifi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = !wm.isWifiEnabled
            }
        } catch (_: Exception) {
            openSetting(Settings.ACTION_WIFI_SETTINGS)
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    fun toggleBluetooth() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                @Suppress("MissingPermission", "DEPRECATION")
                if (adapter.isEnabled) adapter.disable() else adapter.enable()
            } else {
                openSetting(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
        } catch (_: Exception) {
            openSetting(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
    }

    fun isAirplaneMode(): Boolean {
        return Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    fun toggleAirplaneMode() {
        openSetting(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
    }

    fun isAutoRotate(): Boolean {
        return Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
    }

    fun toggleAutoRotate(): Boolean {
        return if (Settings.System.canWrite(context)) {
            val next = if (isAutoRotate()) 0 else 1
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, next)
            next == 1
        } else {
            openWriteSettingsPermission()
            false
        }
    }

    fun isDndEnabled(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun toggleDnd() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            val next = if (isDndEnabled()) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            }
            nm.setInterruptionFilter(next)
        } else {
            openSetting(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }

    fun openHotspot() {
        try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openSetting(Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    fun isLocationEnabled(): Boolean {
        return Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
    }

    fun toggleLocation() {
        openSetting(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    fun openNfc() {
        openSetting(Settings.ACTION_NFC_SETTINGS)
    }

    fun toggleBatterySaver() {
        openSetting(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    }

    fun toggleDarkMode() {
        openSetting(Settings.ACTION_DISPLAY_SETTINGS)
    }

    fun takeScreenshot() {
        OverlayService.instance?.hidePanelTemporarilyForAction {
            val ok = LegionAccessibilityService.instance?.takeScreenshot() ?: false
            if (!ok) {
                Toast.makeText(context, "فعّل خدمة Accessibility لأخذ لقطة شاشة فورية", Toast.LENGTH_SHORT).show()
                openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }
    }

    fun startScreenRecord() {
        OverlayService.instance?.hidePanelTemporarilyForAction {
            try {
                val zuiIntent = context.packageManager.getLaunchIntentForPackage("com.zui.screenrecorder")
                    ?: context.packageManager.getLaunchIntentForPackage("com.lenovo.screenrecorder")
                if (zuiIntent != null) {
                    zuiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(zuiIntent)
                } else {
                    LegionAccessibilityService.instance?.openQuickSettings()
                    Toast.makeText(context, "اختر Screen Record من الشريط السريع", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                LegionAccessibilityService.instance?.openQuickSettings()
            }
        }
    }

    fun openScreenCast() {
        OverlayService.instance?.hidePanelTemporarilyForAction {
            openSetting(Settings.ACTION_CAST_SETTINGS)
        }
    }

    fun toggleSplitScreen() {
        OverlayService.instance?.hidePanelTemporarilyForAction {
            val ok = LegionAccessibilityService.instance?.toggleSplitScreen() ?: false
            if (!ok) {
                Toast.makeText(context, "فعّل Accessibility لتقسيم الشاشة بضغطة واحدة", Toast.LENGTH_SHORT).show()
                openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }
    }

    fun getBrightness(): Float {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 180)
        return (raw / 255f).coerceIn(0f, 1f)
    }

    fun setBrightness(fraction: Float) {
        if (Settings.System.canWrite(context)) {
            val v = (fraction * 255f).toInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        } else {
            openWriteSettingsPermission()
        }
    }

    fun isAutoBrightness(): Boolean {
        return Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    fun toggleAutoBrightness(): Boolean {
        return if (Settings.System.canWrite(context)) {
            val next = if (isAutoBrightness()) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, next)
            next == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            openWriteSettingsPermission()
            false
        }
    }

    fun getVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    fun setVolume(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (fraction * max).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun openSoundPanel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_VOLUME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                openSetting(Settings.ACTION_SOUND_SETTINGS)
            }
        } catch (_: Exception) {
            openSetting(Settings.ACTION_SOUND_SETTINGS)
        }
    }

    fun getPerformanceMode(): String = prefs.getString("perf_mode", "Balanced") ?: "Balanced"

    fun setPerformanceMode(mode: String) {
        prefs.edit().putString("perf_mode", mode).apply()
        when (mode) {
            "Power Saving" -> setRefreshRate(60)
            "Balanced" -> setRefreshRate(120)
            "Performance" -> setRefreshRate(120)
            "Extreme" -> setRefreshRate(144)
        }
    }

    fun getRefreshRate(): Int = prefs.getInt("refresh_rate", 120)

    fun setRefreshRate(hz: Int) {
        prefs.edit().putInt("refresh_rate", hz).apply()
        if (Settings.System.canWrite(context)) {
            try {
                Settings.System.putFloat(context.contentResolver, "peak_refresh_rate", hz.toFloat())
                Settings.System.putFloat(context.contentResolver, "min_refresh_rate", hz.toFloat())
                Settings.System.putInt(context.contentResolver, "user_refresh_rate", hz)
            } catch (_: Exception) {
            }
        }
    }

    fun readHardwareStats(): HardwareStats {
        val bmIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bmIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 78) ?: 78
        val scale = bmIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = bmIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = bmIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 360) ?: 360
        val tempC = tempTenths / 10f

        val mode = getPerformanceMode()
        val baseRpm = when (mode) {
            "Power Saving" -> 2400
            "Balanced" -> 4280
            "Performance" -> 5400
            else -> 6800
        }
        val dynamicRpm = baseRpm + ((tempC - 35f) * 85f).toInt().coerceAtLeast(-300)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.availableBytes
        val usedGb = ((totalBytes - freeBytes) / (1024f * 1024f * 1024f))
        val totalGb = (totalBytes / (1024f * 1024f * 1024f)).coerceAtLeast(64f)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramTotal = (memInfo.totalMem / (1024f * 1024f * 1024f)).coerceAtLeast(4f)
        val ramUsed = ((memInfo.totalMem - memInfo.availMem) / (1024f * 1024f * 1024f)).coerceAtLeast(1f)

        return HardwareStats(
            batteryPercent = pct,
            isCharging = charging,
            tempCelsius = tempC,
            fanRpm = dynamicRpm,
            storageUsedGb = usedGb,
            storageTotalGb = totalGb,
            ramUsedGb = ramUsed,
            ramTotalGb = ramTotal
        )
    }

    fun openGameAssistant() {
        OverlayService.instance?.hidePanelTemporarilyForAction {
            val packages = listOf(
                "com.zui.game.service",
                "com.lenovo.legionzone",
                "com.google.android.play.games"
            )
            for (pkg in packages) {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return@hidePanelTemporarilyForAction
                }
            }
            Toast.makeText(context, "Game Assistant مفعل داخل اللوحة!", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWriteSettingsPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openSetting(action: String) {
        try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
