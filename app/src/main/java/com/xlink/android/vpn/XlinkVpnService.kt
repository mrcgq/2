package com.xlink.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.xlink.android.util.AppFilterManager
import com.xlink.android.util.PortFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class XlinkVpnService : VpnService() {

    companion object {
        private const val TAG = "XlinkVpnService"
        const val ACTION_START = "com.xlink.android.START"
        const val ACTION_STOP = "com.xlink.android.STOP"
        const val ACTION_START_NODE = "com.xlink.android.START_NODE"
        const val ACTION_STOP_NODE = "com.xlink.android.STOP_NODE"
        const val ACTION_START_ALL = "com.xlink.android.START_ALL"
        const val ACTION_STOP_ALL = "com.xlink.android.STOP_ALL"
        const val ACTION_RESTART_TUN = "com.xlink.android.RESTART_TUN"
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

    @Volatile
    private var activeNodeName: String = "Xlink"

    // ── 网络自愈核心状态 ──────────────────────────────────────────
    private var connectivityManager: ConnectivityManager? = null
    private var networkHandoverCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUnderlyingNetwork: Network? = null
    private var lastHandoverTimestamp: Long = 0L

    // ── 实时网速监控状态 ──────────────────────────────────────────
    private var statsJob: Job? = null
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L

    override fun onCreate() {
        super.onCreate()
        nodeStore = NodeStore(applicationContext)
        CoreEngine.setProtectCallback { fd -> protect(fd) }
        createNotificationChannel()
        VpnStateHolder.resetAll()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetworkHandoverListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Xlink 正在连接...", "准备建立安全隧道"))

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
            ACTION_RESTART_TUN -> {
                val currentId = activeNodeId
                if (currentId != null) {
                    serviceScope.launch {
                        stopCurrentRunningLocked()
                        startSingleNode(currentId)
                    }
                }
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
        unregisterNetworkHandoverListener()
        stopStatsMonitor()
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
            activeNodeName = node.name

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

                // 启动实时网速与流量轮询
                startStatsMonitor(node.name)

            } catch (e: Exception) {
                val errMsg = e.message ?: "未知异常"
                Log.e(TAG, "启动节点失败: $errMsg", e)
                VpnStateHolder.setError(nodeId, node.name, errMsg)
                stopCurrentRunningLocked()
            }
        }
    }

    private suspend fun stopCurrentRunningLocked() {
        stopStatsMonitor()
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
            val builder = Builder()
                .addAddress(TUN_ADDRESS_V4, TUN_PREFIX_V4)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_FAKEDNS_IP)
                .addRoute("100.64.0.0", 10)
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute("::", 0)
                .setMtu(TUN_MTU)
                .setBlocking(false)
                .setSession("Xlink Odyssey")

            val isPerAppEnabled = AppFilterManager.isEnabled(applicationContext)
            val selectedApps = AppFilterManager.getSelectedApps(applicationContext)

            if (isPerAppEnabled && selectedApps.isNotEmpty()) {
                var addedCount = 0
                for (pkg in selectedApps) {
                    try {
                        packageManager.getPackageInfo(pkg, 0)
                        builder.addAllowedApplication(pkg)
                        addedCount++
                    } catch (_: PackageManager.NameNotFoundException) {}
                }
                if (addedCount == 0) {
                    builder.addDisallowedApplication(packageName)
                }
            } else {
                builder.addDisallowedApplication(packageName)
            }

            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establishTun 失败: ${e.message}", e)
            null
        }
    }

    // ── 第一项：网络切换自愈引擎 (消灭 Wi-Fi/5G 切换假死) ──────────────────
    private fun registerNetworkHandoverListener() {
        val cm = connectivityManager ?: return
        networkHandoverCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                inspectAndHealNetwork(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // 关键防死锁：严格忽略 VPN 自身接口产生的网络事件
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    inspectAndHealNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                if (network == lastUnderlyingNetwork) {
                    lastUnderlyingNetwork = null
                }
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // 严格排除虚拟网卡
                .build()
            cm.registerNetworkCallback(request, networkHandoverCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "注册网络自愈监听失败: ${e.message}")
        }
    }

    private fun unregisterNetworkHandoverListener() {
        val cm = connectivityManager ?: return
        val cb = networkHandoverCallback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkHandoverCallback = null
    }

    private fun inspectAndHealNetwork(newNetwork: Network) {
        val runningId = activeNodeId ?: return
        if (!VpnStateHolder.isAnyRunning()) return

        val now = System.currentTimeMillis()
        // 2.5 秒物理防抖，防止基站瞬态乒乓漫游
        if (newNetwork == lastUnderlyingNetwork && (now - lastHandoverTimestamp) < 3000L) return
        if ((now - lastHandoverTimestamp) < 2500L) return

        lastUnderlyingNetwork = newNetwork
        lastHandoverTimestamp = now

        Log.i(TAG, "检测到物理网络漫游变更，执行毫秒级后台静默自愈...")
        VpnStateHolder.emitLog(runningId, "系统", "网络环境变化，已触发自愈重连...", LogLevel.INFO)

        serviceScope.launch {
            stopCurrentRunningLocked()
            startSingleNode(runningId)
        }
    }

    // ── 第二项：通知栏实时网速与累计流量引擎 ───────────────────────────
    private fun startStatsMonitor(nodeName: String) {
        statsJob?.cancel()
        lastRxBytes = 0L
        lastTxBytes = 0L

        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (!VpnStateHolder.isAnyRunning() || activeNodeId == null) break

                try {
                    val stats = TProxyService.TProxyGetStats()
                    if (stats != null && stats.size >= 2) {
                        val currentRx = stats[0]
                        val currentTx = stats[1]

                        val rxSpeed = if (lastRxBytes > 0 && currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
                        val txSpeed = if (lastTxBytes > 0 && currentTx >= lastTxBytes) currentTx - lastTxBytes else 0L

                        lastRxBytes = currentRx
                        lastTxBytes = currentTx

                        val speedText = "↑ ${formatSpeed(txSpeed)}  ↓ ${formatSpeed(rxSpeed)} · 已用: ${formatBytes(currentRx + currentTx)}"
                        updateNotification("节点: $nodeName", speedText)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取网速异常: ${e.message}")
                }
            }
        }
    }

    private fun stopStatsMonitor() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024f)
            else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024f * 1024f))
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
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

    private fun buildNotification(titleText: String, contentText: String): Notification {
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
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // 关键：每秒静默刷新文本，绝对不振动、不响铃、不闪烁
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(titleText: String, contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(titleText, contentText))
    }
}
