//
// 修复重点：
//   1. 彻底删除对只读 NodeConfig 直接属性赋值的编译错误代码
//   2. 采用局部变量收集 + 构造函数安全生成 NodeConfig
//   3. 导出 ParseResult 密封类，同时兼顾各界面的方法名调用习惯

package com.xlink.android.util

import com.xlink.android.data.model.NodeConfig
import java.net.URLDecoder
import java.net.URLEncoder

object UriParser {

    private const val SCHEME = "xlink://"

    sealed class ParseResult {
        data class Success(val config: NodeConfig) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    fun parse(rawUri: String): NodeConfig? {
        val uri = rawUri.trim()
        if (!uri.startsWith(SCHEME)) return null

        var body = uri.removePrefix(SCHEME)
        var name = "Imported Node"
        val hashIdx = body.indexOf('#')
        if (hashIdx != -1) {
            val rawName = body.substring(hashIdx + 1)
            name = urlDecode(rawName).ifBlank { "Imported Node" }
            body = body.substring(0, hashIdx)
        }

        val qIdx = body.indexOf('?')
        val params = if (qIdx != -1) body.substring(qIdx + 1) else ""
        if (qIdx != -1) body = body.substring(0, qIdx)

        var token = ""
        var server = body
        val atIdx = body.lastIndexOf('@')
        if (atIdx != -1) {
            token = body.substring(0, atIdx)
            server = body.substring(atIdx + 1)
        }

        var secretKey = ""
        var fallbackIp = ""
        var ip = ""
        var routingMode = NodeConfig.ROUTING_GLOBAL
        var strategyMode = NodeConfig.STRATEGY_RANDOM
        var rules = ""

        if (params.isNotEmpty()) {
            params.split('&').forEach { param ->
                val eqIdx = param.indexOf('=')
                if (eqIdx != -1) {
                    val key = param.substring(0, eqIdx)
                    val value = param.substring(eqIdx + 1)
                    when (key) {
                        "key"      -> secretKey = value
                        "fallback" -> fallbackIp = value
                        "ip"       -> ip = value
                        "route"    -> if (value == "cn") routingMode = NodeConfig.ROUTING_SMART
                        "strategy" -> strategyMode = when (value) {
                            "rr"   -> NodeConfig.STRATEGY_RR
                            "hash" -> NodeConfig.STRATEGY_HASH
                            else   -> NodeConfig.STRATEGY_RANDOM
                        }
                        "rules"    -> rules = value.replace("|", "\r\n")
                    }
                }
            }
        }

        return NodeConfig(
            name         = name,
            listen       = "127.0.0.1:10808",
            server       = server,
            ip           = ip,
            token        = token,
            secretKey    = secretKey,
            fallbackIp   = fallbackIp,
            rules        = rules,
            routingMode  = routingMode,
            strategyMode = strategyMode,
            isRunning    = false
        )
    }

    fun parseXlinkUri(uriString: String): ParseResult {
        val node = parse(uriString)
        return if (node != null) ParseResult.Success(node)
        else ParseResult.Failure("无效的 xlink:// 协议格式")
    }

    fun serialize(node: NodeConfig): String {
        val params = buildString {
            var first = true
            fun addParam(key: String, value: String) {
                if (first) first = false else append('&')
                append(key).append('=').append(value)
            }
            if (node.secretKey.isNotBlank()) addParam("key", node.secretKey)
            if (node.fallbackIp.isNotBlank()) addParam("fallback", node.fallbackIp)
            if (node.ip.isNotBlank()) addParam("ip", node.ip)
            if (node.routingMode == NodeConfig.ROUTING_SMART) addParam("route", "cn")
            if (node.strategyMode != NodeConfig.STRATEGY_RANDOM) {
                val s = if (node.strategyMode == NodeConfig.STRATEGY_RR) "rr" else "hash"
                addParam("strategy", s)
            }
        }
        return "$SCHEME${node.token}@${node.server}${if (params.isNotEmpty()) "?$params" else ""}#${urlEncode(node.name)}"
    }

    fun parseVlessUri(uri: String): VlessResult {
        val trimmed = uri.trim()
        val body = when {
            trimmed.startsWith("vless://")  -> trimmed.removePrefix("vless://")
            trimmed.startsWith("trojan://") -> trimmed.removePrefix("trojan://")
            else -> return VlessResult()
        }

        var main = body
        var name = ""
        val hashIdx = body.indexOf('#')
        if (hashIdx != -1) {
            name = urlDecode(body.substring(hashIdx + 1))
            main = body.substring(0, hashIdx)
        }

        val atIdx = main.indexOf('@')
        if (atIdx == -1) return VlessResult()
        val uuid = main.substring(0, atIdx)
        val hostPart = main.substring(atIdx + 1)
        val (host, port) = splitHostPort(hostPart, 443)

        return VlessResult(ok = true, uuid = uuid, host = host, port = port, sni = host, name = name)
    }

    private fun splitHostPort(addr: String, defaultPort: Int): Pair<String, Int> {
        val lastColon = addr.lastIndexOf(':')
        if (lastColon == -1) return Pair(addr, defaultPort)
        val host = addr.substring(0, lastColon).trim('[', ']')
        val port = addr.substring(lastColon + 1).toIntOrNull() ?: defaultPort
        return Pair(host, port)
    }

    private fun urlEncode(s: String): String = try { URLEncoder.encode(s, "UTF-8").replace("+", "%20") } catch (_: Exception) { s }
    private fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}

data class VlessResult(
    val ok: Boolean = false,
    val uuid: String = "",
    val host: String = "",
    val port: Int = 443,
    val sni: String = "",
    val name: String = ""
)
