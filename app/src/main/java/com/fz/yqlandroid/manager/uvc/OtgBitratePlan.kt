package com.fz.yqlandroid.manager.uvc

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ⭐ 第五十章：OTG 码率上限的唯一计算处。
 *
 * ## 为什么 OTG 不能用自带摄像头那套 ladder
 * `currentLadder` 是按 Camera2 枚举出的分辨率建的，跟 UVC 设备毫无关系。
 * 之前 OTG 的码率百分比落在 `currentLadder[currentProfile].maxKbps` 上，
 * 等于不管实际跑 640x480 还是 1080p，天花板都是同一个无关的值（默认档 3000kbps）。
 *
 * ## 第一版算错了（2026-07-26 实测暴露）
 * 首版以「**该设备枚举出的最大分辨率**」为锚点、给现有最高档 P4K 的 4000kbps。
 * 在最大只有 640x480 的 UVC 设备上，640x480 就直接吃满了本该给 1600x1200 的 4000kbps ——
 * 锚点跟着设备走 = 没有绝对基准，设备越差码率给得越离谱。
 *
 * ## 现在的算法：绝对基准 + 两段缩放
 * ```
 * kbps = VGA_ANCHOR_KBPS × (w×h / 640×480) × sqrt(fps / 30)
 * clamp 到 [MIN_KBPS, MAX_KBPS]
 * ```
 * - **分辨率线性**：像素多一倍，码率多一倍。
 * - **帧率开根号**：帧率翻倍时码率**不能**跟着翻倍——120fps 的相邻帧几乎一样、
 *   P 帧极小，线性给等于白扔带宽。开根号后 640x480 从 @30 的 2000k 涨到 @120 的 4000k，
 *   每帧预算 8.3KB → 4.2KB（0.217 → 0.109 bpp），仍在 H264/H265 的可用区间内。
 * - **绝对锚点**：640x480@30 = 2000kbps，与设备自身能力无关。
 *
 * 算出的每档上限随能力快照上报 PC，面板直接显示"x% ≈ y kbps"，公式不在 PC 侧重复实现。
 */
object OtgBitratePlan {

    /** 绝对锚点：640x480@30fps 给多少 kbps。调码率整体松紧改这一个数即可 */
    const val VGA_ANCHOR_KBPS = 2000

    /** 锚点分辨率的像素数与帧率 */
    private const val VGA_PIXELS = 640 * 480
    private const val ANCHOR_FPS = 30.0

    /** 再小的分辨率也不低于这个值，否则画面糊到没意义 */
    const val MIN_KBPS = 300

    /** 再大的分辨率也不超过这个值（与现网最高档 P4K 对齐，别把上行打满） */
    const val MAX_KBPS = 4000

    /** min/max 比例，与 ladder 各档一致 */
    private const val MIN_RATIO = 0.6

    /** 设备没报 fps 上限时按 30 算 */
    private const val DEFAULT_FPS = 30

    /**
     * 算一档分辨率的码率上限。
     * @param fps 该档的帧率；<=0 表示设备没报，按 30 算
     */
    fun ceilingFor(width: Int, height: Int, fps: Int): Int {
        if (width <= 0 || height <= 0) return VGA_ANCHOR_KBPS
        val effFps = if (fps > 0) fps else DEFAULT_FPS
        val pixelRatio = (width.toDouble() * height) / VGA_PIXELS
        val fpsRatio = sqrt(effFps / ANCHOR_FPS)
        val kbps = (VGA_ANCHOR_KBPS * pixelRatio * fpsRatio).roundToInt()
        return kbps.coerceIn(MIN_KBPS, MAX_KBPS)
    }

    /** 给能力快照里的每一档分辨率填上码率上限（PC 面板据此显示实际 kbps） */
    fun annotate(sizes: List<UvcCapabilityStore.SizeOption>): List<UvcCapabilityStore.SizeOption> =
        sizes.map { it.copy(maxKbps = ceilingFor(it.width, it.height, it.maxFps)) }

    fun minKbpsOf(maxKbps: Int): Int = (maxKbps * MIN_RATIO).roundToInt().coerceAtLeast(MIN_KBPS)
}
