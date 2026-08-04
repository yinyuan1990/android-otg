package com.fz.yqlandroid.manager

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * ⭐ 需求#3（2026-07-31）：小米/红米「切后台就断推流」的权限处理。
 *
 * 背景：后台推流的系统级标准动作已全部具备——前台服务(foregroundServiceType=camera|microphone)
 * + 无声音频保活 + WakeLock（见 KeepAliveManager / StreamingForegroundService）。普通机型切后台没问题；
 * 小米断，是 MIUI 在系统标准之上的私有管控：
 *   1. 「省电策略」默认"智能限制"——切后台十几秒后冻结进程，**前台服务也照冻**；
 *   2. 「自启动」默认关闭——MIUI 把它当后台存活豁免开关，没开则后台服务随时被杀。
 * 应对：
 *   · 能用代码弹的：ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 系统授权框（在 MIUI 上等价于
 *     把省电策略切"无限制"的关键一半）；
 *   · 代码开不了的：自启动开关——只能检测到小米机型时弹引导，一键跳 MIUI 的自启动管理页。
 */
object OemPermissionHelper {

    private const val TAG = "OemPermission"

    /** 是否小米/红米（MIUI 私有管控只在这类机型上处理，别打扰其他品牌用户） */
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("redmi", ignoreCase = true)

    /** 是否已在「忽略电池优化」白名单里 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "查询电池优化白名单失败: ${e.message}")
            true   // 查不到就当已豁免，别反复骚扰用户
        }
    }

    /** 弹系统「忽略电池优化」授权框（用户点"允许"即入白名单，一次永久） */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("meidui", "🔋 [需求#3] 已弹「忽略电池优化」系统授权框")
        } catch (e: Exception) {
            Log.e(TAG, "请求忽略电池优化失败: ${e.message}")
        }
    }

    /**
     * 跳 MIUI「自启动管理」页；MIUI 版本差异大，逐级回退：
     * 安全中心自启动页 → 通用自启动 action → 应用详情页（用户可从中进"省电策略/自启动"）。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter",
                                  "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                   Uri.parse("package:${context.packageName}"))
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("meidui", "🔋 [需求#3] 已跳转设置页: ${intent.action ?: intent.component}")
                return
            } catch (_: Exception) {
                // 该入口不存在（非 MIUI/版本差异），试下一个
            }
        }
        Log.e(TAG, "所有设置页入口均不可用")
    }
}
