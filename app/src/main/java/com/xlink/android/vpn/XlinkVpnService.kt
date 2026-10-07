package com.xlink.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xlink.android.R
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.data.store.NodeStore
import com.xlink.android.engine.CoreEngine
import com.xlink.android.ui.MainActivity
import com.xlink.android.util.PortFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XlinkVpnService : VpnService() {

    companion object {
        private const val TAG = "XlinkVpnService"
        const val ACTION_START = "com.xlink.android.START"
        const val ACTION_STOP = "com.xlink.android.STOP"
        const val ACTION_START_NODE = "com.xlink.android.START_NODE"
        const val ACTION_STOP_NODE = "com.xlink.android.STOP_NODE"
        const val ACTION_START_ALL = "com.xlink.android.START_ALL"
        const val ACTION_STOP_ALL = "com.xlink.android.STOP_ALL"
        const val EXTRA_NODE_ID = "node_id"

        const val NOTIFICATION_CHANNEL_ID = "xlink_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private const val TUN_ADDRESS_V4 = "198.18.0.1"
        private const val TUN_PREFIX_V4 = 16
        private const val TUN_ADDRESS_V6 = "fc00::1"
        private const val TUN_PREFIX_V6 = 128
        private const val TUN_MTU = 1500
        private const val TUN_FAKEDNS_IP = "198.18.0.2"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nodeStore: NodeStore
    private var tunManager: TunManager? = null

    @Volatile
    private var tunPfd: ParcelFileDescriptor? = null

    @Volatile
    private var activeNodeId: String? = null

    override fun onCreate() {
        super.onCreate()
        nodeStore = NodeStore(applicationContext)
        CoreEngine.setProtectCallback { fd -> protect(fd) }
        createNotificationChannel()
        VpnStateHolder.resetAll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Xlink 正在连接..."))

        when (intent?.action) {
            ACTION_START_NODE -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                if (!nodeId.isNullOrEmpty()) {
                    serviceScope.launch { startSingleNode(nodeId) }
                } else {
                    serviceScope.launch { startDefaultNode() }
                }
            }
            ACTION_STOP_NODE -> serviceScope.launch { stopCurrentRunningLocked() }
            ACTION_START, ACTION_START_ALL -> serviceScope.launch { startDefaultNode() }
            ACTION_STOP, ACTION_STOP_ALL -> serviceScope.launch {
                stopCurrentRunningLocked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> serviceScope.launch {
                if (!VpnStateHolder.isAnyRunning() && activeNodeId == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        serviceScope.launch {
            stopCurrentRunningLocked()
            VpnStateHolder.setError("system", "Xlink", "VPN 权限已被其他应用接管")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        try { tunManager?.stopSync() } catch (_: Exception) {}
        tunManager = null
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        try { CoreEngine.stopNode() } catch (_: Exception) {}
        activeNodeId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        VpnStateHolder.resetAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startDefaultNode() {
        withContext(Dispatchers.IO) {
            val nodes = nodeStore.loadOnce()
            if (nodes.isEmpty()) {
                VpnStateHolder.emitLog("system", "Xlink", "未找到可用节点配置", LogLevel.ERROR)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@withContext
            }
            startSingleNode(nodes.first().id)
        }
    }

    private suspend fun startSingleNode(nodeId: String) {
        withContext(Dispatchers.IO) {
            if (activeNodeId == nodeId && VpnStateHolder.isRunning(nodeId)) return@withContext

            val nodes = nodeStore.loadOnce()
            val node = nodes.firstOrNull { it.id == nodeId } ?: return@withContext

            if (activeNodeId != null) stopCurrentRunningLocked()
            VpnStateHolder.setStarting(nodeId, node.name)

            try {
                val (configuredHost, configuredPort) = NodeConfig.parseListenAddr(node.listen)
                val socksPort = PortFinder.findFree(configuredPort)
                val listenAddr = "$configuredHost:$socksPort"

                val coreResult = CoreEngine.startNode(node, listenAddr)
                if (coreResult.isFailure) {
                    throw coreResult.exceptionOrNull() ?: IllegalStateException("Go 核心引擎启动失败")
                }

                VpnStateHolder.emitLog(nodeId, node.name, "正在创建 TUN 虚拟网卡...", LogLevel.INFO)
                val pfd = establishTun() ?: throw IllegalStateException("TUN 虚拟网卡分配失败")
                tunPfd = pfd

                val tm = TunManager(applicationContext)
                tm.onError = { err ->
                    VpnStateHolder.emitLog(nodeId, node.name, "[TUN 异常] $err", LogLevel.ERROR)
                }
                tm.start(pfd.fd, socksPort)
                tunManager = tm

                activeNodeId = nodeId
                VpnStateHolder.registerEngine(EngineHandle(nodeId = nodeId, tunStarted = true, internalPort = socksPort))
                VpnStateHolder.setRunning(nodeId, node.name, socksPort)
                updateNotification("Xlink 运行中 · 节点: ${node.name}")

            } catch (e: Exception) {
                val errMsg = e.message ?: "未知异常"
                Log.e(TAG, "启动节点失败: $errMsg", e)
                VpnStateHolder.setError(nodeId, node.name, errMsg)
                stopCurrentRunningLocked()
            }
        }
    }

    private suspend fun stopCurrentRunningLocked() {
        val runningId = activeNodeId
        if (runningId != null) {
            VpnStateHolder.emitLog(runningId, "Xlink", "正在关闭连接...", LogLevel.INFO)
        }
        try { tunManager?.stopSync() } catch (_: Exception) {}
        tunManager = null
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        try { CoreEngine.stopNode() } catch (_: Exception) {}
        if (runningId != null) {
            VpnStateHolder.setStopped(runningId, "")
            activeNodeId = null
        }
    }

    private fun establishTun(): ParcelFileDescriptor? {
        return try {
            Builder()
                .addAddress(TUN_ADDRESS_V4, TUN_PREFIX_V4)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_FAKEDNS_IP)
                .addRoute("100.64.0.0", 10) // 确保全部 CGNAT FakeIP 流量无缝路由进 TUN 网卡
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute("::", 0)
                .setMtu(TUN_MTU)
                .setBlocking(false)
                .setSession("Xlink Odyssey")
                .addDisallowedApplication(packageName)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "establishTun 失败: ${e.message}", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_vpn_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_vpn_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, XlinkVpnService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}