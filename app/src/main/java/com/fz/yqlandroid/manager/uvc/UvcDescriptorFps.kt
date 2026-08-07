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

    /**
     * [formatIndex]/[frameIndex]/[vsInterface]/[ivByFps]：§56.17 PROBE 谈判诊断新增。
     * 自己向设备发 UVC PROBE（SET_CUR/GET_CUR）需要报文里填 bFormatIndex/bFrameIndex/
     * dwFrameInterval，wIndex 填 VS 接口号——全部来自描述符，解析时顺手记下。
     */
    data class FrameEntry(
        val format: Int, val width: Int, val height: Int, val fpsList: List<Int>,
        val formatIndex: Int = 0,
        val frameIndex: Int = 0,
        val vsInterface: Int = -1,
        val ivByFps: Map<Int, Long> = emptyMap()
    )

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    /**
     * ⭐ §56.14（2026-08-07）VideoStreaming 接口的传输档（alternate setting）清单。
     *
     * 背景：华为 JEF-AN00 + 某"240fps"摄像头，**所有格式所有帧率全部 native err=-51**
     * （连 YUYV@10fps 都谈不拢，与帧率无关）——嫌疑是该摄像头固件按 USB3 设计，
     * USB2 模式下没有任何塞得进带宽的传输档。isoc 带宽在 UVC 里由 VS 接口的
     * alt-setting 决定：libuvc 协商时要找一个 wMaxPacketSize 满足需求的 alt，
     * 找不到就 -51。把 alt 表解析出来打日志，插上设备即可定案，不用猜。
     *
     * wMaxPacketSize 编码（USB2 高速）：bits0-10=每微帧字节数，bits11-12=附加事务数
     * （每微帧有效载荷 = size × (1+addTrans)，上限 1024×3=3072；每秒 8000 微帧）。
     */
    data class AltEntry(
        val interfaceNum: Int,
        val altSetting: Int,
        val isocPayloadPerMicroframe: Int   // 0 = 该 alt 无 isoc 端点（如 alt0 零带宽档）
    ) {
        /** 该档理论带宽（字节/秒，USB2 高速 8000 微帧/秒） */
        val bytesPerSecond: Long get() = isocPayloadPerMicroframe.toLong() * 8000
    }

    fun parseAltSettings(raw: ByteArray): List<AltEntry> {
        val out = ArrayList<AltEntry>()
        var o = 0
        var curIfNum = -1
        var curAlt = -1
        var curIsVideoStreaming = false
        var curMaxPayload = 0
        fun flush() {
            if (curIsVideoStreaming && curIfNum >= 0) {
                out += AltEntry(curIfNum, curAlt, curMaxPayload)
            }
        }
        while (o + 2 <= raw.size) {
            val len = raw[o].toInt() and 0xFF
            if (len < 2 || o + len > raw.size) break
            when (raw[o + 1].toInt() and 0xFF) {
                0x04 -> if (len >= 9) {   // Standard Interface Descriptor
                    flush()
                    curIfNum = raw[o + 2].toInt() and 0xFF
                    curAlt = raw[o + 3].toInt() and 0xFF
                    // class 0x0E=CC_VIDEO, subclass 0x02=SC_VIDEOSTREAMING
                    curIsVideoStreaming = (raw[o + 5].toInt() and 0xFF) == 0x0E
                            && (raw[o + 6].toInt() and 0xFF) == 0x02
                    curMaxPayload = 0
                }
                0x05 -> if (len >= 7 && curIsVideoStreaming) {   // Endpoint Descriptor
                    val attrs = raw[o + 3].toInt() and 0x03
                    if (attrs == 0x01) {   // isochronous
                        val wMax = le16(raw, o + 4)
                        val size = wMax and 0x07FF
                        val addTrans = (wMax shr 11) and 0x03
                        val payload = size * (1 + addTrans)
                        if (payload > curMaxPayload) curMaxPayload = payload
                    }
                }
            }
            o += len
        }
        flush()
        return out
    }

    fun parse(raw: ByteArray): List<FrameEntry> {
        val out = ArrayList<FrameEntry>()
        var o = 0
        var curFormat = 0        // 0 = 当前不在已识别的视频格式区
        var curFormatIndex = 0   // 当前格式描述符的 bFormatIndex
        var curIfNum = -1        // 当前标准接口描述符的接口号（VS 格式/帧描述符挂在它下面）
        while (o + 2 <= raw.size) {
            val len = raw[o].toInt() and 0xFF
            if (len < 2 || o + len > raw.size) break
            val dtype = raw[o + 1].toInt() and 0xFF
            if (dtype == 0x04 && len >= 9) {   // Standard Interface Descriptor：跟踪接口号
                curIfNum = raw[o + 2].toInt() and 0xFF
            }
            if (dtype == 0x24 && len >= 3) {   // CS_INTERFACE
                when (raw[o + 2].toInt() and 0xFF) {
                    0x06 -> { curFormat = FORMAT_MJPEG; curFormatIndex = if (len >= 4) raw[o + 3].toInt() and 0xFF else 0 }  // VS_FORMAT_MJPEG
                    0x04 -> { curFormat = FORMAT_YUYV;  curFormatIndex = if (len >= 4) raw[o + 3].toInt() and 0xFF else 0 }  // VS_FORMAT_UNCOMPRESSED
                    0x05, 0x07 -> if (curFormat != 0 && len >= 30) {   // VS_FRAME_*
                        val frameIndex = raw[o + 3].toInt() and 0xFF
                        val w = le16(raw, o + 5)
                        val h = le16(raw, o + 7)
                        val nIv = raw[o + 25].toInt() and 0xFF
                        // fps → dwFrameInterval（100ns）映射：PROBE 谈判诊断请求报文要用真间隔值
                        val ivByFps = LinkedHashMap<Int, Long>()
                        if (nIv == 0) {
                            // 连续区间：min 间隔 = 最高帧率，max 间隔 = 最低帧率
                            if (len >= 38) {
                                val minIv = le32(raw, o + 26)
                                val maxIv = le32(raw, o + 30)
                                if (minIv > 0) ivByFps[(10_000_000L / minIv).toInt()] = minIv
                                if (maxIv > 0) ivByFps.putIfAbsent((10_000_000L / maxIv).toInt(), maxIv)
                            }
                        } else {
                            for (i in 0 until nIv) {
                                val p = o + 26 + i * 4
                                if (p + 4 > o + len) break
                                val iv = le32(raw, p)
                                if (iv > 0) ivByFps.putIfAbsent(Math.round(10_000_000.0 / iv).toInt(), iv)
                            }
                        }
                        val cleaned = ivByFps.keys.filter { it in 1..1000 }.distinct().sortedDescending()
                        if (w > 0 && h > 0 && cleaned.isNotEmpty()) {
                            out += FrameEntry(curFormat, w, h, cleaned,
                                formatIndex = curFormatIndex, frameIndex = frameIndex,
                                vsInterface = curIfNum, ivByFps = ivByFps)
                        }
                    }
                    // 其余 subtype（VS_COLORFORMAT 等）不改变 curFormat
                }
            }
            o += len
        }
        return out
    }

    // MARK: - §56.17 PROBE 谈判诊断（回答"为什么 MJPEG 协商失败"）

    /**
     * 一次 PROBE 谈判的结果。
     * [dwMaxPayloadTransferSize] 是设备对该档的**带宽要价**（每次载荷传输的字节数，isoc 下
     * = 每微帧）。libuvc 协商时拿它在 VS 接口的 alt-setting 表里找传输档（见 stream.c：
     * `config_bytes_per_packet >= ctrl->dwMaxPayloadTransferSize` 才选用），一个都塞不下
     * 就返回 UVC_ERROR_INVALID_MODE(-51)——即日志里 prepare_preview err=-51 的唯一来源之一。
     */
    data class ProbeResult(
        val ok: Boolean,
        val error: String? = null,
        val dwMaxVideoFrameSize: Long = 0,
        val dwMaxPayloadTransferSize: Long = 0
    )

    /**
     * 自己向设备做一遍 UVC PROBE（SET_CUR + GET_CUR，走 ep0 控制传输，不 COMMIT 不起流，
     * 按 UVC 规范 PROBE 只是谈判草稿区、无副作用）。**必须在 native 起流（claim VS 接口）之前调**：
     * usbfs 对 recipient=interface 的控制传输会自动 claim 接口，native 已占用时会被拒。
     */
    fun probeNegotiate(conn: android.hardware.usb.UsbDeviceConnection, entry: FrameEntry, fps: Int): ProbeResult {
        val iv = entry.ivByFps[fps] ?: return ProbeResult(false, "描述符无该fps的帧间隔")
        if (entry.vsInterface < 0 || entry.formatIndex <= 0 || entry.frameIndex <= 0) {
            return ProbeResult(false, "描述符缺格式/帧索引(fmt=${entry.formatIndex} frm=${entry.frameIndex} if=${entry.vsInterface})")
        }
        val buf = ByteArray(26)   // UVC 1.1 Probe/Commit 布局，1.5 设备也认前 26 字节
        buf[0] = 0x01             // bmHint bit0：dwFrameInterval 固定，让设备照此报要价
        buf[2] = entry.formatIndex.toByte()
        buf[3] = entry.frameIndex.toByte()
        buf[4] = (iv and 0xFF).toByte()
        buf[5] = ((iv shr 8) and 0xFF).toByte()
        buf[6] = ((iv shr 16) and 0xFF).toByte()
        buf[7] = ((iv shr 24) and 0xFF).toByte()
        // SET_CUR(0x01) / GET_CUR(0x81)，wValue = VS_PROBE_CONTROL(0x01)<<8，wIndex = VS 接口号
        val set = conn.controlTransfer(0x21, 0x01, 0x0100, entry.vsInterface, buf, buf.size, 500)
        if (set < 0) return ProbeResult(false, "SET_CUR(PROBE)被拒 ret=$set（接口被占/设备不响应）")
        val back = ByteArray(34)
        val got = conn.controlTransfer(0xA1, 0x81, 0x0100, entry.vsInterface, back, back.size, 500)
        if (got < 26) return ProbeResult(false, "GET_CUR(PROBE)失败 ret=$got")
        return ProbeResult(true,
            dwMaxVideoFrameSize = le32(back, 18),
            dwMaxPayloadTransferSize = le32(back, 22))
    }
}
