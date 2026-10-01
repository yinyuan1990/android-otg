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
 * 约束：所有调用必须在 uvcThread 上（与其它 nativeSetXxx 一致）。任何一步反射失败即
 * [isAvailable]=false，上层据此把「快门/曝光」控件标记为不支持（PC 面板灰化、拖不动）。
 */
object UvcExposureBridge {
    private const val TAG = "meidui"

    // UVC CT_AE_MODE 位图：D0=Manual，D1=Auto，D2=Shutter Priority，D3=Aperture Priority。
    // 手动设曝光时间前必须切到手动，否则相机自动曝光会无视手动值。
    private const val AE_MODE_MANUAL = 1

    @Volatile private var resolved = false
    @Volatile private var ok = false

    private var fNativePtr: Field? = null
    private var fMin: Field? = null
    private var fMax: Field? = null
    private var mUpdateLimit: Method? = null   // 实例方法：nativeUpdateExposureLimit(long)
    private var mSet: Method? = null           // static：nativeSetExposure(long,int)
    private var mGet: Method? = null           // static：nativeGetExposure(long)
    private var mSetMode: Method? = null       // static：nativeSetExposureMode(long,int)

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
            ok = true
            Log.d(TAG, "🔌 [OTG曝光] 反射桥接就绪")
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

    /** 切到手动曝光模式。个别相机只认 shutter-priority(4)，这里取 manual(1)。*/
    fun setManualMode(camera: UVCCamera) {
        if (!isAvailable) return
        try {
            mSetMode!!.invoke(null, ptr(camera), AE_MODE_MANUAL)
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 切手动模式失败: ${t.message}")
        }
    }

    /** 当前曝光 → 百分比 0~100；失败回 -1。*/
    fun getPercent(camera: UVCCamera): Int {
        if (!isAvailable) return -1
        return try {
            refreshLimit(camera)
            val min = fMin!!.getInt(camera)
            val max = fMax!!.getInt(camera)
            val range = Math.abs(max - min)
            if (range <= 0) return -1
            val abs = mGet!!.invoke(null, ptr(camera)) as Int
            ((abs - min) * 100f / range).toInt().coerceIn(0, 100)
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 读取失败: ${t.message}")
            -1
        }
    }

    /** 百分比 0~100 → 绝对曝光值下发。*/
    fun setPercent(camera: UVCCamera, pct: Int): Boolean {
        if (!isAvailable) return false
        return try {
            refreshLimit(camera)
            val min = fMin!!.getInt(camera)
            val max = fMax!!.getInt(camera)
            val range = Math.abs(max - min)
            if (range <= 0) return false
            val abs = (pct.coerceIn(0, 100) / 100f * range).toInt() + min
            mSet!!.invoke(null, ptr(camera), abs)
            Log.d(TAG, "🔌 [OTG曝光] 已下发 ${pct}% → abs=$abs (min=$min max=$max)")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "🔌 [OTG曝光] 下发失败: ${t.message}")
            false
        }
    }
}
