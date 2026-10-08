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
    const val AE_MODE_MANUAL = 1
    private const val AE_MODE_AUTO = 2
    /** §117 快门优先：曝光时间固定，摄像头自己调增益/光圈（silu 同法） */
    const val AE_MODE_SHUTTER_PRIORITY = 4
    private const val AE_MODE_APERTURE_PRIORITY = 8

    /** UVC CT_AE_PRIORITY 能力位（saki4510t UVCCamera.CTRL_AE_PRIORITY = D2）；反射取不到时用这个值 */
    private const val CTRL_AE_PRIORITY_FALLBACK = 0x00000004

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
    // §117 CT_AE_PRIORITY：0=帧率恒定，1=AE 可为亮度降帧率。全部可缺省（缺了只是不支持该开关）
    private var mSetPrio: Method? = null
    private var mGetPrio: Method? = null
    private var mUpdatePrioLimit: Method? = null
    private var aePrioFlag = CTRL_AE_PRIORITY_FALLBACK

    /** 开机时读到的原始 AE 模式（-1=未知），恢复自动曝光时优先回到它 */
    @Volatile private var defaultMode = -1
    /** 当前是否处于我们切进去的手动曝光（模式 1 或 4） */
    @Volatile var manualActive = false
        private set
    /** 手动曝光时实际生效的模式：1=纯手动（增益由用户/软件 AGC 管），4=快门优先（摄像头自己调增益） */
    @Volatile var currentManualMode = AE_MODE_MANUAL
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
            mSetPrio = try {
                cls.getDeclaredMethod("nativeSetExposurePriority", jLong, jInt).apply { isAccessible = true }
            } catch (_: Throwable) { null }
            mGetPrio = try {
                cls.getDeclaredMethod("nativeGetExposurePriority", jLong).apply { isAccessible = true }
            } catch (_: Throwable) { null }
            mUpdatePrioLimit = try {
                cls.getDeclaredMethod("nativeUpdateExposurePriorityLimit", jLong).apply { isAccessible = true }
            } catch (_: Throwable) { null }
            aePrioFlag = try { cls.getField("CTRL_AE_PRIORITY").getInt(null) } catch (_: Throwable) { CTRL_AE_PRIORITY_FALLBACK }
            ok = true
            Log.d(TAG, "🔌 [OTG曝光] 反射桥接就绪（读模式=${if (mGetMode != null) "可用" else "不可用"}" +
                    "，AE优先级=${if (mSetPrio != null) "可用" else "不可用"}）")
        } catch (t: Throwable) {
            ok = false
            Log.d(TAG, "🔌 [OTG曝光] 反射桥接不可用（库签名可能变了）: ${t.message}")
        }
    }

    /** 库层面有没有可调曝光的接口（与设备是否支持 CTRL_AE_ABS 无关，那由调用方 checkSupportFlag 另判）。*/
    val isAvailable: Boolean
        get() { ensure(); return ok }

    private fun ptr(camera: UVCCamera): Long = fNativePtr!!.getLong(camera)

    /** 调 native：库里有的是 static、有的是实例方法，按声明自动选 receiver */
    private fun nat(m: Method, camera: UVCCamera, vararg args: Any): Any? {
        val recv = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else camera
        return m.invoke(recv, ptr(camera), *args)
    }

    /** native 更新曝光上下限（写入 mExposureMin/Max/Def），是百分比映射的前提。updateCameraParams 不含此项。*/
    private fun refreshLimit(camera: UVCCamera) {
        try { nat(mUpdateLimit!!, camera) } catch (_: Throwable) { /* 个别设备不报，忽略 */ }
    }

    /** 读当前 AE 模式；读不到回 -1 */
    private fun readMode(camera: UVCCamera): Int = try {
        mGetMode?.let { nat(it, camera) as? Int } ?: -1
    } catch (_: Throwable) { -1 }

    /** native 返回值：Int 且 <0 视为失败；无返回值/非 Int 视为成功 */
    private fun setMode(camera: UVCCamera, mode: Int): Boolean = try {
        val r = nat(mSetMode!!, camera, mode)
        !(r is Int && r < 0)
    } catch (t: Throwable) {
        Log.d(TAG, "🔌 [OTG曝光] 切模式 $mode 失败: ${t.message}")
        false
    }

    private fun isManualMode(m: Int) = m == AE_MODE_MANUAL || m == AE_MODE_SHUTTER_PRIORITY

    /** 相机刚打开时调：记下原始 AE 模式，供恢复自动曝光 */
    fun onCameraOpened(camera: UVCCamera) {
        if (!isAvailable) return
        defaultMode = readMode(camera)
        manualActive = isManualMode(defaultMode)
        currentManualMode = if (defaultMode == AE_MODE_SHUTTER_PRIORITY) AE_MODE_SHUTTER_PRIORITY else AE_MODE_MANUAL
        Log.d(TAG, "🔌 [OTG曝光] 原始AE模式=$defaultMode（1=手动 2=自动 4=快门优先 8=光圈优先 -1=读不到）")
    }

    /** 相机关闭时调 */
    fun onCameraClosed() {
        defaultMode = -1
        manualActive = false
        currentManualMode = AE_MODE_MANUAL
    }

    /** 是否处于自动曝光（读得到模式就以设备为准，读不到按我们自己的切换记录） */
    fun isAuto(camera: UVCCamera): Boolean {
        val m = readMode(camera)
        return if (m > 0) !isManualMode(m) else !manualActive
    }

    /**
     * 切到手动曝光（或在两种手动模式间切换）。先读出当前曝光值，切完再写回——画面不跳黑。
     *
     * @param preferShutterPriority true=先试快门优先(4)，摄像头回读确认是 4 才算数（它自己调增益，
     *        不必跑软件 AGC）；不支持就回落纯手动(1)。false=纯手动。
     * @return 实际生效的模式（1 或 4）
     */
    fun setManualMode(camera: UVCCamera, preferShutterPriority: Boolean): Int {
        if (!isAvailable) return AE_MODE_MANUAL
        val want = if (preferShutterPriority) AE_MODE_SHUTTER_PRIORITY else AE_MODE_MANUAL
        if (manualActive && currentManualMode == want) return currentManualMode
        val cur = try { nat(mGet!!, camera) as Int } catch (_: Throwable) { -1 }
        var mode = AE_MODE_MANUAL
        if (preferShutterPriority && setMode(camera, AE_MODE_SHUTTER_PRIORITY) &&
            readMode(camera) == AE_MODE_SHUTTER_PRIORITY) {
            mode = AE_MODE_SHUTTER_PRIORITY
        }
        val switched = if (mode == AE_MODE_SHUTTER_PRIORITY) true else setMode(camera, AE_MODE_MANUAL)
        manualActive = true
        currentManualMode = mode
        if (switched && cur > 0) {
            try { nat(mSet!!, camera, cur) } catch (_: Throwable) {}
        }
        Log.d(TAG, "🔌 [OTG曝光] 切手动曝光 ${if (switched) "成功" else "失败"} mode=$mode" +
                "（${if (mode == AE_MODE_SHUTTER_PRIORITY) "快门优先，摄像头自调增益" else "纯手动"}" +
                "${if (preferShutterPriority && mode != AE_MODE_SHUTTER_PRIORITY) "，设备不支持快门优先" else ""}），锁定当前曝光 abs=$cur")
        return mode
    }

    // ---------- §117 CT_AE_PRIORITY（帧率恒定 / 允许降帧）----------

    /** 设备是否支持 AE 优先级控制（库有接口 + 设备报 CT_AE_PRIORITY 能力位） */
    fun aePrioritySupported(camera: UVCCamera): Boolean {
        if (!isAvailable || mSetPrio == null) return false
        return try { camera.checkSupportFlag(aePrioFlag.toLong()) } catch (_: Throwable) { false }
    }

    /** 读 AE 优先级：0=帧率恒定 1=允许降帧；读不到 -1 */
    fun getAePriority(camera: UVCCamera): Int = try {
        mUpdatePrioLimit?.let { try { nat(it, camera) } catch (_: Throwable) {} }
        mGetPrio?.let { nat(it, camera) as? Int } ?: -1
    } catch (_: Throwable) { -1 }

    /** 设 AE 优先级。allowFpsDrop=false → 0（帧率恒定，暗光不降帧） */
    fun setAePriority(camera: UVCCamera, allowFpsDrop: Boolean): Boolean {
        val m = mSetPrio ?: return false
        val v = if (allowFpsDrop) 1 else 0
        return try {
            val r = nat(m, camera, v)
            val ok = !(r is Int && r < 0)
            Log.d(TAG, "🔌 [OTG曝光] AE优先级=$v（${if (allowFpsDrop) "允许暗光降帧" else "帧率恒定"}）" +
                    " ${if (ok) "成功" else "失败 rc=$r"}，回读=${getAePriority(camera)}")
            ok
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 设 AE优先级失败: ${t.message}")
            false
        }
    }

    /** 恢复自动曝光：原始模式 → 光圈优先(8) → 全自动(2)，第一个成功即停 */
    fun setAutoMode(camera: UVCCamera): Boolean {
        if (!isAvailable) return false
        val candidates = linkedSetOf<Int>()
        if (defaultMode > 0 && !isManualMode(defaultMode)) candidates += defaultMode
        candidates += AE_MODE_APERTURE_PRIORITY
        candidates += AE_MODE_AUTO
        for (m in candidates) {
            if (!setMode(camera, m)) continue
            val back = readMode(camera)
            if (back > 0 && isManualMode(back)) continue   // 设备没认，仍在手动
            manualActive = false
            currentManualMode = AE_MODE_MANUAL
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
            val abs = (nat(mGet!!, camera) as Int).coerceIn(lo, hi)
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
            nat(mSet!!, camera, abs)
            Log.d(TAG, "🔌 [OTG曝光] 已下发 ${pct}% → abs=$abs (${abs / 10.0}ms, 区间 ${lo / 10.0}~${hi / 10.0}ms, " +
                    "设备 min=${fMin!!.getInt(camera)} max=${fMax!!.getInt(camera)}, fps=$fps)")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 下发失败: ${t.message}")
            false
        }
    }
}
