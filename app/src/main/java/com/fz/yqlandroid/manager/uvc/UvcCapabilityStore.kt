package com.fz.yqlandroid.manager.uvc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ⭐ 第四十八章 / 第五十章：OTG(UVC) 相机能力快照。
 *
 * 两份数据同源、用途不同：
 * - [lines]：人读文本，`StreamingScreen` 叠显在预览画面上（OTG 占用 USB 口无法 adb），
 *   同时打进 meidui 日志 → 后端「OTG日志」。
 * - [caps]：结构化快照，经 CONFIG_STATE/CAMERA_CAPS 上报 PC，PC 据此**动态生成**调节面板
 *   （支持哪几项就长哪几个控件，不支持的不渲染，而不是渲染成灰的按钮）。
 *
 * 逐台 UVC 设备能力不同，所有硬件控制项统一百分比 0~100（jiangdg libuvc 内部映射到设备各自的绝对 min~max）。
 */
object UvcCapabilityStore {

    /** 单个可调项。type: "pct"=0~100 百分比滑条 / "bool"=开关 / "enum"=分段选择 */
    data class Control(
        val key: String,
        val label: String,
        val type: String,
        val supported: Boolean,
        val cur: Int,
        val min: Int = 0,
        val max: Int = 100,
        val options: List<String> = emptyList()
    )

    /**
     * 一档分辨率 = PC 面板上的一个"档位"（设备枚举出几个就是几个，不再是固定 5 档）。
     * [maxKbps]    由 [OtgBitratePlan] 按像素率等比算出，PC 面板据此显示"码率 x% ≈ y kbps"。
     * [encodable]  硬件编码器吃不吃得下这个尺寸（见 [EncoderSizeLimits]）。
     *              false 的档位 PC 面板不给选——采集没问题但编码器一帧不出，选了必黑。
     * [encMaxFps]  该尺寸下编码器能编的最高帧率（推流 fps 的真实上限；0=查不到）。
     */
    data class SizeOption(
        val width: Int,
        val height: Int,
        val maxFps: Int,
        val maxKbps: Int = 0,
        val encodable: Boolean = true,
        val encMaxFps: Int = 0
    )

    /** 一台 UVC 设备的完整能力快照。[version] 变化即表示能力变了（换设备/重新协商），PC 据此决定是否重建面板 */
    data class Caps(
        val deviceName: String,
        val width: Int,
        val height: Int,
        /** 当前实际在用的采集格式："MJPEG" / "YUYV"（PC 面板显示，让"切了格式没生效"看得见） */
        val format: String = "",
        val sizes: List<SizeOption>,
        val controls: List<Control>,
        val version: Long
    )

    /**
     * 当前生效的推送帧率 / 码率百分比。
     * 这两项设备侧才是真值（初始值来自后端下发的配置，不是 PC 面板的缺省），
     * 随能力快照一起上报，PC 面板照着显示而不是自己猜一个。
     */
    @Volatile var pushFps: Int = 0
    @Volatile var bitratePct: Int = 0

    /** 当前热控推流上限（0=无限制）。"fps 拖不上去"十有八九是它摁的，必须让 PC 面板看见 */
    @Volatile var thermalCapFps: Int = 0

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _caps = MutableStateFlow<Caps?>(null)
    val caps: StateFlow<Caps?> = _caps

    /** PC 侧比对用：0 = 当前无 OTG 能力（未开流/自带摄像头模式） */
    val version: Long get() = _caps.value?.version ?: 0L

    fun set(newLines: List<String>, newCaps: Caps?) {
        _lines.value = newLines
        _caps.value = newCaps
    }

    fun clear() {
        _lines.value = emptyList()
        _caps.value = null
    }
}
