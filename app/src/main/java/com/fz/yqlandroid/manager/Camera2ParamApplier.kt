package com.fz.yqlandroid.manager

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import org.webrtc.CameraVideoCapturer

/**
 * 🔥 原生采集器的“按需”硬件控制层（对标参考产品 y60373 CameraActivity.applyCamera2Params）
 *
 * 背景（见 docs/PROGRESS.md 十五）：
 *   原自定义 Camera2ControlCapturer 自建采集管线 + 常驻手动曝光，是 Android 端发热主因。
 *   改用 WebRTC 原生 Camera2Capturer 后，用反射拿到原生 Camera2Session 内部的
 *   captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface，
 *   在“用户拖动 / 后端下发”时临时新建一次 setRepeatingRequest 注入曝光/对焦/变焦/快门/白平衡。
 *   —— 不常驻、不每帧，兼顾低发热与可控性。
 *
 * ⭐ 快门(cjfps)保留：反射通道同样可写 SENSOR_EXPOSURE_TIME + AE_OFF + 手动 ISO。
 * ⭐ 安全兜底：反射/写入任何异常一律吞掉，绝不影响推流（字段名随库实现可能变化）。
 *
 * 反射字段名与 WebRTC(Camera2Capturer/Camera2Session) 一致；本项目使用
 * io.getstream:stream-webrtc-android:1.1.1（同源 chromium webrtc），字段名沿用。
 */
object Camera2ParamApplier {

    private const val TAG = "Camera2ParamApplier"

    /** 一次控制的完整参数集合（缺省值=不改/自动）。 */
    data class Params(
        val exposureEv: Float? = null,     // AE 曝光补偿(EV)，仅在未启用手动快门时生效
        val focus: Float? = null,          // 0..1；0.5=连续自动对焦，其余=手动对焦距离
        val zoom: Float? = null,           // >=1.0；变焦
        val shutterCjfps: Int? = null,     // 快门 1/cjfps 秒(60~600)，非空=手动曝光(AE OFF)
        val manualIsoPercent: Int? = null, // ISO 增益 0~100（PC test_brightness）→ 映射设备 SENSITIVITY_RANGE；仅手动快门(AE OFF)生效
        val whiteBalanceSlider: Int? = null, // 0..100 手动色温(0冷100暖)
        val whiteBalanceLocked: Boolean = false, // 锁定当前白平衡
        val targetFps: Int? = null         // 🔥 钉死 AE 帧率区间 [fps,fps]，防低光自动砍半(30→15)
    )

    // ⭐ [meidui 诊断] 相机硬件层真实状态：每 ~150 帧打一行（30fps 下约 5s 一次）。
    //    exp=实际曝光时间 frameDur=实际帧间隔(33ms=30fps/66ms=15fps) aeRange=生效AE区间 iso=增益。
    private var diagFrameCount = 0L
    private val diagCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (diagFrameCount++ % 150 != 0L) return
            try {
                val expMs = (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L) / 1_000_000.0
                val durMs = (result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L) / 1_000_000.0
                val aeRange = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1
                val aeMode = result.get(CaptureResult.CONTROL_AE_MODE) ?: -1
                Log.d("meidui", "cam exp=${"%.1f".format(expMs)}ms frameDur=${"%.1f".format(durMs)}ms" +
                        " (≈${if (durMs > 0) (1000.0 / durMs).toInt() else -1}fps) aeRange=$aeRange iso=$iso aeMode=$aeMode")
            } catch (_: Throwable) { /* 诊断绝不影响推流 */ }
        }
    }

    /**
     * 反射取原生 session 句柄并应用参数（一次 setRepeatingRequest）。
     * @return true=已下发；false=反射失败/会话未就绪（已安全忽略）
     */
    fun apply(capturer: CameraVideoCapturer?, p: Params): Boolean {
        val cap = capturer ?: return false
        return try {
            // 1) currentSession 在 CameraCapturer 父类
            val superclass = cap.javaClass.superclass ?: return false
            val sessionField = superclass.getDeclaredField("currentSession").apply { isAccessible = true }
            val session = sessionField.get(cap) ?: return false
            val sc = session.javaClass

            val captureSession = sc.getDeclaredField("captureSession").apply { isAccessible = true }
                .get(session) as? CameraCaptureSession ?: return false
            val cameraDevice = sc.getDeclaredField("cameraDevice").apply { isAccessible = true }
                .get(session) as? CameraDevice ?: return false
            val characteristics = sc.getDeclaredField("cameraCharacteristics").apply { isAccessible = true }
                .get(session) as? CameraCharacteristics ?: return false
            val handler = sc.getDeclaredField("cameraThreadHandler").apply { isAccessible = true }
                .get(session) as? Handler ?: return false
            val surface = sc.getDeclaredField("surface").apply { isAccessible = true }
                .get(session) as? Surface ?: return false

            // 2) 新建 TEMPLATE_RECORD 请求，写入参数
            val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(surface)

            applyExposureAndShutter(builder, characteristics, p)
            applyFpsRange(builder, characteristics, p)
            applyFocus(builder, characteristics, p)
            applyZoom(builder, characteristics, p)
            applyWhiteBalance(builder, characteristics, p)

            // 3) 一次性下发（不常驻循环）。
            //    ⭐ [meidui 诊断] 挂 CaptureCallback 打印硬件真实曝光/帧间隔/AE区间：
            //    frameDur≈66ms=相机自己降到15fps（低光AE）；≈33ms=相机正常30fps、问题在采集之后。
            captureSession.setRepeatingRequest(builder.build(), diagCallback, handler)
            Log.d(TAG, "✅ 参数已注入(原生session): ev=${p.exposureEv} focus=${p.focus} zoom=${p.zoom} cjfps=${p.shutterCjfps} wb=${p.whiteBalanceSlider}/${p.whiteBalanceLocked}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "⚠️ 反射注入失败(已忽略，不影响推流): ${e.message}")
            false
        }
    }

    // ===== 曝光 / 快门 =====
    private fun applyExposureAndShutter(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val cj = p.shutterCjfps
            if (cj != null && cj > 0) {
                // 🔥 手动快门(保留 cjfps 能力)：AE OFF + SENSOR_EXPOSURE_TIME + 手动 ISO
                // ⭐ [meidui 诊断] 快门"无作用"排查：手动曝光需要 MANUAL_SENSOR 能力，
                //   LEGACY/部分 LIMITED 机型没有 → SENSOR_EXPOSURE_TIME 会被 HAL 静默忽略。
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val hasManualSensor = caps?.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                val hwLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "UNKNOWN"
                }
                val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                var expNs = 1_000_000_000L / cj
                val wantNs = expNs
                if (expRange != null) expNs = expNs.coerceIn(expRange.lower, expRange.upper)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                // ISO 增益：PC 下发 0~100 百分比 → 映射设备 SENSITIVITY_RANGE；未给则用区间中值
                val iso = when {
                    p.manualIsoPercent != null && isoRange != null ->
                        isoRange.lower + (isoRange.upper - isoRange.lower) * p.manualIsoPercent.coerceIn(0, 100) / 100
                    else -> isoRange?.let { (it.lower + it.upper) / 2 } ?: 400
                }
                val safeIso = if (isoRange != null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
                b.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                Log.d("meidui", "📸 [快门] 注入 1/${cj}s: 请求exp=${wantNs / 1000}us → clamp后=${expNs / 1000}us" +
                        " (设备范围=${expRange?.lower?.div(1000)}~${expRange?.upper?.div(1000)}us)" +
                        " iso=$safeIso MANUAL_SENSOR=$hasManualSensor hwLevel=$hwLevel" +
                        if (!hasManualSensor) " ⚠️ 该机型无手动曝光能力，HAL 会忽略快门设置(快门无作用的根因)" else "")
            } else {
                // 自动曝光 + 曝光补偿(EV)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_LOCK, false)
                val ev = p.exposureEv
                if (ev != null) {
                    val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    if (step != null && range != null && step.toDouble() > 0) {
                        val steps = Math.round(ev / step.toFloat()).coerceIn(range.lower, range.upper)
                        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exposure/shutter 写入失败: ${e.message}")
        }
    }

    // ===== AE 帧率区间（防低光降帧） =====
    // WebRTC Camera2Session 选 AE 区间偏好宽区间(如[15,30])，暗光时 AE 拉长曝光把帧率砍半(30→15)。
    // 这里改为钉死固定区间，且绝不高钉（旧逻辑「下界最高」会把目标20钉成[30,30]，
    // 相机被迫跑30fps → 热控降采集帧率完全失效 → 越跑越热升级 CRITICAL）。选择优先级：
    //   1) 精确 [fps,fps]
    //   2) 最小的固定区间 [x,x] 且 x>=fps（如目标20→[24,24]、目标12→[15,15]，防低光又贴近目标）
    //   3) 覆盖 fps 的区间里下界最高、跨度最小（如目标20→[15,24]）
    //   4) 兜底：上界最大的区间
    private fun applyFpsRange(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val fps = p.targetFps ?: return
            if (fps <= 0) return
            // 手动快门(AE OFF)时 AE 帧率区间无效，帧率由 SENSOR_FRAME_DURATION 决定。
            // 🔥 2026-07-08 修复「采集按 30 没生效」：此前这里直接 return、帧间隔没人设 →
            //    传感器跑满(实测 frameDur=16.7ms≈60fps，尽管 aeRange 钉了[30,30])。
            //    现在手动快门下显式钉 SENSOR_FRAME_DURATION=1/fps 秒（与手动曝光同属
            //    MANUAL_SENSOR 能力，快门能生效的机型这个也生效）。曝光时间若超过帧间隔，
            //    HAL 会自动拉长实际帧间隔（快门优先，符合预期）。
            if (p.shutterCjfps != null && p.shutterCjfps > 0) {
                val frameDurNs = 1_000_000_000L / fps
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurNs)
                Log.d("meidui", "🎞️ 手动快门(AE OFF)钉采集帧率: SENSOR_FRAME_DURATION=" +
                        "${frameDurNs / 1_000_000}ms → ${fps}fps（AE区间在手动模式无效，此值才是真帧率）")
                return
            }
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return
            val exact = ranges.firstOrNull { it.lower == fps && it.upper == fps }
            val fixedAbove = ranges
                .filter { it.lower == it.upper && it.upper >= fps }
                .minByOrNull { it.upper }
            val covering = ranges
                .filter { it.lower <= fps && it.upper >= fps }
                .maxWithOrNull(compareBy({ it.lower }, { -(it.upper - it.lower) }))
            val best = exact ?: fixedAbove ?: covering
                ?: ranges.maxByOrNull { it.upper }
            if (best != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(best.lower, best.upper))
                Log.d("meidui", "🎞️ AE帧率区间钉死: 目标${fps}fps → [${best.lower},${best.upper}] " +
                        "(可用: ${ranges.joinToString()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fpsRange 写入失败: ${e.message}")
        }
    }

    // ===== 对焦 =====
    private fun applyFocus(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val f = p.focus ?: return
            val minFocusDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (f == 0.5f || minFocusDist <= 0f) {
                // 连续自动对焦
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                // 手动对焦：1=远(0屈光度) 0=近(minFocusDist)
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - f).coerceIn(0f, 1f) * minFocusDist)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus 写入失败: ${e.message}")
        }
    }

    // ===== 变焦 =====
    private fun applyZoom(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val z = (p.zoom ?: return).coerceAtLeast(1.0f)
            val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val zc = z.coerceIn(1.0f, maxZoom)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zc.coerceIn(range.lower, range.upper))
                    return
                }
            }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            if (zc > 1.0f) {
                val cropW = (active.width() / zc).toInt()
                val cropH = (active.height() / zc).toInt()
                val x = (active.width() - cropW) / 2
                val y = (active.height() - cropH) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + cropW, y + cropH))
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, active)
            }
        } catch (e: Exception) {
            Log.w(TAG, "zoom 写入失败: ${e.message}")
        }
    }

    // ===== 白平衡 =====
    private fun applyWhiteBalance(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val slider = p.whiteBalanceSlider
            when {
                slider != null -> {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    val t = slider.coerceIn(0, 100) / 100f
                    val rGain = 1.2f + t * 1.3f
                    val bGain = 2.5f - t * 1.3f
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(rGain, 1.0f, 1.0f, bGain))
                }
                p.whiteBalanceLocked -> b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                else -> {
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "whiteBalance 写入失败: ${e.message}")
        }
    }
}
