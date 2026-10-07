package com.xlink.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlink.android.data.model.NodeConfig
import com.xlink.android.ui.theme.XlinkColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeCard(
    node: NodeConfig,
    isSelected: Boolean,
    latencyMs: Long? = null,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClone: () -> Unit,
    onExport: () -> Unit,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) XlinkColors.Primary else Color.Transparent,
        animationSpec = tween(180),
        label = "border"
    )
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) XlinkColors.Primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(180),
        label = "bg"
    )

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(width = if (isSelected) 1.5.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = { showContextMenu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name.ifBlank { "未命名节点" },
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = node.listen.ifBlank { "未配置监听地址" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 轻量安全测速胶囊
            LatencyBadge(latencyMs = latencyMs, onClick = onPing)

            // 运行状态徽章
            NodeStatusBadge(state = if (node.isRunning) NodeDisplayState.RUNNING else NodeDisplayState.STOPPED)

            // 启停按钮
            if (node.isRunning) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止", tint = XlinkColors.StatusError, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onStart, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "启动", tint = XlinkColors.StatusRunning, modifier = Modifier.size(18.dp))
                }
            }

            // 更多操作
            IconButton(onClick = { showContextMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }, offset = DpOffset(0.dp, 4.dp)) {
            DropdownMenuItem(text = { Text("编辑配置") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { showContextMenu = false; onEdit() })
            DropdownMenuItem(text = { Text("复制/分享链接") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { showContextMenu = false; onExport() })
            DropdownMenuItem(text = { Text("真实延迟测速") }, leadingIcon = { Icon(Icons.Filled.Bolt, null) }, onClick = { showContextMenu = false; onPing() })
            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { showContextMenu = false; onRename() })
            DropdownMenuItem(text = { Text("克隆副本") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { showContextMenu = false; onClone() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("删除", color = XlinkColors.StatusError) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = XlinkColors.StatusError) }, onClick = { showContextMenu = false; onDelete() })
        }
    }
}

@Composable
fun LatencyBadge(latencyMs: Long?, onClick: () -> Unit) {
    val (text, color) = when {
        latencyMs == null -> "⚡测速" to MaterialTheme.colorScheme.primary
        latencyMs < 0 -> "超时" to XlinkColors.StatusError
        latencyMs < 300 -> "${latencyMs}ms" to XlinkColors.StatusRunning
        latencyMs < 600 -> "${latencyMs}ms" to XlinkColors.StatusStarting
        else -> "${latencyMs}ms" to XlinkColors.StatusError
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
