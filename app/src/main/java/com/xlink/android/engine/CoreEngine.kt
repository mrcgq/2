//
// 修复重点：
//   1. 移除虚构的多实例 Long 句柄与 EngineSlot，对齐 core.go 单例 API
//   2. 实现 gomobile 的 LogCallback (onLog 3 参数规范) 与 ProtectFunc 接口
//   3. 统一通过 SharedFlow 广播结构化日志，通过 StateFlow 广播引擎运行状态

package com.xlink.android.engine

import android.util.Log
import com.xlink.android.data.model.NodeConfig
import core.Core
import core.LogCallback
import core.ProtectFunc
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CoreEngine {

    private const val TAG = "XlinkCoreEngine"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _runningNodeId = MutableStateFlow<String?>(null)
    val runningNodeId: StateFlow<String?> = _runningNodeId.asStateFlow()

    private val _runningNodeName = MutableStateFlow<String?>(null)
    val runningNodeName: StateFlow<String?> = _runningNodeName.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 50, extraBufferCapacity = 250)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    @Volatile
    private var protectInvoker: ((Int) -> Boolean)? = null

    init {
        Core.setLogCallback(object : LogCallback {
            override fun onLog(level: String?, nodeTag: String?, message: String?) {
                val lvl = when (level?.uppercase(Locale.ROOT)) {
                    "ERROR"   -> LogLevel.FAILURE
                    "WARN"    -> LogLevel.WARN
                    "RULE"    -> LogLevel.RULE
                    "LB"      -> LogLevel.LB
                    "DIRECT"  -> LogLevel.DIRECT
                    "SUCCESS" -> LogLevel.SUCCESS
                    "SYSTEM", "INFO" -> LogLevel.SYSTEM
                    else      -> LogLevel.KERNEL
                }
                val entry = LogEntry(
                    timestamp = nowTime(),
                    nodeName  = nodeTag ?: "Xlink",
                    level     = lvl,
                    message   = message ?: ""
                )
                _logFlow.tryEmit(entry)
            }
        })
    }

    fun setProtectCallback(cb: ((Int) -> Boolean)?) {
        protectInvoker = cb
        if (cb != null) {
            Core.setProtectFunc(object : ProtectFunc {
                override fun protect(fd: Long): Boolean {
                    return cb.invoke(fd.toInt())
                }
            })
        } else {
            Core.setProtectFunc(null)
        }
    }

    @Synchronized
    fun startNode(node: NodeConfig, listenAddr: String): Result<Unit> {
        if (Core.isRunning() || _isRunning.value) {
            stopNode()
        }

        emitSystemLog(node.name, "正在加载配置...")
        emitSystemLog(node.name, "监听地址：$listenAddr")

        Core.setNodeTag(node.name)
        val configJson = generateConfig(node, listenAddr)

        val err = try {
            Core.start(configJson)
        } catch (e: Exception) {
            val msg = "JNI 调用异常: ${e.message}"
            emitSystemLog(node.name, "[错误] $msg")
            Log.e(TAG, msg, e)
            return Result.failure(e)
        }

        if (!err.isNullOrEmpty()) {
            val msg = "内核启动失败: $err"
            emitSystemLog(node.name, "[错误] $msg")
            Log.e(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }

        _isRunning.value = true
        _runningNodeId.value = node.id
        _runningNodeName.value = node.name

        return Result.success(Unit)
    }

    @Synchronized
    fun stopNode() {
        val currentName = _runningNodeName.value ?: "Xlink"
        try {
            Core.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Core.stop() 异常: ${e.message}")
        }
        _isRunning.value = false
        _runningNodeId.value = null
        _runningNodeName.value = null
        emitSystemLog(currentName, "核心引擎已停止。")
    }

    fun isNodeRunning(nodeId: String): Boolean {
        return _isRunning.value && _runningNodeId.value == nodeId
    }

    private fun generateConfig(node: NodeConfig, listenAddr: String): String {
        val safeServer = node.server
            .replace("\r\n", ";")
            .replace("\n", ";")
            .replace("\r", ";")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(";")

        val safeRules = node.rules
            .replace("\r", "")
            .replace(";", "|")
            .replace("\n", "|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("|")

        val strategyStr = when (node.strategyMode) {
            1 -> "rr"
            2 -> "hash"
            else -> "random"
        }

        return Core.generateConfigJSON(
            safeServer,
            node.ip.trim(),
            node.secretKey.trim(),
            node.fallbackIp.trim(),
            listenAddr.trim(),
            strategyStr,
            safeRules
        )
    }

    private fun emitSystemLog(nodeName: String, msg: String) {
        _logFlow.tryEmit(
            LogEntry(
                timestamp = nowTime(),
                nodeName  = nodeName,
                level     = LogLevel.SYSTEM,
                message   = msg
            )
        )
    }

    private fun nowTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
