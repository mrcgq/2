import os

XML_FILES = {
    "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".XlinkApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.XlinkAndroid"
        android:networkSecurityConfig="@xml/network_security_config"
        tools:targetApi="35">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:windowSoftInputMode="adjustResize"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".vpn.XlinkVpnService"
            android:exported="false"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:foregroundServiceType="specialUse">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <meta-data
                android:name="android.net.VpnService.specialUseDescription"
                android:value="@string/vpn_special_use_description" />
        </service>

        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="false"
            android:enabled="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".receiver.VpnControlReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="com.xlink.android.ACTION_START_VPN" />
                <action android:name="com.xlink.android.ACTION_STOP_VPN" />
            </intent-filter>
        </receiver>

    </application>
</manifest>""",

    "app/src/main/res/values/strings.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 应用基础 -->
    <string name="app_name">Xlink 客户端</string>
    <string name="app_version">v14.2 (Odyssey)</string>

    <!-- VPN 清单与特殊用途声明 (Android 14+ 强制) -->
    <string name="vpn_special_use_description">通过基于 Cloudflare 边缘计算的加密代理隧道保护设备网络流量</string>

    <!-- VPN 前台通知 -->
    <string name="notification_channel_vpn_name">VPN 服务</string>
    <string name="notification_channel_vpn_desc">Xlink VPN 正在运行的前台保活通知</string>
    <string name="notification_title_running">Xlink 正在运行</string>
    <string name="notification_action_stop">断开连接</string>

    <!-- 兼容别名 (防旧代码引用断裂) -->
    <string name="notif_channel_id">xlink_vpn_channel</string>
    <string name="notif_channel_name">VPN 服务</string>
    <string name="notif_channel_desc">Xlink VPN 正在运行的前台保活通知</string>
    <string name="notif_title">Xlink 运行中</string>
    <string name="notif_action_stop">断开</string>

    <!-- 表单字段标签 -->
    <string name="label_listen">本地监听</string>
    <string name="label_server">域名池</string>
    <string name="label_ip">指定 IP</string>
    <string name="label_token">Token</string>
    <string name="label_secret_key">Key</string>
    <string name="label_fallback_ip">回源 IP</string>
    <string name="label_sub_url">订阅地址</string>
    <string name="label_routing_mode">路由模式</string>
    <string name="label_strategy">负载策略</string>
    <string name="label_rules">分流规则</string>

    <!-- 模式选项 -->
    <string name="routing_global">全局代理 (经由 Xlink)</string>
    <string name="routing_smart">智能分流 (国内直连，国外走 Xlink)</string>

    <string name="strategy_random">[Random] 混沌模式 (推荐)</string>
    <string name="strategy_rr">[RR] 加特林模式 (轮询)</string>
    <string name="strategy_hash">[Hash] 狙击模式 (会话保持)</string>

    <!-- 对话框文案 -->
    <string name="dialog_rename_title">重命名节点</string>
    <string name="dialog_rename_hint">请输入新的节点别名</string>
    <string name="dialog_delete_confirm_title">确认删除</string>
    <string name="dialog_delete_confirm_msg">确定要删除选中的节点吗？此操作不可撤销。</string>
    <string name="dialog_batch_sni_title">批量修改 SNI</string>
    <string name="dialog_batch_sni_hint">请输入新的 SNI 域名 (如 cdn.worker.dev)</string>

    <!-- 提示与报错信息 -->
    <string name="err_node_running">请先停止正在运行的节点再执行操作</string>
    <string name="err_import_invalid">剪贴板内容不是有效的 xlink:// 节点配置</string>
    <string name="msg_import_success">节点导入成功！</string>
    <string name="msg_export_success">配置已成功导出至剪贴板！</string>

    <!-- 设置界面 -->
    <string name="settings_title">系统设置</string>
    <string name="settings_autostart">开机自启 (当前主用节点)</string>
    <string name="settings_about">关于 Xlink</string>
    <string name="settings_about_msg">Xlink Odyssey Android 客户端\\n版本：%1$s\\n\\n专为 Cloudflare Worker + WebSocket 量身定制的隐私加速工具。</string>

    <!-- 导航与通用 -->
    <string name="nav_home">节点</string>
    <string name="nav_log">日志</string>
    <string name="nav_settings">设置</string>
</resources>""",

    "app/src/main/res/values/colors.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 品牌色 -->
    <color name="xlink_primary">#1A73E8</color>
    <color name="xlink_primary_dark">#1557B0</color>
    <color name="xlink_primary_container">#D3E3FD</color>
    <color name="xlink_secondary">#00897B</color>
    <color name="xlink_secondary_dark">#005F56</color>

    <!-- 状态色 -->
    <color name="status_running">#2E7D32</color>
    <color name="status_stopped">#757575</color>
    <color name="status_starting">#F57C00</color>
    <color name="status_error">#C62828</color>

    <!-- 日志分级色 -->
    <color name="log_error">#EF5350</color>
    <color name="log_success">#66BB6A</color>
    <color name="log_system">#90A4AE</color>
    <color name="log_rule">#42A5F5</color>
    <color name="log_lb">#AB47BC</color>
    <color name="log_direct">#26A69A</color>
    <color name="log_default">#B0BEC5</color>

    <!-- 背景 -->
    <color name="surface">#FAFAFA</color>
    <color name="surface_variant">#F1F3F4</color>
    <color name="surface_dark">#1C1B1F</color>
    <color name="surface_variant_dark">#2B2930</color>

    <!-- 通用 -->
    <color name="white">#FFFFFF</color>
    <color name="black">#000000</color>
    <color name="divider">#E0E0E0</color>
    <color name="divider_dark">#3A3A3A</color>
</resources>""",

    "app/src/main/res/values/themes.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.XlinkAndroid" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/xlink_primary</item>
        <item name="colorPrimaryDark">@color/xlink_primary_dark</item>
        <item name="colorSecondary">@color/xlink_secondary</item>
        <item name="android:statusBarColor">@color/xlink_primary_dark</item>
        <item name="android:navigationBarColor">@color/surface</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>

    <style name="Theme.XlinkAndroid.Splash" parent="Theme.XlinkAndroid">
        <item name="android:windowBackground">@color/xlink_primary</item>
        <item name="android:windowFullscreen">false</item>
    </style>
</resources>""",

    "app/src/main/res/drawable/ic_launcher_background.xml": """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:type="linear"
        android:startColor="#1A73E8"
        android:endColor="#0D47A1"
        android:angle="135" />
</shape>""",

    "app/src/main/res/drawable/ic_launcher_foreground.xml": """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,20 C54,20 30,30 30,54 C30,68 40,78 54,84 C68,78 78,68 78,54 C78,30 54,20 Z" />

    <path
        android:fillColor="#1A73E8"
        android:strokeColor="#1A73E8"
        android:strokeWidth="2"
        android:pathData="M44,44 L64,64 M64,44 L44,64" />

    <path
        android:fillColor="@android:color/transparent"
        android:strokeColor="#1A73E8"
        android:strokeWidth="3"
        android:pathData="M42,54 A6,6 0 0 1 42,54" />
</vector>""",

    "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml": """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>""",

    "app/src/main/res/xml/backup_rules.xml": """<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="file" path="." />
    <exclude domain="external" path="." />
</full-backup-content>""",

    "app/src/main/res/xml/data_extraction_rules.xml": """<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" />
        <exclude domain="sharedpref" />
    </device-transfer>
</data-extraction-rules>""",

    "app/src/main/res/xml/network_security_config.xml": """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>

    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>"""
}

for path, content in XML_FILES.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content.strip() + "\n")
    print(f"✔ 成功还原: {path}")

print("\n✨ 全部 10 个 XML 配置文件已完全修复恢复！")