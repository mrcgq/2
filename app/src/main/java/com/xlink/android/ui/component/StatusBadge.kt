// 对应 C 版 RefreshNodeList() 中的 "●运行中" / "○已停止" 列文本。

package com.xlink.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlink.android.ui.theme.XlinkColors

/** VPN 整体状态（对应 VpnStateHolder.VpnState） */
enum class VpnDisplayState {
    STOPPED, STARTING, RUNNING, ERROR
}

/** 单节点运行状态（对应 NodeConfig.isRunning） */
enum class NodeDisplayState {
    RUNNING, STOPPED
}

/**
 * 节点状态徽章：小圆点 + 文字，用于节点卡片右上角。
 * RUNNING → 绿色  ● 运行中
 * STOPPED → 灰色  ○ 已停止
 */
@Composable
fun NodeStatusBadge(
    state    : NodeDisplayState,
    modifier : Modifier = Modifier
) {
    val (dotColor, label) = when (state) {
        NodeDisplayState.RUNNING -> XlinkColors.StatusRunning  to "运行中"
        NodeDisplayState.STOPPED -> XlinkColors.StatusStopped  to "已停止"
    }

    Row(
        modifier         = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(dotColor.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text       = label,
            color      = dotColor,
            fontSize   = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * VPN 全局状态横幅，显示在 HomeScreen 顶部。
 * 对应 C 版 g_engineStatuses 聚合状态显示。
 */
@Composable
fun VpnStatusBanner(
    state    : VpnDisplayState,
    nodeCount: Int = 0,
    modifier : Modifier = Modifier
) {
    val (bgColor, textColor, icon, label) = when (state) {
        VpnDisplayState.RUNNING  -> Quadruple(
            XlinkColors.StatusRunning.copy(alpha = 0.1f),
            XlinkColors.StatusRunning,
            "●",
            "VPN 已连接（$nodeCount 个节点运行中）"
        )
        VpnDisplayState.STARTING -> Quadruple(
            XlinkColors.StatusStarting.copy(alpha = 0.1f),
            XlinkColors.StatusStarting,
            "◐",
            "正在连接…"
        )
        VpnDisplayState.ERROR    -> Quadruple(
            XlinkColors.StatusError.copy(alpha = 0.1f),
            XlinkColors.StatusError,
            "✕",
            "连接失败，请检查配置"
        )
        VpnDisplayState.STOPPED  -> Quadruple(
            Color(0xFFF1F3F4),
            XlinkColors.StatusStopped,
            "○",
            "VPN 未连接"
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(icon,  color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
    }
}

// 内部用四元组，避免引入 Pair 嵌套
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
