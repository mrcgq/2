package com.xlink.android.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xlink.android.R
import com.xlink.android.ui.component.*
import com.xlink.android.ui.theme.XlinkColors
import com.xlink.android.viewmodel.NodeViewModel
import com.xlink.android.vpn.VpnState
import com.xlink.android.vpn.VpnStateHolder
import com.xlink.android.vpn.XlinkVpnService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    nodeViewModel: NodeViewModel = viewModel(),
    onRequestVpnPermission: (() -> Unit) -> Unit,
    onNavigateToEdit: () -> Unit,
) {
    val context = LocalContext.current
    val nodes by nodeViewModel.nodes.collectAsStateWithLifecycle()
    val currentIndex by nodeViewModel.currentIndex.collectAsStateWithLifecycle()
    val vpnState by VpnStateHolder.vpnState.collectAsStateWithLifecycle()
    val latencies by nodeViewModel.nodeLatencies.collectAsStateWithLifecycle()

    val runningNode = nodes.firstOrNull { it.isRunning }
    val isAnyRunning = runningNode != null

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetIndex by remember { mutableIntStateOf(-1) }
    var showAddMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Xlink 控制台", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (isAnyRunning) "已连接：${runningNode?.name}" else stringResource(R.string.app_version),
                            fontSize = 11.sp,
                            color = if (isAnyRunning) XlinkColors.StatusRunning else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                actions = {
                    // 顶部一键全员测速
                    IconButton(onClick = { nodeViewModel.pingAllNodes() }) {
                        Icon(Icons.Filled.Bolt, contentDescription = "全部测速", tint = MaterialTheme.colorScheme.primary)
                    }

                    // 主开关
                    IconButton(
                        onClick = {
                            if (isAnyRunning) {
                                val intent = Intent(context, XlinkVpnService::class.java).apply { action = XlinkVpnService.ACTION_STOP }
                                context.startService(intent)
                            } else {
                                if (currentIndex in nodes.indices) {
                                    onRequestVpnPermission {
                                        val intent = Intent(context, XlinkVpnService::class.java).apply {
                                            action = XlinkVpnService.ACTION_START_NODE
                                            putExtra(XlinkVpnService.EXTRA_NODE_ID, nodes[currentIndex].id)
                                        }
                                        context.startForegroundService(intent)
                                    }
                                }
                            }
                        },
                        enabled = nodes.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = if (isAnyRunning) XlinkColors.StatusRunning else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加节点")
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("从剪贴板导入节点") },
                        leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                        onClick = {
                            showAddMenu = false
                            nodeViewModel.importFromClipboard(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("手动新建空白节点") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = {
                            showAddMenu = false
                            nodeViewModel.addNewNode()
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val vpnDisplayState = when (vpnState) {
                VpnState.RUNNING -> VpnDisplayState.RUNNING
                VpnState.STARTING -> VpnDisplayState.STARTING
                VpnState.ERROR -> VpnDisplayState.ERROR
                else -> VpnDisplayState.STOPPED
            }
            VpnStatusBanner(state = vpnDisplayState, nodeCount = if (isAnyRunning) 1 else 0)

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items = nodes, key = { _, node -> node.id }) { index, node ->
                    NodeCard(
                        node = node,
                        isSelected = (index == currentIndex),
                        latencyMs = latencies[node.id],
                        onClick = { nodeViewModel.switchNode(index) },
                        onDoubleClick = { nodeViewModel.switchNode(index); onNavigateToEdit() },
                        onStart = {
                            onRequestVpnPermission {
                                val intent = Intent(context, XlinkVpnService::class.java).apply {
                                    action = XlinkVpnService.ACTION_START_NODE
                                    putExtra(XlinkVpnService.EXTRA_NODE_ID, node.id)
                                }
                                context.startForegroundService(intent)
                            }
                        },
                        onStop = {
                            val intent = Intent(context, XlinkVpnService::class.java).apply { action = XlinkVpnService.ACTION_STOP }
                            context.startService(intent)
                        },
                        onEdit = { nodeViewModel.switchNode(index); onNavigateToEdit() },
                        onRename = { renameTargetIndex = index; showRenameDialog = true },
                        onDelete = { nodeViewModel.deleteNode(index) },
                        onClone = { nodeViewModel.cloneNode(index) },
                        onExport = { nodeViewModel.exportNode(context, node) },
                        onPing = { nodeViewModel.pingNode(node) }
                    )
                }
            }
        }
    }

    if (showRenameDialog && renameTargetIndex in nodes.indices) {
        InputDialog(
            title = "重命名节点",
            prompt = "请输入新节点别名",
            initialValue = nodes[renameTargetIndex].name,
            onConfirm = { newName ->
                nodeViewModel.renameNode(renameTargetIndex, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}