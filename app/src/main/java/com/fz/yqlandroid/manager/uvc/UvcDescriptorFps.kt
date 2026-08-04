package com.fz.yqlandroid.manager.uvc

/**
 * ⭐ 2026-08-03 UVC 原始描述符解析：**提前知道**每个分辨率支持的真实 fps 表。
 *
 * 背景（§55.8）：AUSBC 库的 `getSupportedSizeList()` 在很多设备上只回填宽高、不回填每档
 * fps（native 只吐简化 JSON），导致 PC 切档只能按最大喊价 120 → Java `setPreviewSize`
 * "假接受" → native `prepare_preview` 报 -51 → 0帧看门狗降帧收敛（盲试，最坏 ~5s）。
 *
 * 但 UVC 协议本身是明牌：设备的 USB 配置描述符里，每个格式（MJPEG/YUYV）下每个分辨率
 * 都带一张帧间隔表（dwFrameInterval，100ns 单位），10^7/间隔 = fps。
 * `UsbDeviceConnection.getRawDescriptors()` 能拿到原始字节，这里自己按标准布局解析，
 * 绕过库的残缺封装。描述符规矩的摄像头从此切档零试错；乱写的仍走降帧收敛兜底。
 *
 * 布局参考 UVC 1.1/1.5 规范（Class-specific VS Interface Descriptors）：
 *   CS_INTERFACE(0x24) + subtype: VS_FORMAT_MJPEG=0x06 / VS_FORMAT_UNCOMPRESSED=0x04
 *                                 VS_FRAME_MJPEG=0x07  / VS_FRAME_UNCOMPRESSED=0x05
 *   帧描述符: wWidth@5(LE16) wHeight@7(LE16) bFrameIntervalType@25
 *             =0 连续: dwMin@26 dwMax@30 (LE32)；>0 离散: dwFrameInterval[i]@26+4i
 */
object UvcDescriptorFps {

    /** 与 preferredFormat 同一套编号：1=MJPEG 2=YUYV(未压缩) */
    const val FORMAT_MJPEG = 1
    const val FORMAT_YUYV = 2

    data class FrameEntry(val format: Int, val width: Int, val height: Int, val fpsList: List<Int>)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun parse(raw: ByteArray): List<FrameEntry> {
        val out = ArrayList<FrameEntry>()
        var o = 0
        var curFormat = 0   // 0 = 当前不在已识别的视频格式区
        while (o + 2 <= raw.size) {
            val len = raw[o].toInt() and 0xFF
            if (len < 2 || o + len > raw.size) break
            val dtype = raw[o + 1].toInt() and 0xFF
            if (dtype == 0x24 && len >= 3) {   // CS_INTERFACE
                when (raw[o + 2].toInt() and 0xFF) {
                    0x06 -> curFormat = FORMAT_MJPEG          // VS_FORMAT_MJPEG
                    0x04 -> curFormat = FORMAT_YUYV           // VS_FORMAT_UNCOMPRESSED
                    0x05, 0x07 -> if (curFormat != 0 && len >= 30) {   // VS_FRAME_*
                        val w = le16(raw, o + 5)
                        val h = le16(raw, o + 7)
                        val nIv = raw[o + 25].toInt() and 0xFF
                        val fps = ArrayList<Int>()
                        if (nIv == 0) {
                            // 连续区间：min 间隔 = 最高帧率，max 间隔 = 最低帧率
                            if (len >= 38) {
                                val minIv = le32(raw, o + 26)
                                val maxIv = le32(raw, o + 30)
                                if (minIv > 0) fps += (10_000_000L / minIv).toInt()
                                if (maxIv > 0) fps += (10_000_000L / maxIv).toInt()
                            }
                        } else {
                            for (i in 0 until nIv) {
                                val p = o + 26 + i * 4
                                if (p + 4 > o + len) break
                                val iv = le32(raw, p)
                                if (iv > 0) fps += Math.round(10_000_000.0 / iv).toInt()
                            }
                        }
                        val cleaned = fps.filter { it in 1..1000 }.distinct().sortedDescending()
                        if (w > 0 && h > 0 && cleaned.isNotEmpty()) {
                            out += FrameEntry(curFormat, w, h, cleaned)
                        }
                    }
                    // 其余 subtype（VS_COLORFORMAT 等）不改变 curFormat
                }
            }
            o += len
        }
        return out
    }
}
