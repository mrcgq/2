package com.xlink.android.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xlink.android.R
import com.xlink.android.util.AppFilterManager
import com.xlink.android.util.AutoStartManager
import com.xlink.android.util.InstalledAppItem
import com.xlink.android.vpn.VpnStateHolder
import com.xlink.android.vpn.XlinkVpnService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRequestVpnPermission: (() -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var autoStartEnabled by remember { mutableStateOf(false) }
    var perAppProxyEnabled by remember { mutableStateOf(false) }
    var selectedAppsCount by remember { mutableIntStateOf(0) }
    var isBatteryOptimizedIgnored by remember { mutableStateOf(false) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    fun refreshBatteryOptimizationState() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isBatteryOptimizedIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    // 监听应用返回前台生命周期，自动刷新电池优化状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshBatteryOptimizationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        autoStartEnabled = AutoStartManager.isEnabled(context)
        perAppProxyEnabled = AppFilterManager.isEnabled(context)
        selectedAppsCount = AppFilterManager.getSelectedApps(context).size
        refreshBatteryOptimizationState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(scrollState)
        ) {
            SettingsSectionHeader("分流与连接行为")

            // 分应用代理开关
            SettingsSwitchItem(
                icon = Icons.Filled.Apps,
                title = "分应用代理 (推荐)",
                subtitle = if (perAppProxyEnabled) "已开启 (仅代理选中的 $selectedAppsCount 个应用，其余国内App直连)" else "未开启 (全局所有应用走代理)",
                checked = perAppProxyEnabled,
                onCheckedChange = { enabled ->
                    AppFilterManager.setEnabled(context, enabled)
                    perAppProxyEnabled = enabled

                    if (VpnStateHolder.isAnyRunning()) {
                        val restartIntent = Intent(context, XlinkVpnService::class.java).apply {
                            action = XlinkVpnService.ACTION_RESTART_TUN
                        }
                        context.startService(restartIntent)
                        Toast.makeText(context, "规则已更新，VPN 网卡已自动热重载！", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            if (perAppProxyEnabled) {
                SettingsClickItem(
                    icon = Icons.Filled.Checklist,
                    title = "选择代理应用",
                    subtitle = "点击选择仅允许哪些海外 App 走代理 ($selectedAppsCount 已选)",
                    onClick = { showAppPicker = true }
                )
            }

            // 开机自启
            SettingsSwitchItem(
                icon = Icons.Filled.Autorenew,
                title = stringResource(R.string.settings_autostart),
                subtitle = "设备重启后自动启动 VPN 连接",
                checked = autoStartEnabled,
                onCheckedChange = { enabled ->
                    if (AutoStartManager.setEnabled(context, enabled)) {
                        autoStartEnabled = enabled
                    }
                }
            )

            // 第三项：后台保活（忽略电池优化引导）
            SettingsClickItem(
                icon = Icons.Filled.BatteryChargingFull,
                title = "后台保活 (忽略电池优化)",
                subtitle = if (isBatteryOptimizedIgnored) "已加入白名单 (锁屏息屏后防系统休眠断连)" else "未加入白名单 (点击前往授权，防止后台被杀)",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(fallbackIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开系统设置: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "当前系统版本无需配置电池优化", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionHeader("VPN 与系统设置")

            SettingsClickItem(
                icon = Icons.Filled.VpnLock,
                title = "系统 VPN 设置 (物理防漏锁)",
                subtitle = "前往配置「始终开启 VPN」和「阻止非 VPN 连接」",
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionHeader("关于")

            SettingsClickItem(
                icon = Icons.Filled.Info,
                title = "关于 Xlink Odyssey",
                subtitle = "版本 v14.2 (纯 C 核心暴瘦版)",
                onClick = { showAbout = true }
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            onDismiss = {
                showAppPicker = false
                selectedAppsCount = AppFilterManager.getSelectedApps(context).size

                if (VpnStateHolder.isAnyRunning()) {
                    val restartIntent = Intent(context, XlinkVpnService::class.java).apply {
                        action = XlinkVpnService.ACTION_RESTART_TUN
                    }
                    context.startService(restartIntent)
                    Toast.makeText(context, "代理应用列表已更新，VPN 网卡已热重载！", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { Icon(Icons.Filled.VpnKey, null) },
            title = { Text("关于 Xlink Odyssey") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Xlink Odyssey Android 豪华增强版")
                    Text("• 核心: hev-socks5-tunnel (纯 C 内存栈)")
                    Text("• 传输: WebSocket over TLS + Nano Header v2")
                    Text("• 架构: MapDNS (0ms FakeDNS) + 自愈重连")
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("确定") } }
        )
    }
}

@Composable
fun AppPickerDialog(context: Context, onDismiss: () -> Unit) {
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        apps = AppFilterManager.getInstalledAppList(context)
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("选择需要代理的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("未勾选的应用 (如微信、淘宝、银行) 将全部走国内高速直连", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索应用名称或包名...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = apps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newSelected = !app.isSelected
                                        apps = apps.map { if (it.packageName == app.packageName) it.copy(isSelected = newSelected) else it }
                                        val selectedSet = apps.filter { it.isSelected }.map { it.packageName }.toSet()
                                        AppFilterManager.setSelectedApps(context, selectedSet)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                Checkbox(
                                    checked = app.isSelected,
                                    onCheckedChange = { checked ->
                                        apps = apps.map { if (it.packageName == app.packageName) it.copy(isSelected = checked) else it }
                                        val selectedSet = apps.filter { it.isSelected }.map { it.packageName }.toSet()
                                        AppFilterManager.setSelectedApps(context, selectedSet)
                                    }
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("完成保存") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp, end = 16.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsSwitchItem(icon: ImageVector, title: String, subtitle: String = "", checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = if (subtitle.isNotEmpty()) { { Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) } } else null,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun SettingsClickItem(icon: ImageVector, title: String, subtitle: String = "", onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = if (subtitle.isNotEmpty()) { { Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) } } else null,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
