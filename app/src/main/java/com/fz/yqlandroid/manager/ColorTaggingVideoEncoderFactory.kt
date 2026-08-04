package com.fz.yqlandroid.manager

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 🎨 颜色管线对标 iOS —— 给 H264 码流补回 BT.709 + full-range 色彩描述（VUI）
 *
 * iOS(NV12MetalProcessor) 在编码前给 CVPixelBuffer 打上
 *   YCbCrMatrix / ColorPrimaries / TransferFunction = ITU_R_709_2 + full-range，
 * 使 H264 携带正确的 colour_description，PC 端才能按满范围 BT.709 还原（否则红色发暗、整体偏色）。
 *
 * Android 的 stream-webrtc-android 硬编码器默认**不写 VUI 色彩描述**，PC 端多按 BT.601 有限范围猜测，
 * 于是与 iOS 出现色差。本包装器在**编码之后、仅关键帧**上改写 SPS 的 VUI：
 *   video_signal_type_present_flag=1, video_full_range_flag=1,
 *   colour_description_present_flag=1, primaries=transfer=matrix=1(BT.709)。
 *
 * ⭐ 低发热设计：只处理关键帧（SPS 只出现在关键帧），P 帧原样透传，几乎零额外功耗。
 * ⭐ 安全兜底：任何解析异常一律返回原始帧，绝不影响推流。
 *
 * 🎛️ 2026-07-09 追加「恒定码率 governor」（对 H264 + H265 都生效）：
 *   根因（官方源码/文档实锤）：
 *   1. libwebrtc `HardwareVideoEncoder.initEncodeInternal` 虽然请求了
 *      `MediaFormat.KEY_BITRATE_MODE = BITRATE_MODE_CBR`，但**不检查**
 *      `EncoderCapabilities.isBitrateModeSupported(CBR)`；Android 官方文档明确：
 *      编码器不支持 CBR 时该设置被静默忽略、回落 VBR → 运动画面码率必然冲高。
 *   2. 官方 `HardwareVideoEncoderFactory.createBitrateAdjuster` **只给 Exynos 芯片**
 *      配 Dynamic/Framerate BitrateAdjuster，高通/联发科/海思等一律 BaseBitrateAdjuster
 *      = 零纠偏，编码器超标输出没人拉回来。
 *   3. 官方对这个问题的标准解法就是 `DynamicBitrateAdjuster`（注释原话：
 *      "Used for hardware codecs that ... deviate from the target bitrate by
 *      unacceptable margins"）：累计实际输出与目标的字节偏差，每 3 秒把喂给
 *      编码器的请求码率按 4^(exp/20) 缩放纠偏。
 *   本 governor = 官方 DynamicBitrateAdjuster 算法逐行搬运，仅一处收紧：
 *   **scaleExp 钳到 ≤0（只准向下压、回稳只回到 100%）**——官方允许放大到 4 倍以补
 *   undershoot，但静止画面 VBR 本来就用不满码率，若放大请求，下一次运动会先冲一波
 *   更大的尖峰，与「码率恒定」目标相反。
 */
class ColorTaggingVideoEncoderFactory(
    private val delegate: VideoEncoderFactory,
    // 🔥 运动突增回调：编码 P 帧字节数显著超过滑动基线时触发（用于“大范围拖动花屏→加密关键帧”）。
    //    在编码线程回调，实现方需自行切线程，且必须轻量非阻塞。
    private val onMotionSurge: (() -> Unit)? = null
) : VideoEncoderFactory {

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        val inner = info?.let { delegate.createEncoder(it) } ?: return null
        // H264：VUI 改写 + 恒定码率 governor；H265：仅 governor（VUI 是 H264 语法）。
        // VP8/VP9/AV1 不在推流链路上，原样返回。
        return when {
            info.name.equals("H264", ignoreCase = true) -> {
                Log.d(TAG, "🎨 包装 H264 编码器：BT.709 VUI + 恒定码率governor")
                H264ColorTagEncoder(inner, onMotionSurge, tagVui = true, codecName = "H264")
            }
            info.name.equals("H265", ignoreCase = true) || info.name.equals("HEVC", ignoreCase = true) -> {
                Log.d(TAG, "🎛️ 包装 H265 编码器：恒定码率governor（无 VUI 改写）")
                H264ColorTagEncoder(inner, onMotionSurge, tagVui = false, codecName = "H265")
            }
            else -> inner
        }
    }

    companion object { private const val TAG = "ColorTagEncoder" }
}

/**
 * 包装单个 VideoEncoder：
 *   1. （仅 H264）拦截回调，在关键帧改写 SPS 的 VUI；
 *   2. 轻量运动突增检测（P 帧字节滑动基线）；
 *   3. 🎛️ 恒定码率 governor（见文件头，对标官方 DynamicBitrateAdjuster、只准向下钳）。
 */
private class H264ColorTagEncoder(
    private val inner: VideoEncoder,
    private val onMotionSurge: (() -> Unit)? = null,
    private val tagVui: Boolean = true,
    private val codecName: String = "H264"
) : VideoEncoder {

    // ===== 运动突增检测（基于 P 帧字节数滑动基线，零像素处理、低发热） =====
    private var pFrameBaselineBytes: Double = 0.0     // P 帧字节数指数滑动平均
    private var baselineInitialized: Boolean = false
    private var lastSurgeNs: Long = 0L

    private fun detectMotionSurge(frame: EncodedImage) {
        try {
            // 只看 P 帧（Delta）。关键帧本身字节大是正常的，不参与运动判断。
            if (frame.frameType != EncodedImage.FrameType.VideoFrameDelta) return
            val bytes = (frame.buffer?.remaining() ?: return).toDouble()
            if (bytes <= 0) return

            if (!baselineInitialized) {
                pFrameBaselineBytes = bytes
                baselineInitialized = true
                return
            }

            // 突增判据：当前 P 帧字节数 > 基线 * SURGE_RATIO，且基线已有意义
            val isSurge = pFrameBaselineBytes > 0 && bytes > pFrameBaselineBytes * SURGE_RATIO

            // 指数滑动更新基线（EMA）
            pFrameBaselineBytes = pFrameBaselineBytes * (1 - EMA_ALPHA) + bytes * EMA_ALPHA

            if (isSurge) {
                val now = System.nanoTime()
                // 节流：突增通知最短间隔，避免持续大运动时每帧都回调
                if (now - lastSurgeNs >= SURGE_MIN_INTERVAL_NS) {
                    lastSurgeNs = now
                    onMotionSurge?.invoke()
                }
            }
        } catch (_: Throwable) { /* 检测失败绝不影响推流 */ }
    }

    // ===== 🎛️ 恒定码率 governor（算法=官方 DynamicBitrateAdjuster，scaleExp 钳 ≤0） =====
    // 线程模型：setTargets 在编码线程（initEncode/setRates），reportFrame 在输出线程
    // （onEncodedFrame 回调），scale 在编码线程（encode 时应用）——用 @Synchronized 保护。
    private var govTargetBps: Double = 0.0
    private var govTargetFps: Double = 0.0
    private var govDeviationBytes: Double = 0.0        // 实际输出 − 期望输出 的累计字节差
    private var govTimeSinceAdjustMs: Double = 0.0
    private var govScaleExp: Int = 0                   // ≤0；scale = 4^(exp/20) ∈ [0.25, 1.0]
    @Volatile private var govScale: Double = 1.0
    // 编码线程私有：最近一次 native 下发的目标（用于 scale 变化时重新下发给内部编码器）
    private var lastRcParams: VideoEncoder.RateControlParameters? = null
    private var lastAppliedScale: Double = 1.0

    @Synchronized
    private fun govSetTargets(targetBps: Double, targetFps: Double) {
        if (govTargetBps > 0 && targetBps < govTargetBps) {
            // 官方算法：目标下调时等比缩 accumulator，避免旧偏差按新目标误判
            govDeviationBytes = govDeviationBytes * targetBps / govTargetBps
        }
        govTargetBps = targetBps
        govTargetFps = targetFps
    }

    @Synchronized
    private fun govReportFrame(sizeBytes: Int) {
        if (govTargetBps <= 0 || govTargetFps <= 0) return
        val expectedBytesPerFrame = (govTargetBps / 8.0) / govTargetFps
        govDeviationBytes += sizeBytes - expectedBytesPerFrame
        govTimeSinceAdjustMs += 1000.0 / govTargetFps

        val thresholdBytes = govTargetBps / 8.0          // 1 秒量的字节数
        val cap = GOV_ADJUSTMENT_SEC * thresholdBytes    // 偏差封顶（防陈旧数据）
        govDeviationBytes = govDeviationBytes.coerceIn(-cap, cap)

        // 每 3 秒最多调一次（官方节奏）
        if (govTimeSinceAdjustMs <= GOV_ADJUSTMENT_SEC * 1000.0) return

        if (govDeviationBytes > thresholdBytes) {
            // 实际输出超标累计 >1 秒量 → 下压请求码率
            val steps = (govDeviationBytes / thresholdBytes + 0.5).toInt()
            govScaleExp = max(govScaleExp - steps, -GOV_STEPS)
            govDeviationBytes = thresholdBytes
        } else if (govDeviationBytes < -thresholdBytes) {
            // 输出低于目标 → 逐步回升，但 ⭐ 钳到 0（只回到 100%，绝不超发）
            val steps = (-govDeviationBytes / thresholdBytes + 0.5).toInt()
            govScaleExp = min(govScaleExp + steps, 0)
            govDeviationBytes = -thresholdBytes
        }
        govTimeSinceAdjustMs = 0.0

        val newScale = GOV_MAX_SCALE.pow(govScaleExp.toDouble() / GOV_STEPS)
        if (abs(newScale - govScale) > 0.001) {
            govScale = newScale
            Log.d("meidui", "🎛️ [码率governor] $codecName 编码器实际输出偏离目标 → " +
                    "请求码率×${"%.2f".format(newScale)} (exp=$govScaleExp, 目标=${(govTargetBps / 1000).toInt()}kbps)")
        }
    }

    /** 按 scale 缩放 RateControlParameters（逐层缩 BitrateAllocation，帧率不动） */
    private fun scaledRc(rc: VideoEncoder.RateControlParameters, scale: Double): VideoEncoder.RateControlParameters {
        if (scale >= 0.999) return rc
        return try {
            val scaled = rc.bitrate.bitratesBbs
                .map { layer -> layer.map { (it * scale).toInt() }.toIntArray() }
                .toTypedArray()
            VideoEncoder.RateControlParameters(VideoEncoder.BitrateAllocation(scaled), rc.framerateFps)
        } catch (_: Throwable) { rc /* 构造失败原样透传，绝不影响推流 */ }
    }

    // ===== VideoEncoder 代理 =====

    override fun initEncode(settings: VideoEncoder.Settings?, cb: VideoEncoder.Callback?): VideoCodecStatus {
        // startBitrate 单位 kbps（官方 Settings 注释：Kilobits per second）
        settings?.let { govSetTargets(it.startBitrate * 1000.0, it.maxFramerate.toDouble()) }
        val wrapped = if (cb == null) null else VideoEncoder.Callback { frame, info ->
            govReportFrame(frame.buffer?.remaining() ?: 0)
            detectMotionSurge(frame)
            cb.onEncodedFrame(maybeTag(frame), info)
        }
        return inner.initEncode(settings, wrapped)
    }

    override fun release(): VideoCodecStatus = inner.release()

    override fun encode(frame: VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        // ⭐ scale 在输出线程算出，但 setRates 必须在编码线程调（内部有 ThreadChecker），
        //   所以在每帧 encode 前检查是否需要按新 scale 重新下发
        val s = govScale
        if (s != lastAppliedScale) {
            lastAppliedScale = s
            lastRcParams?.let { rc ->
                try { inner.setRates(scaledRc(rc, s)) } catch (_: Throwable) {}
            }
        }
        return inner.encode(frame, info)
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, framerate: Int): VideoCodecStatus {
        if (allocation == null) return inner.setRateAllocation(null, framerate)
        govSetTargets(allocation.sum.toDouble(), framerate.toDouble())
        val rc = VideoEncoder.RateControlParameters(allocation, framerate.toDouble())
        lastRcParams = rc
        lastAppliedScale = govScale
        val scaled = scaledRc(rc, govScale)
        return inner.setRateAllocation(scaled.bitrate, framerate)
    }

    override fun setRates(rcParameters: VideoEncoder.RateControlParameters?): VideoCodecStatus {
        if (rcParameters == null) return inner.setRates(null)
        govSetTargets(rcParameters.bitrate.sum.toDouble(), rcParameters.framerateFps)
        lastRcParams = rcParameters
        lastAppliedScale = govScale
        return inner.setRates(scaledRc(rcParameters, govScale))
    }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = inner.scalingSettings

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> =
        inner.resolutionBitrateLimits

    override fun getImplementationName(): String = inner.implementationName

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = inner.encoderInfo

    override fun isHardwareEncoder(): Boolean = inner.isHardwareEncoder

    private fun maybeTag(frame: EncodedImage): EncodedImage {
        if (!tagVui) return frame  // H265 会话：VUI 是 H264 语法，直接透传
        return try {
            if (frame.frameType != EncodedImage.FrameType.VideoFrameKey) return frame
            val src = frame.buffer ?: return frame
            val data = ByteArray(src.remaining())
            // duplicate 避免扰动原 buffer 的 position（原帧仍由内部编码器负责释放）
            src.duplicate().get(data)

            val modified = H264VuiEditor.setBt709FullRange(data) ?: return frame
            if (modified.contentEquals(data)) return frame

            val out = ByteBuffer.allocateDirect(modified.size)
            out.put(modified)
            out.rewind()

            EncodedImage.builder()
                .setBuffer(out, null)
                .setEncodedWidth(frame.encodedWidth)
                .setEncodedHeight(frame.encodedHeight)
                .setCaptureTimeNs(frame.captureTimeNs)
                .setFrameType(frame.frameType)
                .setRotation(frame.rotation)
                .setQp(frame.qp)
                .createEncodedImage()
        } catch (e: Throwable) {
            Log.w(TAG, "VUI 改写失败，透传原帧: ${e.message}")
            frame
        }
    }

    companion object {
        private const val TAG = "H264ColorTag"
        // 运动突增判据：P 帧字节数 > 基线的 2.2 倍视为大范围运动
        private const val SURGE_RATIO = 2.2
        // 基线 EMA 平滑系数（越大越跟手；0.2 约等于近几帧的均值）
        private const val EMA_ALPHA = 0.2
        // 突增回调最短间隔 120ms（配合关键帧 0.1~0.5s 节奏，避免每帧回调）
        private const val SURGE_MIN_INTERVAL_NS = 120_000_000L

        // ===== governor 常量（与官方 DynamicBitrateAdjuster 完全一致） =====
        private const val GOV_ADJUSTMENT_SEC = 3.0   // 最多每 3 秒调一次
        private const val GOV_MAX_SCALE = 4.0        // 縮放底数：4^(exp/20)
        private const val GOV_STEPS = 20             // exp ∈ [-20, 0] → scale ∈ [0.25, 1.0]
    }
}
