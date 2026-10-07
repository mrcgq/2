//
// 修复重点：
//   1. 彻底删除脆弱的手写字符串查找解析器 (jsonGetString 等)。
//   2. 采用官方 kotlinx.serialization.json.Json 强类型反序列化。
//   3. 兼容标准 JSON、VLESS/Trojan 订阅以及 Base64 编码。

package com.xlink.android.data.sub

import android.util.Base64
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.util.UriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class SubResult {
    data class Success(val updatedNode: NodeConfig, val nodeCount: Int) : SubResult()
    data class Failure(val reason: String) : SubResult()
}

@Serializable
private data class XlinkSubscriptionPayload(
    val name: String? = null,
    val sni: String? = null,
    val servers: List<String> = emptyList(),
    val key: String? = null,
    val token: String? = null,
    @SerialName("fallback_ip")
    val fallbackIp: String? = null,
    val ip: String? = null,
)

class SubRepository {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun updateNode(node: NodeConfig): SubResult = withContext(Dispatchers.IO) {
        if (node.subUrl.isBlank()) return@withContext SubResult.Failure("订阅链接为空")

        val rawContent = downloadRaw(node.subUrl) ?: return@withContext SubResult.Failure("下载订阅失败")
        val contentToParse = tryBase64Decode(rawContent) ?: rawContent

        return@withContext when {
            contentToParse.trimStart().startsWith("{") -> parseXlinkJson(contentToParse, node)
            contentToParse.contains("vless://") || contentToParse.contains("trojan://") -> parseVlessUriList(contentToParse, node)
            else -> SubResult.Failure("未能识别订阅格式")
        }
    }

    private fun downloadRaw(url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "Xlink/14.2").build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    private fun tryBase64Decode(content: String): String? {
        return try {
            val decoded = Base64.decode(content.trim(), Base64.DEFAULT)
            String(decoded, Charsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun parseXlinkJson(jsonStr: String, node: NodeConfig): SubResult {
        return try {
            val payload = json.decodeFromString<XlinkSubscriptionPayload>(jsonStr)
            var updated = node
            payload.name?.let { updated = updated.copy(name = it) }
            payload.key?.let { updated = updated.copy(secretKey = it) }
            payload.token?.let { updated = updated.copy(token = it) }
            payload.fallbackIp?.let { updated = updated.copy(fallbackIp = it) }

            val sni = payload.sni.orEmpty()
            val validServers = payload.servers.map { it.trim() }.filter { it.isNotBlank() }
            if (validServers.isNotEmpty()) {
                val pool = if (sni.isNotBlank()) validServers.joinToString("\n") { "$sni#$it" } else validServers.joinToString("\n")
                updated = updated.copy(server = pool)
            }
            SubResult.Success(updated.copy(subLastUpdate = System.currentTimeMillis()), validServers.size)
        } catch (e: Exception) {
            SubResult.Failure("JSON 解析失败: ${e.message}")
        }
    }

    private fun parseVlessUriList(content: String, node: NodeConfig): SubResult {
        val lines = content.split("\n").map { it.trim() }.filter { it.startsWith("vless://") || it.startsWith("trojan://") }
        if (lines.isEmpty()) return SubResult.Failure("未找到代理节点")

        var firstUuid = ""
        val poolEntries = mutableListOf<String>()
        for (line in lines) {
            val parsed = UriParser.parseVlessUri(line)
            if (!parsed.ok) continue
            if (firstUuid.isEmpty()) firstUuid = parsed.uuid
            val entry = if (parsed.sni.isNotBlank()) "${parsed.sni}#${parsed.host}:${parsed.port}" else "${parsed.host}:${parsed.port}"
            poolEntries.add(entry)
        }
        val updated = node.copy(
            token = if (firstUuid.isNotBlank()) firstUuid else node.token,
            server = poolEntries.joinToString("\n"),
            subLastUpdate = System.currentTimeMillis()
        )
        return SubResult.Success(updated, poolEntries.size)
    }
}
