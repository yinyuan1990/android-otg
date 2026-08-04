package com.fz.yqlandroid.manager

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * 🌡️ 设备热状态监听（对标 iOS `ProcessInfo.processInfo.thermalState`）
 *
 * iOS 端会在 SoC 升温时根据 thermalState(nominal/fair/serious/critical) 主动降帧降码，
 * Android 之前完全没有热控，长时间高分辨率/高帧推流会持续升温、触发系统级降频甚至掉流。
 *
 * 本类基于 `PowerManager` 热状态 API（Android 10 / API 29+）：
 *   - addThermalStatusListener：系统上报 THERMAL_STATUS_* 变化
 *   - 归一化为 4 档 Level，回调给 WebRTCManager 做降帧/降码/降档
 * API < 29 的设备无系统热状态接口，降级为“不监听”（不影响推流）。
 */
class ThermalManager(context: Context) {

    /** 归一化热档位（与 iOS thermalState 对齐） */
    enum class Level { NOMINAL, FAIR, SERIOUS, CRITICAL }

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** 热档位变化回调（主线程/系统线程触发） */
    var onLevelChanged: ((Level) -> Unit)? = null

    var currentLevel: Level = Level.NOMINAL
        private set

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "⚠️ API<29 无系统热状态接口，跳过热控监听")
            return
        }
        if (listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { status -> handle(status) }
        listener = l
        try {
            powerManager.addThermalStatusListener(l)
            handle(powerManager.currentThermalStatus)   // 立即同步一次当前状态
            Log.d(TAG, "🌡️ 热状态监听已启动")
        } catch (e: Exception) {
            Log.e(TAG, "热状态监听启动失败: ${e.message}")
            listener = null
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { listener?.let { powerManager.removeThermalStatusListener(it) } } catch (_: Exception) {}
        }
        listener = null
        currentLevel = Level.NOMINAL
    }

    private fun handle(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> Level.NOMINAL
            PowerManager.THERMAL_STATUS_MODERATE -> Level.FAIR
            PowerManager.THERMAL_STATUS_SEVERE -> Level.SERIOUS
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> Level.CRITICAL
            else -> Level.NOMINAL
        }
        if (level != currentLevel) {
            currentLevel = level
            Log.d(TAG, "🌡️ 热状态变化 → $level (rawStatus=$status)")
            onLevelChanged?.invoke(level)
        }
    }

    companion object { private const val TAG = "ThermalManager" }
}
