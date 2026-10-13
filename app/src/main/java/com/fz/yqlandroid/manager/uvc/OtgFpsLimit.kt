package com.fz.yqlandroid.manager.uvc

import android.content.Context
import android.util.Log

/**
 * §127 OTG 最高帧率：总后台「App配置」的全局值（system_config `otg.max.fps`），登录响应 `otgMaxFps` 下发。
 *
 * 采集请求 fps、推送 fps（PC otg_fps / 后端 set_fps / 自适应升帧）、码率上限公式里的 fps 都钳在它以下；
 * 0 = 不限制。登录时落盘，冷启动（永久 token 自动登录不经登录页）读上次的值。
 */
object OtgFpsLimit {

    private const val PREFS = "token_prefs"
    private const val KEY = "otg_max_fps"

    @Volatile var maxFps: Int = 0
        private set

    fun saveFromLogin(context: Context, value: Int?) {
        maxFps = (value ?: 0).coerceIn(0, 1000)
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, maxFps).apply()
        } catch (_: Exception) {}
        Log.d("meidui", "🔌 [OTG最高帧率] 登录下发 ${if (maxFps > 0) "${maxFps}fps" else "不限"}")
    }

    fun load(context: Context) {
        maxFps = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
        } catch (_: Exception) { 0 }
    }

    fun clamp(fps: Int): Int = if (maxFps > 0) minOf(fps, maxFps) else fps
}
