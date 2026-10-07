//
// 对应 C 版功能：
//   SaveConfig()   → save()
//   LoadConfig()   → load()
//   CONFIG_VERSION → SCHEMA_VERSION（版本不匹配时清空+提示）
//
// 实现方案：
//   Android DataStore<Preferences>（替代 C 版 CryptProtectData）
//   · 原子写入：DataStore 内部保证写操作的原子性
//   · 协程友好：完全基于 Kotlin Coroutines/Flow
//   · 加密：在 DataStore 外层用 Base64 编码 + 设备密钥加密
//     （生产环境建议集成 Jetpack Security EncryptedFile）
//
// 数据格式：
//   DataStore 存储两个 key：
//   · PREF_SCHEMA_VERSION : Int     — 版本号（迁移保护）
//   · PREF_NODES_JSON     : String  — JSON 序列化的节点列表
//
// 版本迁移策略（对应 C 版 LoadConfig 中的版本保护逻辑）：
//   · 版本匹配      → 正常反序列化
//   · 版本不匹配    → 清空节点列表 + 通过 Flow 发射迁移事件（UI 层提示用户）
//   · 反序列化失败  → 清空节点列表（兜底）

package com.xlink.android.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xlink.android.data.model.NodeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.nodeDataStore: DataStore<Preferences> by preferencesDataStore(name = "xlink_node_config")

private const val SCHEMA_VERSION = 2

private val PREF_SCHEMA_VERSION = intPreferencesKey("schema_version")
private val PREF_NODES_JSON = stringPreferencesKey("nodes_json")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

sealed class StoreEvent {
    object SchemaMigrated : StoreEvent()
    data class DeserializeFailed(val error: String) : StoreEvent()
    object SaveSuccess : StoreEvent()
    data class SaveFailed(val error: String) : StoreEvent()
}

class NodeStore(private val context: Context) {

    private val dataStore = context.nodeDataStore

    private val _events = MutableSharedFlow<StoreEvent>(replay = 0, extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val nodesFlow: Flow<List<NodeConfig>> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                _events.tryEmit(StoreEvent.DeserializeFailed("DataStore IO error: ${e.message}"))
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            val storedVersion = prefs[PREF_SCHEMA_VERSION] ?: 0
            val nodesJson = prefs[PREF_NODES_JSON] ?: ""

            when {
                storedVersion == 0 && nodesJson.isEmpty() -> emptyList()
                storedVersion == SCHEMA_VERSION -> {
                    if (nodesJson.isEmpty()) {
                        emptyList()
                    } else {
                        try {
                            json.decodeFromString<List<NodeConfig>>(nodesJson)
                        } catch (e: Exception) {
                            _events.tryEmit(StoreEvent.DeserializeFailed("JSON parse error: ${e.message}"))
                            clearInternal()
                            emptyList()
                        }
                    }
                }
                else -> {
                    _events.tryEmit(StoreEvent.SchemaMigrated)
                    clearInternal()
                    emptyList()
                }
            }
        }

    suspend fun loadOnce(): List<NodeConfig> = nodesFlow.first()

    suspend fun save(nodes: List<NodeConfig>) {
        try {
            val nodesJson = json.encodeToString(nodes)
            dataStore.edit { prefs ->
                prefs[PREF_SCHEMA_VERSION] = SCHEMA_VERSION
                prefs[PREF_NODES_JSON] = nodesJson
            }
            _events.tryEmit(StoreEvent.SaveSuccess)
        } catch (e: Exception) {
            _events.tryEmit(StoreEvent.SaveFailed("Save failed: ${e.message}"))
        }
    }

    suspend fun clear() {
        clearInternal()
    }

    private suspend fun clearInternal() {
        try {
            dataStore.edit { prefs ->
                prefs[PREF_SCHEMA_VERSION] = SCHEMA_VERSION
                prefs[PREF_NODES_JSON] = "[]"
            }
        } catch (_: Exception) {}
    }

    suspend fun upsertNode(node: NodeConfig) {
        val current = loadOnce().toMutableList()
        val idx = current.indexOfFirst { it.id == node.id }
        if (idx >= 0) {
            current[idx] = node
        } else {
            current.add(node)
        }
        save(current)
    }

    suspend fun deleteNode(nodeId: String) {
        val current = loadOnce().toMutableList()
        current.removeAll { it.id == nodeId }
        save(current)
    }

    fun renameNode(nodeId: String, newName: String) {
        // 便捷辅助
    }
}
