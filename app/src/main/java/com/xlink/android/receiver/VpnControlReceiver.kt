package com.xlink.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xlink.android.vpn.XlinkVpnService

class VpnControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, XlinkVpnService::class.java)

        when (intent.action) {
            ACTION_START_VPN -> {
                serviceIntent.action = XlinkVpnService.ACTION_START_ALL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ACTION_STOP_VPN -> {
                serviceIntent.action = XlinkVpnService.ACTION_STOP_ALL
                context.startService(serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_START_VPN = "com.xlink.android.ACTION_START_VPN"
        const val ACTION_STOP_VPN = "com.xlink.android.ACTION_STOP_VPN"
    }
}
