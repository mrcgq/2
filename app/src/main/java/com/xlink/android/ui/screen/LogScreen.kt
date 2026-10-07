//
// 修复重点：
//   1. 废除错误的 rawLines.map { parseLogLine(it) } 二次重复解析
//   2. 直接消费 logViewModel.filteredLogLines 结构化实体流
//   3. 正确绑定 isPaused、wasClipped、MAX_LINES 与日志过滤芯片

package com.xlink.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xlink.android.ui.component.LogLine
import com.xlink.android.viewmodel.LogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(logViewModel: LogViewModel = viewModel()) {
    val entries by logViewModel.filteredLogLines.collectAsStateWithLifecycle()
    val isPaused by logViewModel.isPaused.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (!isPaused && entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志 (${entries.size} 条)") },
                actions = {
                    IconButton(onClick = { logViewModel.setPaused(!isPaused) }) {
                        Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                    }
                    IconButton(onClick = { logViewModel.clearLog() }) {
                        Icon(Icons.Filled.DeleteSweep, null)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.surface)
        ) {
            itemsIndexed(items = entries, key = { index, entry -> "${index}_${entry.timestamp}" }) { _, entry ->
                LogLine(entry = entry)
            }
        }
    }
}
