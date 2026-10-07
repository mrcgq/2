//
// 对应 C 版：
// - ID_AUTOSTART_CHECK → autostart 开关（用 Android 开机广播替代注册表 Run 键）
// - APP_VERSION        → 关于信息显示
// - UpdateAutoStartCheckbox() → LaunchedEffect 读取当前开机自启状态

package com.xlink.android.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlink.android.R
import com.xlink.android.util.AutoStartManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRequestVpnPermission: (() -> Unit) -> Unit = {}
) {
    val context     = LocalContext.current
    val scrollState = rememberScrollState()

    // 开机自启状态（对应 C 版 g_autoStartEnabled + IsAutoStartEnabled()）
    var autoStartEnabled by remember { mutableStateOf(false) }
    var autoStartError   by remember { mutableStateOf("") }

    // 读取当前开机自启状态（对应 C 版 UpdateAutoStartCheckbox()）
    LaunchedEffect(Unit) {
        autoStartEnabled = AutoStartManager.isEnabled(context)
    }

    // 关于对话框
    var showAbout by remember { mutableStateOf(false) }

    // 通知权限提示（Android 13+）
    var showNotifHint by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 18.sp
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {

            // ── § 连接行为 ────────────────────────────────────
            SettingsSectionHeader("连接行为")

            // 开机自启（对应 C 版 ID_AUTOSTART_CHECK + SetAutoStart()）
            SettingsSwitchItem(
                icon        = Icons.Filled.Autorenew,
                title       = stringResource(R.string.settings_autostart),
                subtitle    = if (autoStartError.isNotEmpty()) autoStartError
                              else "设备重启后自动启动所有节点",
                checked     = autoStartEnabled,
                onCheckedChange = { enabled ->
                    val success = AutoStartManager.setEnabled(context, enabled)
                    if (success) {
                        autoStartEnabled = enabled
                        autoStartError   = ""
                    } else {
                        // 对应 C 版 "设置开机自启失败，请尝试以管理员身份运行" 提示
                        autoStartError = "设置失败，部分设备需在系统权限管理中手动允许"
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── § VPN 与代理 ──────────────────────────────────
            SettingsSectionHeader("VPN 与代理")

            // VPN 始终保持（Android 系统级设置引导）
            SettingsClickItem(
                icon     = Icons.Filled.VpnLock,
                title    = "系统 VPN 设置",
                subtitle = "在系统设置中配置「始终开启 VPN」，防止 VPN 断线后流量泄漏",
                onClick  = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_VPN_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Settings.ACTION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── § 通知 ────────────────────────────────────────
            SettingsSectionHeader("通知")

            SettingsClickItem(
                icon     = Icons.Filled.Notifications,
                title    = "通知权限",
                subtitle = if (Build.VERSION.SDK_INT >= 33)
                               "Android 13+ 需要通知权限才能显示 VPN 状态常驻通知"
                           else "点击前往应用通知设置",
                onClick  = {
                    val intent = Intent().apply {
                        action  = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── § 调试 ────────────────────────────────────────
            SettingsSectionHeader("调试")

            // 日志级别说明
            SettingsInfoItem(
                icon     = Icons.Filled.BugReport,
                title    = "日志模式",
                subtitle = "当前：详细调试模式（对应 C 版「详细调试模式」标题）\n所有核心日志实时写入「日志」标签页"
            )

            // GODEBUG 说明（对应 C 版 SetEnvironmentVariableA("GODEBUG","netdns=go")）
            SettingsInfoItem(
                icon     = Icons.Filled.Dns,
                title    = "DNS 解析器",
                subtitle = "GODEBUG=netdns=go\n强制使用纯 Go DNS 解析器，规避 CGO 解析器在部分设备上的问题"
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── § 关于 ────────────────────────────────────────
            SettingsSectionHeader("关于")

            SettingsClickItem(
                icon     = Icons.Filled.Info,
                title    = "关于 Xlink",
                subtitle = "版本 ${stringResource(R.string.app_version)}  ·  点击查看详情",
                onClick  = { showAbout = true }
            )

            SettingsClickItem(
                icon     = Icons.Filled.Code,
                title    = "协议与内核",
                subtitle = "Xlink Nano Worker v15.3 · Go 内核 v14.2 · Cloudflare Worker",
                onClick  = {}
            )

            // 占位底部留白
            Spacer(Modifier.height(32.dp))
        }
    }

    // 关于对话框
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon  = { Icon(Icons.Filled.VpnKey, null) },
            title = { Text("关于 Xlink Odyssey") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_about_msg,
                            stringResource(R.string.app_version)
                        ),
                        fontSize = 13.sp
                    )
                    HorizontalDivider()
                    Text(
                        text     = "技术栈",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    val techItems = listOf(
                        "客户端" to "Kotlin + Jetpack Compose",
                        "VPN 层" to "Android VpnService API",
                        "IP 转换" to "tun2socks (gVisor 网络栈)",
                        "Go 内核" to "gomobile .aar（v14.2）",
                        "传输层" to "WebSocket over TLS",
                        "协议" to "Nano Header v2（无 s5）",
                        "服务端" to "Cloudflare Worker v15.3",
                    )
                    techItems.forEach { (k, v) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text     = "$k：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(72.dp)
                            )
                            Text(text = v, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 设置列表子组件
// ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp, end = 16.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color    = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsSwitchItem(
    icon           : ImageVector,
    title          : String,
    subtitle       : String       = "",
    checked        : Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent   = { Text(title, fontSize = 15.sp) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        } else null,
        leadingContent    = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent   = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
private fun SettingsClickItem(
    icon    : ImageVector,
    title   : String,
    subtitle: String    = "",
    onClick : () -> Unit,
) {
    ListItem(
        headlineContent   = { Text(title, fontSize = 15.sp) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        } else null,
        leadingContent    = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent   = {
            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsInfoItem(
    icon    : ImageVector,
    title   : String,
    subtitle: String = "",
) {
    ListItem(
        headlineContent   = { Text(title, fontSize = 15.sp) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
        } else null,
        leadingContent    = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    )
}
