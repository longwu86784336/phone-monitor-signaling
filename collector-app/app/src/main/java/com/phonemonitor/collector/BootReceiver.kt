package com.phonemonitor.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", null) ?: return
            val server = prefs.getString("server_url", MainActivity.DEFAULT_SERVER) ?: MainActivity.DEFAULT_SERVER
            val passwordHash = prefs.getString("password_hash", null) ?: return
            // ★ 只有勾选了「记住密码自动启动」才开机自启
            val autoStart = prefs.getBoolean("auto_start", false)
            if (!autoStart) return

            val serviceIntent = Intent(context, CameraService::class.java).apply {
                putExtra("deviceId", deviceId)
                putExtra("server", server)
                putExtra("passwordHash", passwordHash)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
