package com.xlink.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xlink.android.engine.CoreEngine
import com.xlink.android.engine.LogEntry
import com.xlink.android.engine.LogLevel
import com.xlink.android.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewModel : ViewModel() {

    companion object {
        const val MAX_LOG_LINES = 800
        const val TRIM_TARGET_LINES = 400
    }

    private val logBuffer = ArrayDeque<LogEntry>(MAX_LOG_LINES + 32)
    private val seenSignatures = LinkedHashSet<String>(MAX_LOG_LINES * 2)

    private val _logLines = MutableStateFlow<List<LogEntry>>(emptyList())
    val logLines: StateFlow<List<LogEntry>> = _logLines.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _wasClipped = MutableStateFlow(false)
    val wasClipped: StateFlow<Boolean> = _wasClipped.asStateFlow()

    val filterLevel = MutableStateFlow<LogLevel?>(null)
    val searchQuery = MutableStateFlow("")

    val filteredLogLines: StateFlow<List<LogEntry>> = combine(_logLines, filterLevel, searchQuery) { lines, level, query ->
        lines.filter { entry ->
            val matchLevel = level == null || entry.level == level
            val matchQuery = query.isBlank() || entry.message.contains(query, ignoreCase = true) || entry.nodeName.contains(query, ignoreCase = true)
            matchLevel && matchQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // 收集内核日志
        viewModelScope.launch(Dispatchers.Main) {
            CoreEngine.logFlow.collect { entry -> appendEntry(entry) }
        }

        // 同时收集 VPN 网卡与系统生命周期日志
        viewModelScope.launch(Dispatchers.Main) {
            VpnStateHolder.logEvents.collect { vpnEvent ->
                val lvl = when (vpnEvent.level) {
                    com.xlink.android.vpn.LogLevel.ERROR -> LogLevel.FAILURE
                    com.xlink.android.vpn.LogLevel.WARNING -> LogLevel.WARN
                    com.xlink.android.vpn.LogLevel.SUCCESS -> LogLevel.SUCCESS
                    else -> LogLevel.SYSTEM
                }
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(vpnEvent.timestampMs))
                appendEntry(LogEntry(timeStr, vpnEvent.nodeName, lvl, vpnEvent.message))
            }
        }
    }

    private fun appendEntry(entry: LogEntry) {
        val signature = "${entry.timestamp}|${entry.nodeName}|${entry.level.name}|${entry.message}"
        if (!seenSignatures.add(signature)) return

        while (seenSignatures.size > MAX_LOG_LINES) {
            val iterator = seenSignatures.iterator()
            if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
        }

        if (logBuffer.size >= MAX_LOG_LINES) {
            repeat(logBuffer.size - TRIM_TARGET_LINES) { logBuffer.removeFirst() }
            _wasClipped.value = true
        }
        logBuffer.addLast(entry)
        _logLines.value = logBuffer.toList()
    }

    fun setPaused(paused: Boolean) { _isPaused.value = paused }
    fun clearLog() {
        logBuffer.clear()
        seenSignatures.clear()
        _logLines.value = emptyList()
        _wasClipped.value = false
    }
    fun filterByLevel(level: LogLevel?) { filterLevel.value = level }
    fun setSearchQuery(query: String) { searchQuery.value = query }
}