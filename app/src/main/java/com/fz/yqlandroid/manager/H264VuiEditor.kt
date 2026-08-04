package com.fz.yqlandroid.manager

/**
 * 🎨 H264 SPS 的 VUI 改写器 —— 写入 BT.709 + full-range 色彩描述。
 *
 * 输入/输出均为 Annex-B 码流（含起始码 00 00 01 / 00 00 00 01 的一段或多段 NALU）。
 * 只改写 SPS(nal_unit_type=7)，其余 NALU 原样保留。
 *
 * 处理流程：
 *   1. 按起始码切分 NALU
 *   2. 命中 SPS：去除防竞争字节(EBSP→RBSP) → 逐字段解析并重发到 vui_present 标志 →
 *      强制写入自定义 VUI(仅 video_signal_type + colour_description) → rbsp_trailing_bits →
 *      重新插入防竞争字节(RBSP→EBSP) → 复原 NAL 头与起始码
 *   3. 拼回完整码流
 *
 * 全程无浮点、无逐像素运算；仅在关键帧执行一次，功耗可忽略。
 */
object H264VuiEditor {

    /** 需要 high-profile 扩展字段的 profile_idc 集合（含 chroma_format_idc 等） */
    private val HIGH_PROFILES = intArrayOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
    )

    /**
     * 把 Annex-B 码流里的 SPS 改写为 BT.709 + full-range。
     * @return 改写后的码流；无 SPS 或解析失败返回 null（调用方透传原帧）。
     */
    fun setBt709FullRange(annexB: ByteArray): ByteArray? {
        val nalus = splitAnnexB(annexB) ?: return null
        var changed = false
        val out = ArrayList<ByteArray>(nalus.size)

        for (n in nalus) {
            val nalType = n.payload[0].toInt() and 0x1F
            if (nalType == 7) {  // SPS
                val rewritten = rewriteSps(n.payload)
                if (rewritten != null) {
                    out.add(concat(n.startCode, rewritten))
                    changed = true
                } else {
                    out.add(concat(n.startCode, n.payload))
                }
            } else {
                out.add(concat(n.startCode, n.payload))
            }
        }
        if (!changed) return null

        var total = 0
        for (b in out) total += b.size
        val result = ByteArray(total)
        var pos = 0
        for (b in out) { System.arraycopy(b, 0, result, pos, b.size); pos += b.size }
        return result
    }

    // ==================== NALU 切分 ====================

    private class Nalu(val startCode: ByteArray, val payload: ByteArray)

    /** 按起始码切分；返回每个 NALU 的(起始码, 负载)。非 Annex-B 返回 null。 */
    private fun splitAnnexB(data: ByteArray): List<Nalu>? {
        val starts = ArrayList<Int>()      // 每个起始码的起始下标
        val startLens = ArrayList<Int>()   // 对应起始码长度(3 或 4)
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i); startLens.add(3); i += 3; continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i); startLens.add(4); i += 4; continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return null

        val nalus = ArrayList<Nalu>(starts.size)
        for (k in starts.indices) {
            val scStart = starts[k]
            val scLen = startLens[k]
            val payloadStart = scStart + scLen
            val payloadEnd = if (k + 1 < starts.size) starts[k + 1] else data.size
            if (payloadEnd <= payloadStart) continue
            val startCode = data.copyOfRange(scStart, payloadStart)
            val payload = data.copyOfRange(payloadStart, payloadEnd)
            nalus.add(Nalu(startCode, payload))
        }
        return if (nalus.isEmpty()) null else nalus
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        return r
    }

    // ==================== SPS 改写 ====================

    /** @param spsNal 含 1 字节 NAL 头的 SPS(EBSP)。返回改写后的 SPS NAL(EBSP)，失败 null。 */
    private fun rewriteSps(spsNal: ByteArray): ByteArray? {
        try {
            val nalHeader = spsNal[0]
            val ebsp = spsNal.copyOfRange(1, spsNal.size)
            val rbsp = ebspToRbsp(ebsp)

            val reader = BitReader(rbsp)
            val writer = BitWriter()

            // profile_idc / constraint+reserved / level_idc
            val profileIdc = reader.readBits(8); writer.writeBits(profileIdc, 8)
            writer.writeBits(reader.readBits(8), 8)   // constraint_set flags + reserved
            writer.writeBits(reader.readBits(8), 8)   // level_idc

            // seq_parameter_set_id
            writer.writeUE(reader.readUE())

            if (HIGH_PROFILES.contains(profileIdc)) {
                val chromaFormatIdc = reader.readUE(); writer.writeUE(chromaFormatIdc)
                if (chromaFormatIdc == 3) writer.writeBit(reader.readBit())  // separate_colour_plane_flag
                writer.writeUE(reader.readUE())  // bit_depth_luma_minus8
                writer.writeUE(reader.readUE())  // bit_depth_chroma_minus8
                writer.writeBit(reader.readBit()) // qpprime_y_zero_transform_bypass_flag
                val scalingPresent = reader.readBit(); writer.writeBit(scalingPresent)
                if (scalingPresent == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (idx in 0 until count) {
                        val listPresent = reader.readBit(); writer.writeBit(listPresent)
                        if (listPresent == 1) {
                            val sizeOfList = if (idx < 6) 16 else 64
                            copyScalingList(reader, writer, sizeOfList)
                        }
                    }
                }
            }

            writer.writeUE(reader.readUE())  // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE(); writer.writeUE(picOrderCntType)
            when (picOrderCntType) {
                0 -> writer.writeUE(reader.readUE())  // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    writer.writeBit(reader.readBit())        // delta_pic_order_always_zero_flag
                    writer.writeSE(reader.readSE())          // offset_for_non_ref_pic
                    writer.writeSE(reader.readSE())          // offset_for_top_to_bottom_field
                    val num = reader.readUE(); writer.writeUE(num)
                    for (idx in 0 until num) writer.writeSE(reader.readSE())
                }
            }

            writer.writeUE(reader.readUE())   // max_num_ref_frames
            writer.writeBit(reader.readBit()) // gaps_in_frame_num_value_allowed_flag
            writer.writeUE(reader.readUE())   // pic_width_in_mbs_minus1
            writer.writeUE(reader.readUE())   // pic_height_in_map_units_minus1
            val frameMbsOnly = reader.readBit(); writer.writeBit(frameMbsOnly)
            if (frameMbsOnly == 0) writer.writeBit(reader.readBit())  // mb_adaptive_frame_field_flag
            writer.writeBit(reader.readBit()) // direct_8x8_inference_flag
            val cropping = reader.readBit(); writer.writeBit(cropping)
            if (cropping == 1) {
                writer.writeUE(reader.readUE())  // frame_crop_left_offset
                writer.writeUE(reader.readUE())  // frame_crop_right_offset
                writer.writeUE(reader.readUE())  // frame_crop_top_offset
                writer.writeUE(reader.readUE())  // frame_crop_bottom_offset
            }

            // vui_parameters_present_flag —— 无论原值如何，强制置 1 并写自定义 VUI
            reader.readBit()  // 丢弃原标志（原 VUI 一并丢弃，改用我们的最小 VUI）
            writer.writeBit(1)
            writeColorVui(writer)

            // rbsp_trailing_bits
            writer.writeBit(1)
            writer.byteAlignWithZeros()

            val newRbsp = writer.toByteArray()
            val newEbsp = rbspToEbsp(newRbsp)
            val result = ByteArray(newEbsp.size + 1)
            result[0] = nalHeader
            System.arraycopy(newEbsp, 0, result, 1, newEbsp.size)
            return result
        } catch (e: Throwable) {
            return null
        }
    }

    /** 写入仅含 video_signal_type + colour_description 的最小 VUI，全部 BT.709 full-range。 */
    private fun writeColorVui(w: BitWriter) {
        w.writeBit(0)  // aspect_ratio_info_present_flag
        w.writeBit(0)  // overscan_info_present_flag
        w.writeBit(1)  // video_signal_type_present_flag
        w.writeBits(5, 3)   // video_format = 5 (Unspecified)
        w.writeBit(1)       // video_full_range_flag = 1 (满范围，对齐 iOS)
        w.writeBit(1)       // colour_description_present_flag = 1
        w.writeBits(1, 8)   // colour_primaries = 1 (BT.709)
        w.writeBits(1, 8)   // transfer_characteristics = 1 (BT.709)
        w.writeBits(1, 8)   // matrix_coefficients = 1 (BT.709)
        w.writeBit(0)  // chroma_loc_info_present_flag
        w.writeBit(0)  // timing_info_present_flag
        w.writeBit(0)  // nal_hrd_parameters_present_flag
        w.writeBit(0)  // vcl_hrd_parameters_present_flag
        w.writeBit(0)  // pic_struct_present_flag
        w.writeBit(0)  // bitstream_restriction_flag
    }

    private fun copyScalingList(reader: BitReader, writer: BitWriter, sizeOfList: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until sizeOfList) {
            if (nextScale != 0) {
                val deltaScale = reader.readSE(); writer.writeSE(deltaScale)
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    // ==================== 防竞争字节 ====================

    /** EBSP→RBSP：移除 emulation_prevention_three_byte(00 00 03 → 00 00) */
    private fun ebspToRbsp(ebsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(ebsp.size)
        var zeros = 0
        var i = 0
        while (i < ebsp.size) {
            val b = ebsp[i]
            if (zeros >= 2 && b.toInt() == 0x03 &&
                i + 1 < ebsp.size && (ebsp[i + 1].toInt() and 0xFF) <= 0x03
            ) {
                zeros = 0  // 跳过这个 0x03
                i++
                continue
            }
            out.add(b)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
            i++
        }
        val arr = ByteArray(out.size)
        for (k in out.indices) arr[k] = out[k]
        return arr
    }

    /** RBSP→EBSP：在 00 00 后紧跟 <=03 的字节前插入 0x03 */
    private fun rbspToEbsp(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 8)
        var zeros = 0
        for (b in rbsp) {
            if (zeros >= 2 && (b.toInt() and 0xFF) <= 0x03) {
                out.add(0x03.toByte())
                zeros = 0
            }
            out.add(b)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        val arr = ByteArray(out.size)
        for (k in out.indices) arr[k] = out[k]
        return arr
    }

    // ==================== 位读/写 + Exp-Golomb ====================

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS bit overflow")
            val b = (data[bytePos].toInt() ushr (7 - bitPos)) and 0x1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return b
        }

        fun readBits(n: Int): Int {
            var v = 0
            for (k in 0 until n) v = (v shl 1) or readBit()
            return v
        }

        /** ue(v) 无符号 Exp-Golomb */
        fun readUE(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw ArithmeticException("ue overflow")
            }
            if (leadingZeros == 0) return 0
            val suffix = readBits(leadingZeros)
            return (1 shl leadingZeros) - 1 + suffix
        }

        /** se(v) 有符号 Exp-Golomb */
        fun readSE(): Int {
            val ue = readUE()
            val sign = if (ue and 0x1 == 1) 1 else -1
            return sign * ((ue + 1) / 2)
        }
    }

    private class BitWriter {
        private val bytes = ArrayList<Byte>(64)
        private var current = 0
        private var bitCount = 0

        fun writeBit(bit: Int) {
            current = (current shl 1) or (bit and 0x1)
            bitCount++
            if (bitCount == 8) {
                bytes.add(current.toByte())
                current = 0
                bitCount = 0
            }
        }

        fun writeBits(value: Int, n: Int) {
            for (k in n - 1 downTo 0) writeBit((value ushr k) and 0x1)
        }

        fun writeUE(value: Int) {
            val code = value + 1
            var numBits = 0
            var tmp = code
            while (tmp != 0) { numBits++; tmp = tmp ushr 1 }
            // numBits-1 个前导 0
            for (k in 0 until numBits - 1) writeBit(0)
            for (k in numBits - 1 downTo 0) writeBit((code ushr k) and 0x1)
        }

        fun writeSE(value: Int) {
            val ue = if (value <= 0) (-2 * value) else (2 * value - 1)
            writeUE(ue)
        }

        fun byteAlignWithZeros() {
            while (bitCount != 0) writeBit(0)
        }

        fun toByteArray(): ByteArray {
            if (bitCount != 0) {
                // 理论上调用前已 byteAlign，这里兜底补零
                current = current shl (8 - bitCount)
                bytes.add(current.toByte())
                current = 0
                bitCount = 0
            }
            val arr = ByteArray(bytes.size)
            for (k in bytes.indices) arr[k] = bytes[k]
            return arr
        }
    }
}
