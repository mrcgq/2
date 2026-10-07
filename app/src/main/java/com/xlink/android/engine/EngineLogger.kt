//
// 修复重点：
//   1. 确立全工程唯一的 LogLevel 与 LogEntry 声明，消除与 LogLine 的类重复冲突
//   2. 规范日志级别标签与色彩映射
//   3. 提供 TranslateLog 规则翻译器作为 Go 核心日志文本的备用清洗器

package com.xlink.android.engine

import androidx.compose.ui.graphics.Color
import com.xlink.android.ui.theme.XlinkColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String, val tag: String) {
    SYSTEM("系统", "[系统]"),
    SUCCESS("成功", "[成功]"),
    FAILURE("错误", "[错误]"),
    RULE("规则", "[规则]"),
    LB("负载", "[负载]"),
    DIRECT("直连", "[直连]"),
    KERNEL("内核", "[内核]"),
    WARN("警告", "[警告]");

    val color: Color
        get() = when (this) {
            SYSTEM  -> XlinkColors.LogSystem
            SUCCESS -> XlinkColors.LogSuccess
            FAILURE -> XlinkColors.LogError
            RULE    -> XlinkColors.LogRule
            LB      -> XlinkColors.LogLb
            DIRECT  -> XlinkColors.LogDirect
            KERNEL  -> XlinkColors.LogDefault
            WARN    -> XlinkColors.LogError
        }
}

data class LogEntry(
    val timestamp: String,
    val nodeName: String,
    val level: LogLevel,
    val message: String,
) {
    fun toPlainText(): String = "[$timestamp][$nodeName][${level.label}] $message"
}
