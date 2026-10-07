// 对应 C 版 hNodeListView 每行：别名列 + 状态列 + 右键操作菜单。

package com.xlink.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * 节点卡片。
 *
 * @param node          节点数据
 * @param isSelected    是否为当前选中节点（对应 C 版 g_currentNodeIndex）
 * @param onClick       单击：选中节点
 * @param onDoubleClick 双击：进入编辑界面
 * @param onStart       启动当前节点（对应 ID_START_SELECTED）
 * @param onStop        停止当前节点（对应 ID_STOP_SELECTED）
 * @param onEdit        编辑节点配置
 * @param onRename      重命名（对应 ID_NODE_RENAME）
 * @param onDelete      删除（对应 ID_NODE_DELETE）
 * @param onClone       克隆副本（对应 AddNewNode 克隆逻辑）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeCard(
    node         : NodeConfig,
    isSelected   : Boolean,
    onClick      : () -> Unit,
    onDoubleClick: () -> Unit,
    onStart      : () -> Unit,
    onStop       : () -> Unit,
    onEdit       : () -> Unit,
    onRename     : () -> Unit,
    onDelete     : () -> Unit,
    onClone      : () -> Unit,
    modifier     : Modifier = Modifier,
) {
    var showContextMenu by remember { mutableStateOf(false) }

    // 选中时边框高亮，动画过渡
    val borderColor by animateColorAsState(
        targetValue  = if (isSelected) XlinkColors.Primary else Color.Transparent,
        animationSpec = tween(180),
        label        = "border"
    )
    val cardBg by animateColorAsState(
        targetValue  = if (isSelected)
            XlinkColors.Primary.copy(alpha = 0.06f)
        else
            MaterialTheme.colorScheme.surface,
        animationSpec = tween(180),
        label        = "bg"
    )

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(
                    width = if (isSelected) 1.5.dp else 0.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(10.dp)
                )
                .combinedClickable(
                    onClick      = onClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick  = { showContextMenu = true }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 节点名称（主要信息）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = node.name.ifBlank { "未命名节点" },
                    fontSize   = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // 监听地址（次要信息，小字显示）
                Text(
                    text       = node.listen.ifBlank { "未配置监听地址" },
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
            }

            // 状态徽章
            NodeStatusBadge(
                state = if (node.isRunning) NodeDisplayState.RUNNING
                        else               NodeDisplayState.STOPPED
            )

            // 启动 / 停止快捷按钮
            if (node.isRunning) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint   = XlinkColors.StatusError,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onStart,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "启动",
                        tint   = XlinkColors.StatusRunning,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 更多操作入口
            IconButton(
                onClick  = { showContextMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // 长按 / 更多 的上下文菜单
        // 对应 C 版节点列表右键：编辑/重命名/克隆/删除
        DropdownMenu(
            expanded         = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset           = DpOffset(x = 0.dp, y = 4.dp)
        ) {
            DropdownMenuItem(
                text         = { Text("编辑配置") },
                leadingIcon  = { Icon(Icons.Filled.Edit, null) },
                onClick      = { showContextMenu = false; onEdit() }
            )
            DropdownMenuItem(
                text         = { Text("重命名") },
                leadingIcon  = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                onClick      = { showContextMenu = false; onRename() }
            )
            DropdownMenuItem(
                text         = { Text("克隆副本") },
                leadingIcon  = { Icon(Icons.Filled.ContentCopy, null) },
                onClick      = { showContextMenu = false; onClone() }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text         = { Text("删除", color = XlinkColors.StatusError) },
                leadingIcon  = { Icon(Icons.Filled.Delete, null, tint = XlinkColors.StatusError) },
                onClick      = { showContextMenu = false; onDelete() }
            )
        }
    }
}
