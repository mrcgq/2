package com.xlink.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

enum class NodeRunState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class NodeRunStatus(
    val nodeId: String,
    val state: NodeRunState,
    val internalPort: Int = 0
)

@Serializable
data class NodeConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新节点",
    val listen: String = "127.0.0.1:10808",
    val server: String = "cdn.worker.dev:443",
    val ip: String = "",
    val token: String = "my-password",
    val secretKey: String = "my-secret-key-888",
    val fallbackIp: String = "",
    val rules: String = "",

    val routingMode: Int = ROUTING_GLOBAL,
    val strategyMode: Int = STRATEGY_RANDOM,

    val subUrl: String = "",
    val subAutoUpdate: Boolean = false,
    val subLastUpdate: Long = 0L,
    val sysProxyEnabled: Boolean = false,

    @Transient
    val xlinkInternalPort: Int = 0,

    @Transient
    val isRunning: Boolean = false,
) {
    companion object {
        const val MAX_NODES = 50

        const val ROUTING_GLOBAL = 0
        const val ROUTING_SMART  = 1

        const val STRATEGY_RANDOM = 0
        const val STRATEGY_RR     = 1
        const val STRATEGY_HASH   = 2

        fun createDefault(name: String = "默认节点", portOffset: Int = 0): NodeConfig =
            NodeConfig(
                name = name,
                listen = "127.0.0.1:${10808 + portOffset}",
                server = "cdn.worker.dev:443",
                token = "my-password",
                secretKey = "my-secret-key-888",
                rules = "# 示例: youtube.com,cdn.worker.dev:443\n# google.com,worker2.dev:443",
                routingMode = ROUTING_GLOBAL,
                strategyMode = STRATEGY_RANDOM,
                isRunning = false
            )

        fun clone(source: NodeConfig): NodeConfig {
            val (host, port) = parseListenAddr(source.listen)
            val newPort = if (port >= 65534) 49152 else port + 1
            return source.copy(
                id = UUID.randomUUID().toString(),
                name = "${source.name}(副本)",
                listen = "$host:$newPort",
                isRunning = false,
                xlinkInternalPort = 0
            )
        }

        fun parseListenAddr(listen: String): Pair<String, Int> {
            val colonIdx = listen.lastIndexOf(':')
            return if (colonIdx > 0) {
                val host = listen.substring(0, colonIdx)
                val port = listen.substring(colonIdx + 1).toIntOrNull() ?: 10808
                Pair(host, port)
            } else {
                Pair("127.0.0.1", 10808)
            }
        }
    }

    fun serverAsSemicolonList(): String =
        server.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(";")

    fun rulesAsPipeList(): String =
        rules.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("|")

    fun subLastUpdateFormatted(): String {
        if (subLastUpdate == 0L) return "从未更新"
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(subLastUpdate))
    }
}
