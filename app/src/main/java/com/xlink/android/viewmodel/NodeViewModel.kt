package com.xlink.android.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.data.model.NodeRunState
import com.xlink.android.data.store.NodeStore
import com.xlink.android.data.sub.SubRepository
import com.xlink.android.data.sub.SubResult
import com.xlink.android.util.ClipboardUtil
import com.xlink.android.util.UriParser
import com.xlink.android.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

sealed class NodeUiEvent {
    data class ShowToast(val message: String) : NodeUiEvent()
    data class ShowError(val message: String) : NodeUiEvent()
}

class NodeViewModel(
    private val nodeStore: NodeStore,
    private val subRepository: SubRepository,
) : ViewModel() {

    private val _persistedNodes = MutableStateFlow<List<NodeConfig>>(emptyList())

    val nodes: StateFlow<List<NodeConfig>> = combine(_persistedNodes, VpnStateHolder.nodeStatuses) { list, statuses ->
        list.map { node -> node.copy(isRunning = statuses[node.id]?.state == NodeRunState.RUNNING) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val selectedNode: StateFlow<NodeConfig?> = nodes.map { list ->
        val idx = _currentIndex.value
        if (idx in list.indices) list[idx] else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _nodeLatencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val nodeLatencies: StateFlow<Map<String, Long>> = _nodeLatencies.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _uiEventFlow = MutableSharedFlow<NodeUiEvent>(extraBufferCapacity = 32)
    val uiEventFlow: SharedFlow<NodeUiEvent> = _uiEventFlow.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = nodeStore.loadOnce()
            withContext(Dispatchers.Main) {
                if (loaded.isEmpty()) {
                    val defaultNode = NodeConfig.createDefault("默认节点")
                    _persistedNodes.value = listOf(defaultNode)
                    persistAsync()
                } else {
                    _persistedNodes.value = loaded
                }
            }
        }
    }

    fun switchNode(index: Int) {
        if (index in _persistedNodes.value.indices) _currentIndex.value = index
    }

    fun addNewNode(template: NodeConfig? = null) {
        val list = _persistedNodes.value.toMutableList()
        if (list.size >= NodeConfig.MAX_NODES) {
            _uiEventFlow.tryEmit(NodeUiEvent.ShowError("已达节点上限"))
            return
        }
        val newNode = template?.copy(isRunning = false) ?: NodeConfig.createDefault("新节点 ${list.size + 1}", list.size)
        list.add(newNode)
        _persistedNodes.value = list
        _currentIndex.value = list.lastIndex
        persistAsync()
    }

    fun importFromClipboard(context: Context) {
        val text = ClipboardUtil.paste(context)?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "剪贴板为空，无法导入", Toast.LENGTH_SHORT).show()
            return
        }
        val parsedNode = UriParser.parse(text)
        if (parsedNode != null) {
            addNewNode(parsedNode)
            Toast.makeText(context, "成功导入节点: ${parsedNode.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未识别到有效的 xlink:// 配置", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportNode(context: Context, node: NodeConfig) {
        val uri = UriParser.serialize(node)
        ClipboardUtil.copy(context, uri)
        Toast.makeText(context, "已成功复制配置到剪贴板！", Toast.LENGTH_SHORT).show()
    }

    fun pingNode(node: NodeConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetHost = if (node.ip.isNotBlank()) {
                node.ip.trim()
            } else {
                val firstLine = node.server.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
                val afterHash = if (firstLine.contains('#')) firstLine.substringAfter('#') else firstLine
                val hostOnly = if (afterHash.startsWith('[')) {
                    afterHash.substringBefore(']') + "]"
                } else {
                    afterHash.substringBefore(':')
                }
                hostOnly.trim()
            }

            if (targetHost.isBlank()) return@launch

            val targetPort = 443
            val start = System.currentTimeMillis()
            var latency: Long = -1
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(targetHost, targetPort), 2500)
                    latency = System.currentTimeMillis() - start
                }
            } catch (_: Exception) {
                latency = -1
            }
            withContext(Dispatchers.Main) {
                _nodeLatencies.value = _nodeLatencies.value.toMutableMap().apply { put(node.id, latency) }
            }
        }
    }

    fun pingAllNodes() {
        val list = _persistedNodes.value
        list.forEach { pingNode(it) }
    }

    fun cloneNode(index: Int) {
        val list = _persistedNodes.value.toMutableList()
        if (index in list.indices) {
            list.add(NodeConfig.clone(list[index]))
            _persistedNodes.value = list
            _currentIndex.value = list.lastIndex
            persistAsync()
        }
    }

    fun deleteNode(index: Int) {
        val list = _persistedNodes.value.toMutableList()
        if (index !in list.indices) return
        if (VpnStateHolder.isRunning(list[index].id)) {
            _uiEventFlow.tryEmit(NodeUiEvent.ShowError("请先停止节点再删除"))
            return
        }
        list.removeAt(index)
        _persistedNodes.value = list
        _currentIndex.value = if (index >= list.size) list.lastIndex.coerceAtLeast(0) else index
        persistAsync()
    }

    fun renameNode(index: Int, newName: String) {
        updateNode(index) { it.copy(name = newName.trim()) }
    }

    fun updateNode(index: Int, transform: (NodeConfig) -> NodeConfig) {
        val list = _persistedNodes.value.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _persistedNodes.value = list
            persistAsync()
        }
    }

    fun updateSubscription(index: Int, onResult: (String) -> Unit) {
        val list = _persistedNodes.value
        if (index !in list.indices) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            when (val res = subRepository.updateNode(list[index])) {
                is SubResult.Success -> withContext(Dispatchers.Main) {
                    updateNode(index) { res.updatedNode }
                    onResult("更新成功！获取到 ${res.nodeCount} 个节点")
                }
                is SubResult.Failure -> withContext(Dispatchers.Main) {
                    onResult("更新失败: ${res.reason}")
                }
            }
            _isBusy.value = false
        }
    }

    fun batchReplaceSni(serverPool: String, newSni: String): String {
        return serverPool.split('\n').mapNotNull { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith('#')) null
            else {
                val target = if (l.contains('#')) l.substringAfter('#') else l
                "$newSni#$target"
            }
        }.joinToString("\n")
    }

    private fun persistAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            try { nodeStore.save(_persistedNodes.value) } catch (e: Exception) { Log.e("NodeViewModel", "保存失败", e) }
        }
    }
}
