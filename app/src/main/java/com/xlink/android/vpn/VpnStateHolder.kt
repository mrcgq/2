//
// 对应 C 版：
//   g_nodes[i].isRunning    → NodeRunStatus.state（单节点状态）
//   g_engineStatuses[]      → engineStatuses Map（引擎句柄）
//   SidecarEngineStatus     → EngineHandle（Kotlin 等价物）
//
// 架构设计：
//   · 单例对象（Kotlin object）：跨 Service/ViewModel/UI 共享状态
//   · StateFlow：热流，订阅者立即收到当前值（等价 C 版全局变量直读）
//   · SharedFlow：事件总线，单次消费事件（日志、错误通知等）
//
// 线程安全：
//   · StateFlow/SharedFlow 本身是线程安全的
//   · engineHandles Map 用 ConcurrentHashMap 保护
//   · 所有写操作通过 update() 方法序列化

package com.xlink.android.vpn

import com.xlink.android.data.model.NodeRunState
import com.xlink.android.data.model.NodeRunStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class VpnState { STOPPED, STARTING, RUNNING, ERROR }

data class EngineHandle(
    val nodeId: String,
    val coreInstanceId: Long = 0L,
    val tunStarted: Boolean = false,
    val internalPort: Int = 0,
)

data class VpnLogEvent(
    val nodeId: String,
    val nodeName: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class LogLevel { DEBUG, INFO, SUCCESS, WARNING, ERROR }

object VpnStateHolder {
    private val _vpnState = MutableStateFlow(VpnState.STOPPED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _nodeStatuses = MutableStateFlow<Map<String, NodeRunStatus>>(emptyMap())
    val nodeStatuses: StateFlow<Map<String, NodeRunStatus>> = _nodeStatuses.asStateFlow()

    private val _logEvents = MutableSharedFlow<VpnLogEvent>(replay = 0, extraBufferCapacity = 256)
    val logEvents = _logEvents.asSharedFlow()

    private val engineHandles = ConcurrentHashMap<String, EngineHandle>()

    @Volatile
    var isBusy: Boolean = false
        private set

    fun isRunning(nodeId: String): Boolean = _nodeStatuses.value[nodeId]?.state == NodeRunState.RUNNING
    fun isAnyRunning(): Boolean = _nodeStatuses.value.values.any { it.state == NodeRunState.RUNNING }
    fun runningCount(): Int = _nodeStatuses.value.values.count { it.state == NodeRunState.RUNNING }

    fun setStarting(nodeId: String, nodeName: String) {
        updateNodeStatus(nodeId) { NodeRunStatus(nodeId, NodeRunState.STARTING) }
        recomputeVpnState()
        emitLog(nodeId, nodeName, "[系统]正在启动引擎...", LogLevel.INFO)
    }

    fun setRunning(nodeId: String, nodeName: String, internalPort: Int = 0) {
        engineHandles[nodeId] = (engineHandles[nodeId] ?: EngineHandle(nodeId)).copy(tunStarted = true, internalPort = internalPort)
        updateNodeStatus(nodeId) { NodeRunStatus(nodeId, NodeRunState.RUNNING, internalPort) }
        recomputeVpnState()
        emitLog(nodeId, nodeName, "[成功]引擎启动，隧道已建立。", LogLevel.SUCCESS)
    }

    fun setStopped(nodeId: String, nodeName: String) {
        engineHandles.remove(nodeId)
        updateNodeStatus(nodeId) { NodeRunStatus(nodeId, NodeRunState.STOPPED) }
        recomputeVpnState()
        emitLog(nodeId, nodeName, "[系统]节点已停止。", LogLevel.INFO)
    }

    fun setError(nodeId: String, nodeName: String, reason: String) {
        engineHandles.remove(nodeId)
        updateNodeStatus(nodeId) { NodeRunStatus(nodeId, NodeRunState.ERROR) }
        recomputeVpnState()
        emitLog(nodeId, nodeName, "[错误]$reason", LogLevel.ERROR)
    }

    fun registerEngine(handle: EngineHandle) { engineHandles[handle.nodeId] = handle }

    fun resetAll() {
        engineHandles.clear()
        _nodeStatuses.value = emptyMap()
        _vpnState.value = VpnState.STOPPED
        isBusy = false
    }

    fun emitLog(nodeId: String, nodeName: String, message: String, level: LogLevel = LogLevel.INFO) {
        _logEvents.tryEmit(VpnLogEvent(nodeId = nodeId, nodeName = nodeName, message = message, level = level))
    }

    private fun updateNodeStatus(nodeId: String, updater: (NodeRunStatus?) -> NodeRunStatus) {
        _nodeStatuses.update { current ->
            current.toMutableMap().also { map -> map[nodeId] = updater(map[nodeId]) }
        }
    }

    private fun recomputeVpnState() {
        val statuses = _nodeStatuses.value.values
        val newState = when {
            statuses.any { it.state == NodeRunState.RUNNING } -> VpnState.RUNNING
            statuses.any { it.state == NodeRunState.STARTING } -> VpnState.STARTING
            statuses.any { it.state == NodeRunState.ERROR } && statuses.none { it.state == NodeRunState.RUNNING } -> VpnState.ERROR
            else -> VpnState.STOPPED
        }
        _vpnState.value = newState
    }
}
