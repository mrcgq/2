package com.xlink.android.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.ui.component.InputDialog
import com.xlink.android.util.ClipboardUtil
import com.xlink.android.util.UriParser
import com.xlink.android.viewmodel.NodeViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditScreen(nodeViewModel: NodeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentIndex by nodeViewModel.currentIndex.collectAsStateWithLifecycle()
    val nodes by nodeViewModel.nodes.collectAsStateWithLifecycle()
    val node = nodes.getOrNull(currentIndex)

    if (node == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var nameField by remember(node.id) { mutableStateOf(node.name) }
    var listenField by remember(node.id) { mutableStateOf(node.listen) }
    var serverField by remember(node.id) { mutableStateOf(node.server) }
    var ipField by remember(node.id) { mutableStateOf(node.ip) }
    var tokenField by remember(node.id) { mutableStateOf(node.token) }
    var secretKeyField by remember(node.id) { mutableStateOf(node.secretKey) }
    var fallbackIpField by remember(node.id) { mutableStateOf(node.fallbackIp) }
    var subUrlField by remember(node.id) { mutableStateOf(node.subUrl) }
    var routingModeField by remember(node.id) { mutableIntStateOf(node.routingMode) }
    var strategyModeField by remember(node.id) { mutableIntStateOf(node.strategyMode) }
    var rulesField by remember(node.id) { mutableStateOf(node.rules) }

    var showBatchSniDialog by remember { mutableStateOf(false) }

    // 输入改动时防抖自动保存
    LaunchedEffect(
        nameField, listenField, serverField, ipField, tokenField,
        secretKeyField, fallbackIpField, subUrlField, routingModeField, strategyModeField, rulesField
    ) {
        delay(400L)
        nodeViewModel.updateNode(currentIndex) {
            it.copy(
                name = nameField.trim(),
                listen = listenField.trim(),
                server = serverField.trim(),
                ip = ipField.trim(),
                token = tokenField.trim(),
                secretKey = secretKeyField.trim(),
                fallbackIp = fallbackIpField.trim(),
                subUrl = subUrlField.trim(),
                routingMode = routingModeField,
                strategyMode = strategyModeField,
                rules = rulesField.trim()
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                title = { Text(node.name.ifBlank { "配置节点" }, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            val uri = UriParser.serialize(node)
                            ClipboardUtil.copy(context, uri)
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "导出配置")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 节点别名
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                label = { Text("节点别名") },
                modifier = Modifier.fillMaxWidth()
            )

            // 本地监听
            OutlinedTextField(
                value = listenField,
                onValueChange = { listenField = it },
                label = { Text("本地监听地址") },
                placeholder = { Text("127.0.0.1:10808") },
                modifier = Modifier.fillMaxWidth()
            )

            // 域名池 + 批量改 SNI 按钮
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("域名池 (每行一个，支持 SNI#IP:Port)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    FilledTonalButton(
                        onClick = { showBatchSniDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("批量改SNI", fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = serverField,
                    onValueChange = { serverField = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("cdn.worker.dev:443\n或: my.sni.com#104.16.1.1:443") }
                )
            }

            // 指定 IP（优选 IP）
            OutlinedTextField(
                value = ipField,
                onValueChange = { ipField = it },
                label = { Text("指定 IP (Cloudflare 优选 IP / 反代 IP)") },
                placeholder = { Text("如: 104.16.88.88 或 xxli.sohasoha.top") },
                modifier = Modifier.fillMaxWidth()
            )

            // Token
            OutlinedTextField(
                value = tokenField,
                onValueChange = { tokenField = it },
                label = { Text("Token (协议认证 / UUID)") },
                modifier = Modifier.fillMaxWidth()
            )

            // Key (Secret Key)
            OutlinedTextField(
                value = secretKeyField,
                onValueChange = { secretKeyField = it },
                label = { Text("Key (Worker 鉴权密钥)") },
                modifier = Modifier.fillMaxWidth()
            )

            // 回源 IP
            OutlinedTextField(
                value = fallbackIpField,
                onValueChange = { fallbackIpField = it },
                label = { Text("回源 IP (pyip / Fallback IP)") },
                placeholder = { Text("如: [2602:fc59:11:64::6812:2c00]") },
                modifier = Modifier.fillMaxWidth()
            )

            // 路由模式
            Text("路由模式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = routingModeField == NodeConfig.ROUTING_GLOBAL,
                    onClick = { routingModeField = NodeConfig.ROUTING_GLOBAL },
                    label = { Text("全局代理 (经由 Xlink)") }
                )
                FilterChip(
                    selected = routingModeField == NodeConfig.ROUTING_SMART,
                    onClick = { routingModeField = NodeConfig.ROUTING_SMART },
                    label = { Text("智能分流") }
                )
            }

            // 负载策略
            Text("负载策略", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = strategyModeField == NodeConfig.STRATEGY_RANDOM,
                    onClick = { strategyModeField = NodeConfig.STRATEGY_RANDOM },
                    label = { Text("[Random] 混沌") }
                )
                FilterChip(
                    selected = strategyModeField == NodeConfig.STRATEGY_RR,
                    onClick = { strategyModeField = NodeConfig.STRATEGY_RR },
                    label = { Text("[RR] 轮询") }
                )
                FilterChip(
                    selected = strategyModeField == NodeConfig.STRATEGY_HASH,
                    onClick = { strategyModeField = NodeConfig.STRATEGY_HASH },
                    label = { Text("[Hash] 狙击") }
                )
            }

            // 分流规则
            OutlinedTextField(
                value = rulesField,
                onValueChange = { rulesField = it },
                label = { Text("分流规则 (格式: 关键词,节点域名)") },
                placeholder = { Text("# 示例:\n# youtube.com,cdn.worker.dev:443\n# google.com,worker2.dev:443") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6
            )

            // 导出配置按钮
            Button(
                onClick = {
                    val uri = UriParser.serialize(node)
                    ClipboardUtil.copy(context, uri)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("导出配置到剪贴板")
            }
        }
    }

    // 批量改 SNI 对话框
    if (showBatchSniDialog) {
        InputDialog(
            title = "批量修改 SNI",
            prompt = "请输入新的 SNI 域名 (将替换域名池中所有行的 SNI 部分)：",
            placeholder = "cdn.worker.dev",
            onConfirm = { newSni ->
                serverField = nodeViewModel.batchReplaceSni(serverField, newSni)
                showBatchSniDialog = false
            },
            onDismiss = { showBatchSniDialog = false }
        )
    }
}