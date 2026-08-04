package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log

/**
 * H265 (HEVC) P2P 支持 —— Android 全部 H265 专属逻辑集中在本文件，与既有 H264 链路解耦。
 * 完全对标 iOS `H265Support.swift`（第三十一章）。
 *
 * 设计原则（用户要求）：
 *   1. 不散落在 WebRTCManager / P2PManager / LoginScreen 里，旧类只留一行钩子。
 *   2. H265 日志与 H264 完全分开：上报前缀 Android-p2p → Android-p2p-h265（后端白名单已加）。
 *   3. P2P 与 SRS 各自独立选择编码（第四十九章）：
 *      - P2P：key p2p_video_codec，默认 h265（历史行为，不动）；
 *      - SRS：key srs_video_codec，默认 h264（新增，SRS 服务器 6.0.184 已 --h265=on）。
 *      两者互不影响；SRS 会话也会像 P2P 一样对 Offer 做 codec munge。
 *
 * 生效链路：
 *   登录页 P2P 选中时出现「P2P编码 H264/H265」二级选项
 *   → login_prefs("selected_video_codec") 记忆 + token_prefs("p2p_video_codec") 运行时
 *   → WebRTCManager.initialize() 时 registerSupportedCodecs() 探测编码器是否带 H265
 *   → startPublish P2P 分支 decideForP2P()：选 H265 且硬编支持 → effective=h265
 *   → P2PManager 三处 Offer（首发/ICE重试/切中继）：H265 会话走 mungeOfferH265()
 *     （m=video 限定 H265+RTX，算法同 forceH264InVideoSection；Offer 里没有 H265 时
 *      自动回落 H264 munge 并降级 effective，不破坏协商）
 *   → CONFIG_STATE 上报 state.videoCodec（PC 据此预建 H265 解码管线）
 *
 * ⚠️ 依赖：stream-webrtc-android 的 DefaultVideoEncoderFactory 是否暴露 H265 硬编取决于
 *   libwebrtc 版本与设备 MediaCodec；探测不到时自动回落 H264 并打日志（与 iOS <M146 行为一致）。
 */
object H265Support {

    private const val TAG = "H265Support"

    /** 登录页 UI 记忆 key（login_prefs）——P2P 编码 */
    const val PREFS_UI_KEY = "selected_video_codec"
    /** 运行时决策 key（token_prefs，登录成功时写入，与 connect_mode 同处）——P2P 编码 */
    const val PREFS_RUNTIME_KEY = "p2p_video_codec"
    /** 登录页 UI 记忆 key（login_prefs）——SRS 编码（第四十九章新增，默认 h264） */
    const val PREFS_UI_KEY_SRS = "selected_video_codec_srs"
    /** 运行时决策 key（token_prefs）——SRS 编码 */
    const val PREFS_RUNTIME_KEY_SRS = "srs_video_codec"

    /** 编码器工厂是否带 H265（WebRTCManager.initialize 时注册探测） */
    @Volatile var sdkSupportsH265 = false
        private set

    /** 当前会话实际生效编码（"h264"/"h265"，推流时定案；CONFIG_STATE/日志前缀/左上角显示共用） */
    @Volatile var effectiveCodec = "h264"
        private set

    /** 左上角状态条显示用 */
    fun codecLabel(): String = if (effectiveCodec == "h265") "H265" else "H264"

    fun isH265Session(): Boolean = effectiveCodec == "h265"

    // ---------- 钩子 1：WebRTCManager.initialize() 创建 encoder factory 后调 ----------

    /** 注册编码器能力（传 DefaultVideoEncoderFactory.supportedCodecs 的 name 列表） */
    fun registerSupportedCodecs(codecNames: List<String>) {
        sdkSupportsH265 = codecNames.any { it.equals("H265", true) || it.equals("HEVC", true) }
        log("编码器能力探测: H265=${if (sdkSupportsH265) "支持✅" else "不支持❌(libwebrtc/设备无H265硬编)"} 全部=$codecNames")
    }

    // ---------- 钩子 2'：§53.4-定稿 —— 按 SessionPolicy 定案的编码落地（登录页不再让用户选） ----------

    /**
     * 推流前由 `SessionPolicy` 定案 codec 后调用（P2P / SRS 共用）。
     *
     * 与旧的 `decideForP2P/decideForSrs` 的区别：**不再自己读 prefs 里的用户选择**——
     * 编码由 SessionPolicy 综合「服务器默认(总后台可配) + 观看端内核能否收 H265 + 本机能否硬编」
     * 一次算好，这里只负责落地 effectiveCodec。这样"谁决定编码"只有一个地方，不会两处打架。
     * 指定 H265 但本机编码器不支持 → 如实回退 H264（用户口径：不支持就回退）。
     */
    fun applyDecidedCodec(codec: String, mode: String): String {
        effectiveCodec = if (codec == "h265") {
            if (sdkSupportsH265) {
                log("✅ $mode 会话编码 → H265（Offer 将限定 H265）")
                "h265"
            } else {
                log("⚠️ $mode 定案 H265 但本机编码器不支持，回退 H264")
                "h264"
            }
        } else {
            log("ℹ️ $mode 会话编码 → H264")
            "h264"
        }
        return effectiveCodec
    }

    // ---------- 钩子 2：startPublish P2P 分支调（每次推流定案） ----------

    /** P2P 推流前按登录页选择定案编码。选 H265 但不支持 → 回落 H264 并打日志。 */
    fun decideForP2P(context: Context?): String {
        val selected = context?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            ?.getString(PREFS_RUNTIME_KEY, "h264")?.lowercase() ?: "h264"
        effectiveCodec = if (selected == "h265") {
            if (sdkSupportsH265) {
                log("✅ P2P 会话编码 → H265（Offer 将限定 H265，PC 需已建 H265 管线）")
                "h265"
            } else {
                log("⚠️ 选了 H265 但编码器不支持，回落 H264")
                "h264"
            }
        } else "h264"
        return effectiveCodec
    }

    // ---------- 钩子 3a：SRS 分支调（第四十九章：SRS 也可选 H265，默认 h264） ----------

    /** SRS 推流前按登录页 SRS 编码选择定案。选 H265 但硬编不支持 → 回落 H264 并打日志。 */
    fun decideForSrs(context: Context?): String {
        val selected = context?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            ?.getString(PREFS_RUNTIME_KEY_SRS, "h264")?.lowercase() ?: "h264"
        effectiveCodec = if (selected == "h265") {
            if (sdkSupportsH265) {
                log("✅ SRS 会话编码 → H265（Offer 将限定 H265，SRS 6.0.184 已支持，PC 需按上报建 H265 管线）")
                "h265"
            } else {
                log("⚠️ SRS 选了 H265 但编码器不支持，回落 H264")
                "h264"
            }
        } else "h264"
        return effectiveCodec
    }

    // ---------- 钩子 3b：非推流场景兜底（保留兼容） ----------

    fun forceH264ForNonP2P() {
        effectiveCodec = "h264"
    }

    // ---------- 钩子 4：P2PManager 三处 Offer munge 的 H265 分支 ----------

    /**
     * H265 会话的 Offer munge：m=video 限定 H265（含关联 RTX），算法与
     * P2PManager.forceH264InVideoSection 完全一致，仅换 codec 名。
     * Offer 里没有 H265（编码器实际没吐出来）→ 回落 H264 munge + 降级 effective，不破坏协商。
     */
    fun mungeOfferH265(sdp: String): String {
        val h265Munged = forceCodecInVideoSection(sdp, "H265")
        if (h265Munged != null) {
            log("🎬 Offer 已限定 H265")
            return h265Munged
        }
        // 回落：Offer 无 H265 → 按 H264 限定，effective 同步降级（CONFIG_STATE 会如实上报 h264）
        log("⚠️ Offer 里无 H265 rtpmap（编码器未启用），回落 H264 munge")
        effectiveCodec = "h264"
        return forceCodecInVideoSection(sdp, "H264") ?: sdp
    }

    /**
     * 把 m=video 段限定为指定 codec（含其关联 RTX）。
     * 找不到该 codec 或解析异常时返回 null（由调用方决定回落策略）。
     * 算法拷贝自 P2PManager.forceH264InVideoSection（保持行为一致）。
     */
    private fun forceCodecInVideoSection(sdp: String, codecName: String): String? {
        return try {
            val lines = sdp.split("\r\n").toMutableList()
            val mVideoIdx = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mVideoIdx < 0) return null
            var sectionEnd = lines.size
            for (i in mVideoIdx + 1 until lines.size) {
                if (lines[i].startsWith("m=")) { sectionEnd = i; break }
            }

            val rtpmapRe = Regex("^a=rtpmap:(\\d+)\\s+([^/]+)/")
            val fmtpAptRe = Regex("^a=fmtp:(\\d+)\\s+.*apt=(\\d+)")
            val codecPts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = rtpmapRe.find(lines[i]) ?: continue
                if (m.groupValues[2].equals(codecName, ignoreCase = true)) codecPts.add(m.groupValues[1])
            }
            if (codecPts.isEmpty()) return null

            val rtxPts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = fmtpAptRe.find(lines[i]) ?: continue
                if (m.groupValues[2] in codecPts) rtxPts.add(m.groupValues[1])
            }
            val keep = codecPts + rtxPts

            val mTokens = lines[mVideoIdx].split(" ")
            if (mTokens.size <= 3) return null
            lines[mVideoIdx] = (mTokens.take(3) + codecPts + rtxPts).joinToString(" ")

            val ptAttrRe = Regex("^a=(rtpmap|rtcp-fb|fmtp):(\\d+)[\\s:]?")
            for (i in sectionEnd - 1 downTo mVideoIdx + 1) {
                val m = ptAttrRe.find(lines[i]) ?: continue
                if (m.groupValues[2] !in keep) lines.removeAt(i)
            }
            Log.d(TAG, "🎬 Offer 已限定 $codecName: pt=${codecPts.joinToString("/")}, rtx=${rtxPts.joinToString("/")}")
            lines.joinToString("\r\n")
        } catch (e: Exception) {
            Log.e(TAG, "forceCodecInVideoSection($codecName) 解析失败: ${e.message}")
            null
        }
    }

    // ---------- 钩子 5：日志前缀（H265 日志与 H264 完全分开，后端分文件落盘） ----------

    /** P2PLogReporter 上报前缀：H265 会话 → base-h265（Android-p2p-h265），H264 原样 */
    fun logUploadPrefix(base: String): String =
        if (isH265Session()) "$base-h265" else base

    // ---------- H265 专属打印（带 [H265] 标记，P2PLogReporter 的 meidui 标签可捕获） ----------

    fun log(msg: String) {
        // 打到 meidui 标签下（P2PLogReporter 按标签采集上报），同时留 H265Support 标签便于本地过滤
        Log.d("meidui", "🎞️ [H265] $msg")
        Log.d(TAG, msg)
    }
}
