package com.fz.yqlandroid.manager.uvc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * ⭐ 第五十章：查询本机硬件编码器支持的分辨率范围。
 *
 * 背景（2026-07-26 实测日志实锤）：OTG 选到 160x120 时，采集完全正常
 * （`✅ IFrameCallback正常：2500ms内53帧 160x120`、`capFps=29`），
 * 但 `encFps=0 sentFps=0 kbps=0`、PC 侧 fir 一路涨到 27 —— **编码器一帧不出**。
 * 原因是硬件 H264/HEVC 编码器都有最小分辨率下限（HEVC 常见 176x144），
 * 低于下限 MediaCodec 不报错、就是不出流，表现为"这个档位一片黑"。
 *
 * 所以能力上报时就得把"编码器吃不下的档位"标出来，PC 面板直接不给选，
 * 而不是让用户点一个必黑的档位。
 */
object EncoderSizeLimits {

    private const val LOG = "meidui"

    /** 缓存：mime → VideoCapabilities（查询要遍历全部编解码器，别每次枚举都跑） */
    private val cache = mutableMapOf<String, MediaCodecInfo.VideoCapabilities?>()

    fun mimeOf(codec: String): String =
        if (codec.equals("h265", true) || codec.equals("hevc", true)) "video/hevc" else "video/avc"

    private fun capsOf(mime: String): MediaCodecInfo.VideoCapabilities? = cache.getOrPut(mime) {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                ?.getCapabilitiesForType(mime)
                ?.videoCapabilities
        } catch (e: Throwable) {
            Log.d(LOG, "🔌 [编码器能力] 查询 $mime 失败: ${e.message}")
            null
        }
    }

    /**
     * 该分辨率编码器能不能吃。查不到能力信息时**一律放行**（宁可让它试，也不误杀）。
     */
    fun isEncodable(codec: String, width: Int, height: Int): Boolean {
        val vc = capsOf(mimeOf(codec)) ?: return true
        return try {
            vc.isSizeSupported(width, height)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 该尺寸下编码器支持的最高帧率（能力口径，不是当下负载）。
     *
     * 这才是"推流 fps 上限"的正主——之前用的 60 是从自带摄像头 ladder 借来的拍脑袋值，
     * 跟硬件无关；小分辨率上硬件编码器编 120fps 很常见。查不到时回 0（调用方自己兜底）。
     */
    fun maxFrameRate(codec: String, width: Int, height: Int): Int {
        val vc = capsOf(mimeOf(codec)) ?: return 0
        return try {
            vc.getSupportedFrameRatesFor(width, height).upper.toInt().coerceIn(1, 240)
        } catch (_: Throwable) {
            0   // 尺寸本身不支持等
        }
    }

    /** 人读的下限描述，打日志用（如 "H265 编码器支持 176x144 ~ 3840x2160"） */
    fun describe(codec: String): String {
        val mime = mimeOf(codec)
        val vc = capsOf(mime) ?: return "$codec 编码器能力未知（不做过滤）"
        return try {
            "$codec 编码器支持 ${vc.supportedWidths.lower}x${vc.supportedHeights.lower}" +
                    " ~ ${vc.supportedWidths.upper}x${vc.supportedHeights.upper}" +
                    "（对齐 ${vc.widthAlignment}x${vc.heightAlignment}）"
        } catch (_: Throwable) {
            "$codec 编码器能力读取异常（不做过滤）"
        }
    }
}
