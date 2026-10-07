// Android 8.0+ 强制要求：VPN 类 Foreground Service 必须在此注册通知 Channel，
// 否则 startForeground() 会抛 BadNotificationException，系统直接杀进程。

package com.xlink.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi

class XlinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // VPN 前台服务通知 Channel（HIGH 优先级保证通知常驻）
        val vpnChannel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW          // LOW：不响铃，但常驻
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(vpnChannel))
    }
}
