package com.xlink.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xlink.android.util.AutoStartManager
import com.xlink.android.vpn.XlinkVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (AutoStartManager.isEnabled(context)) {
                val serviceIntent = Intent(context, XlinkVpnService::class.java).apply {
                    action = XlinkVpnService.ACTION_START_ALL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
