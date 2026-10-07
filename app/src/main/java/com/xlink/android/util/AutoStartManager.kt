//
// C 版：写 HKCU\...\Run 注册表键
// Android 版：通过 RECEIVE_BOOT_COMPLETED 广播 + BootReceiver 实现
// 开关状态持久化到 SharedPreferences（DataStore 初始化前可用）

package com.xlink.android.util

import android.content.Context
import android.content.pm.PackageManager

object AutoStartManager {

    private const val PREF_NAME    = "xlink_autostart"
    private const val KEY_ENABLED  = "autostart_enabled"

    /**
     * 读取开机自启开关状态。
     * 对应 C 版 IsAutoStartEnabled()：查注册表 XLinkClient 键是否存在。
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    /**
     * 设置开机自启。
     * 对应 C 版 SetAutoStart(BOOL b)：写/删注册表 Run 键。
     *
     * Android 实现：
     * - 启用：将 BootReceiver 设为 ENABLED，写 SharedPreferences
     * - 禁用：将 BootReceiver 设为 DISABLED，清 SharedPreferences
     *
     * 返回 true 表示操作成功。
     * 注意：部分厂商 ROM（MIUI/EMUI/ColorOS）需要在系统"自启动管理"中额外授权，
     * 这里无法自动处理，返回 true 后如自启失败属厂商限制。
     */
    fun setEnabled(context: Context, enable: Boolean): Boolean {
        return try {
            val pm        = context.packageManager
            val component = android.content.ComponentName(
                context,
                "com.xlink.android.receiver.BootReceiver"
            )
            pm.setComponentEnabledSetting(
                component,
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enable)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }
}
