package com.xlink.android.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.xlink.android.ui.MainActivity

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = VpnStateHolder.isAnyRunning()
        if (isRunning) {
            val intent = Intent(this, XlinkVpnService::class.java).apply {
                action = XlinkVpnService.ACTION_STOP_ALL
            }
            startService(intent)
        } else {
            // 防闪退关键：如果系统 VPN 尚未授权，拉起主界面授权，不可盲启
            val prep = VpnService.prepare(this)
            if (prep != null) {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                @Suppress("DEPRECATION")
                startActivityAndCollapse(mainIntent)
                return
            }

            val intent = Intent(this, XlinkVpnService::class.java).apply {
                action = XlinkVpnService.ACTION_START_ALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = VpnStateHolder.isAnyRunning()
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Xlink: 运行中" else "Xlink: 已停止"
        tile.updateTile()
    }
}