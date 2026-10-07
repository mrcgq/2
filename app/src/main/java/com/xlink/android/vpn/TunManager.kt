package com.xlink.android.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "TunManager"

enum class TunManagerState { IDLE, STARTING, RUNNING, STOPPING, ERROR }

class TunManager(private val context: Context) {

    @Volatile
    var state: TunManagerState = TunManagerState.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthMonitorJob: Job? = null

    var onStateChanged: ((TunManagerState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    suspend fun start(tunFd: Int, socks5Port: Int) {
        withContext(Dispatchers.IO) {
            if (state == TunManagerState.RUNNING) stopSync()
            setState(TunManagerState.STARTING)

            try {
                if (tunFd < 3) throw IllegalStateException("TUN 文件描述符异常 (fd=$tunFd)")

                val configFile = createTProxyConfig(socks5Port)
                val success = TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
                if (!success) throw IllegalStateException("TProxyStartService 启动返回失败")

                setState(TunManagerState.RUNNING)
                startHealthMonitor()
            } catch (e: Exception) {
                val msg = "TUN 启动异常: ${e.message}"
                Log.e(TAG, msg, e)
                setState(TunManagerState.ERROR)
                onError?.invoke(msg)
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) { stopSync() }
    }

    fun stopSync() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        if (state == TunManagerState.IDLE || state == TunManagerState.STOPPING) return

        setState(TunManagerState.STOPPING)
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "C 核心停止异常: ${e.message}")
        }
        setState(TunManagerState.IDLE)
    }

    private fun createTProxyConfig(socks5Port: Int): File {
        val configFile = File(context.cacheDir, "tproxy.conf")
        if (configFile.exists()) configFile.delete()

        // 使用标准的 CGNAT 假 IP 掩码，确保 100% 能够反向还原出 google.com、youtube.com 域名
        // 同时移除 socks5.udp，让 QUIC 优雅回退至稳定高速的 TCP 协议
        val configYaml = """
            misc:
              task-stack-size: 81920
            tunnel:
              mtu: 1500
              icmp: 'reply'
            socks5:
              port: $socks5Port
              address: '127.0.0.1'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
        """.trimIndent()

        FileOutputStream(configFile).use { fos ->
            fos.write(configYaml.toByteArray(Charsets.UTF_8))
        }
        return configFile
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(10_000L)
                if (state != TunManagerState.RUNNING) break
                try {
                    if (!TProxyService.TProxyIsRunning()) {
                        setState(TunManagerState.ERROR)
                        onError?.invoke("TUN 核心意外中断")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "健康监控异常: ${e.message}")
                }
            }
        }
    }

    private fun setState(newState: TunManagerState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}