//
// 修复重点：
//   1. 结合 VpnStateHolder 动态同步每个节点的 isRunning 实时状态
//   2. 补齐 HomeScreen 与 NodeEditScreen 所需的全部接口与状态流
//   3. 消除与不可变 NodeConfig 之间的调用冲突
package com.xlink.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.data.model.NodeRunState
import com.xlink.android.data.store.NodeStore
import com.xlink.android.data.sub.SubRepository
import com.xlink.android.data.sub.SubResult
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

sealed class NodeUiEvent {
    data class ShowToast(val message: String) : NodeUiEvent()
    data class ShowError(val message: String) : NodeUiEvent()
    data class ImportSuccess(val nodeName: String) : NodeUiEvent()
    data class ImportFailed(val reason: String) : NodeUiEvent()
    data class ExportSuccess(val uri: String) : NodeUiEvent()
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
                is SubResult.Failure -> withContext(Dispatchers.Main) { onResult("更新失败: ${res.reason}") }
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
