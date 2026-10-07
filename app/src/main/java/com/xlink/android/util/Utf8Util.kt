package com.xlink.android.util

import java.net.URLDecoder
import java.net.URLEncoder

object Utf8Util {

    fun urlEncode(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        s
    }

    fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    fun sanitizeLogLine(raw: String): String =
        raw.trimEnd('\r', '\n').replace("\u0000", "")

    fun splitLines(text: String): List<String> =
        text.replace("\r\n", "\n")
            .replace("\r", "\n")
            .split('\n')
            .filter { it.isNotBlank() }
}
