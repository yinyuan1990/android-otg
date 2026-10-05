package com.fz.yqlandroid.manager.uvc

import android.util.Log
import com.jiangdg.uvc.UVCCamera
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * ⭐ §109.3 OTG 外接 USB 摄像头「手动曝光 / 快门」桥接。
 *
 * 我们用的 `com.jiangdg.uvc.UVCCamera`（ernestp/libausbc 3.5.3，源自 saki4510t）里，曝光的
 * native 绑定其实都在：能力位 `CTRL_AE_ABS`、字段 `mExposureMin/Max/Def`、方法
 * `nativeUpdateExposureLimit/nativeSetExposure/nativeGetExposure/nativeSetExposureMode`，
 * 唯独 jiangdg 当年没把它们用 public 方法暴露出来。给 AAR 里的类补 public 方法得 fork 重编库 +
 * 走 JitPack（使用者才能编），维护成本高；这里改用反射直调这几个**已声明**的 native 方法，
 * 运行期效果与「给库加 public setExposure()」完全一致，且不动构建。
 *
 * ⭐ §115 快门映射与自动曝光：
 *  - 百分比 → 曝光时间改**对数映射**，并钳在 [0.5ms, 当前帧间隔]。原来线性铺满设备全范围
 *    （常见 0.1ms~1s），室内可用的 5~33ms 只占滑块几个百分点：拉到底必黑、稍往上就超帧间隔掉帧。
 *  - 切到手动后可以切回自动：开机时记下设备原始 AE 模式，恢复时优先回到它，失败再试
 *    光圈优先(8)、全自动(2)。原来一旦手动就只能拔插摄像头才回自动。
 *
 * 约束：所有调用必须在 uvcThread 上（与其它 nativeSetXxx 一致）。任何一步反射失败即
 * [isAvailable]=false，上层据此把「快门/曝光」控件标记为不支持（PC 面板灰化、拖不动）。
 */
object UvcExposureBridge {
    private const val TAG = "meidui"

    // UVC CT_AE_MODE 位图：D0=Manual，D1=Auto，D2=Shutter Priority，D3=Aperture Priority。
    // 手动设曝光时间前必须切到手动，否则相机自动曝光会无视手动值。
    private const val AE_MODE_MANUAL = 1
    private const val AE_MODE_AUTO = 2
    private const val AE_MODE_APERTURE_PRIORITY = 8

    /** 快门最短 0.5ms（UVC 单位 100µs）。更短的值室内必黑，且对抓拍运动已无意义。*/
    private const val MIN_EXPOSURE_UNITS = 5

    @Volatile private var resolved = false
    @Volatile private var ok = false

    private var fNativePtr: Field? = null
    private var fMin: Field? = null
    private var fMax: Field? = null
    private var mUpdateLimit: Method? = null   // 实例方法：nativeUpdateExposureLimit(long)
    private var mSet: Method? = null           // static：nativeSetExposure(long,int)
    private var mGet: Method? = null           // static：nativeGetExposure(long)
    private var mSetMode: Method? = null       // static：nativeSetExposureMode(long,int)
    private var mGetMode: Method? = null       // static：nativeGetExposureMode(long)，可缺省

    /** 开机时读到的原始 AE 模式（-1=未知），恢复自动曝光时优先回到它 */
    @Volatile private var defaultMode = -1
    /** 当前是否处于我们切进去的手动曝光 */
    @Volatile var manualActive = false
        private set

    @Synchronized
    private fun ensure() {
        if (resolved) return
        resolved = true
        try {
            val cls = UVCCamera::class.java
            val jLong = java.lang.Long.TYPE
            val jInt = Integer.TYPE
            fNativePtr = cls.getDeclaredField("mNativePtr").apply { isAccessible = true }
            fMin = cls.getDeclaredField("mExposureMin").apply { isAccessible = true }
            fMax = cls.getDeclaredField("mExposureMax").apply { isAccessible = true }
            mUpdateLimit = cls.getDeclaredMethod("nativeUpdateExposureLimit", jLong).apply { isAccessible = true }
            mSet = cls.getDeclaredMethod("nativeSetExposure", jLong, jInt).apply { isAccessible = true }
            mGet = cls.getDeclaredMethod("nativeGetExposure", jLong).apply { isAccessible = true }
            mSetMode = cls.getDeclaredMethod("nativeSetExposureMode", jLong, jInt).apply { isAccessible = true }
            mGetMode = try {
                cls.getDeclaredMethod("nativeGetExposureMode", jLong).apply { isAccessible = true }
            } catch (_: Throwable) { null }
            ok = true
            Log.d(TAG, "🔌 [OTG曝光] 反射桥接就绪（读模式=${if (mGetMode != null) "可用" else "不可用"}）")
        } catch (t: Throwable) {
            ok = false
            Log.d(TAG, "🔌 [OTG曝光] 反射桥接不可用（库签名可能变了）: ${t.message}")
        }
    }

    /** 库层面有没有可调曝光的接口（与设备是否支持 CTRL_AE_ABS 无关，那由调用方 checkSupportFlag 另判）。*/
    val isAvailable: Boolean
        get() { ensure(); return ok }

    private fun ptr(camera: UVCCamera): Long = fNativePtr!!.getLong(camera)

    /** native 更新曝光上下限（写入 mExposureMin/Max/Def），是百分比映射的前提。updateCameraParams 不含此项。*/
    private fun refreshLimit(camera: UVCCamera) {
        try { mUpdateLimit!!.invoke(camera, ptr(camera)) } catch (_: Throwable) { /* 个别设备不报，忽略 */ }
    }

    /** 读当前 AE 模式；读不到回 -1 */
    private fun readMode(camera: UVCCamera): Int = try {
        (mGetMode?.invoke(null, ptr(camera)) as? Int) ?: -1
    } catch (_: Throwable) { -1 }

    /** native 返回值：Int 且 <0 视为失败；无返回值/非 Int 视为成功 */
    private fun setMode(camera: UVCCamera, mode: Int): Boolean = try {
        val r = mSetMode!!.invoke(null, ptr(camera), mode)
        !(r is Int && r < 0)
    } catch (t: Throwable) {
        Log.d(TAG, "🔌 [OTG曝光] 切模式 $mode 失败: ${t.message}")
        false
    }

    /** 相机刚打开时调：记下原始 AE 模式，供恢复自动曝光 */
    fun onCameraOpened(camera: UVCCamera) {
        if (!isAvailable) return
        defaultMode = readMode(camera)
        manualActive = defaultMode == AE_MODE_MANUAL
        Log.d(TAG, "🔌 [OTG曝光] 原始AE模式=$defaultMode（1=手动 2=自动 4=快门优先 8=光圈优先 -1=读不到）")
    }

    /** 相机关闭时调 */
    fun onCameraClosed() {
        defaultMode = -1
        manualActive = false
    }

    /** 是否处于自动曝光（读得到模式就以设备为准，读不到按我们自己的切换记录） */
    fun isAuto(camera: UVCCamera): Boolean {
        val m = readMode(camera)
        return if (m > 0) m != AE_MODE_MANUAL else !manualActive
    }

    /** 切到手动曝光。先读出当前曝光值，切完再写回去——自动→手动的瞬间画面不跳黑。*/
    fun setManualMode(camera: UVCCamera) {
        if (!isAvailable || manualActive) return
        val cur = try { mGet!!.invoke(null, ptr(camera)) as Int } catch (_: Throwable) { -1 }
        val switched = setMode(camera, AE_MODE_MANUAL)
        manualActive = true
        if (switched && cur > 0) {
            try { mSet!!.invoke(null, ptr(camera), cur) } catch (_: Throwable) {}
        }
        Log.d(TAG, "🔌 [OTG曝光] 切手动曝光 ${if (switched) "成功" else "失败"}，锁定当前曝光 abs=$cur")
    }

    /** 恢复自动曝光：原始模式 → 光圈优先(8) → 全自动(2)，第一个成功即停 */
    fun setAutoMode(camera: UVCCamera): Boolean {
        if (!isAvailable) return false
        val candidates = linkedSetOf<Int>()
        if (defaultMode > 0 && defaultMode != AE_MODE_MANUAL) candidates += defaultMode
        candidates += AE_MODE_APERTURE_PRIORITY
        candidates += AE_MODE_AUTO
        for (m in candidates) {
            if (!setMode(camera, m)) continue
            val back = readMode(camera)
            if (back > 0 && back == AE_MODE_MANUAL) continue   // 设备没认，仍在手动
            manualActive = false
            Log.d(TAG, "🔌 [OTG曝光] 已恢复自动曝光 mode=$m（回读=$back）")
            return true
        }
        Log.d(TAG, "🔌 [OTG曝光] 恢复自动曝光失败，候选 $candidates 均不被设备接受")
        return false
    }

    /**
     * 对数映射区间 [lo, hi]（单位 100µs）：lo=max(设备下限, 0.5ms)，hi=min(设备上限, 帧间隔)。
     * 帧间隔封顶 = 曝光不会超过一帧，帧率不再掉。返回 null 表示设备范围不可用。
     */
    private fun range(camera: UVCCamera, fps: Int): Pair<Int, Int>? {
        refreshLimit(camera)
        val dMin = minOf(fMin!!.getInt(camera), fMax!!.getInt(camera))
        val dMax = maxOf(fMin!!.getInt(camera), fMax!!.getInt(camera))
        if (dMax <= 0 || dMax <= dMin) return null
        val frameUnits = 10000 / (if (fps > 0) fps else 30)
        val lo = maxOf(dMin, MIN_EXPOSURE_UNITS, 1)
        var hi = minOf(dMax, frameUnits)
        if (hi <= lo) hi = dMax          // 设备下限已超帧间隔：退回设备全范围
        if (hi <= lo) return null
        return lo to hi
    }

    /** 当前曝光 → 百分比 0~100；失败回 -1。*/
    fun getPercent(camera: UVCCamera, fps: Int): Int {
        if (!isAvailable) return -1
        return try {
            val (lo, hi) = range(camera, fps) ?: return -1
            val abs = (mGet!!.invoke(null, ptr(camera)) as Int).coerceIn(lo, hi)
            (Math.log(abs.toDouble() / lo) / Math.log(hi.toDouble() / lo) * 100).toInt().coerceIn(0, 100)
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 读取失败: ${t.message}")
            -1
        }
    }

    /** 百分比 0~100 → 绝对曝光值下发（调用方须已切手动）。*/
    fun setPercent(camera: UVCCamera, pct: Int, fps: Int): Boolean {
        if (!isAvailable) return false
        return try {
            val (lo, hi) = range(camera, fps) ?: return false
            val p = pct.coerceIn(0, 100) / 100.0
            val abs = Math.round(lo * Math.pow(hi.toDouble() / lo, p)).toInt().coerceIn(lo, hi)
            mSet!!.invoke(null, ptr(camera), abs)
            Log.d(TAG, "🔌 [OTG曝光] 已下发 ${pct}% → abs=$abs (${abs / 10.0}ms, 区间 ${lo / 10.0}~${hi / 10.0}ms, " +
                    "设备 min=${fMin!!.getInt(camera)} max=${fMax!!.getInt(camera)}, fps=$fps)")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 下发失败: ${t.message}")
            false
        }
    }
}
