package com.fz.yqlandroid.manager.uvc

import android.util.Log
import com.fz.yqlandroid.manager.WebRTCManager

/**
 * ⭐ 第五十章：OTG(外接UVC) 专用配置路由 —— 与自带摄像头(Camera2)的下发通道**彻底分家**。
 *
 * 为什么分开：OTG 的分辨率是设备枚举出来的列表（这台 7 档、那台 3 档，尺寸各不相同），
 * 跟自带摄像头那套固定 5 档（标清/高清/超清/超高帧/超高清）根本不是一回事；硬件可调项也逐台不同。
 * 混在同一批 ptype 里迟早互相污染，所以 PC 对 OTG 设备一律发 **`otg_` 前缀**的 ptype，
 * 由本类独立消费；`WebRTCManager.applyRemoteConfig` 那套老 ptype 在 OTG 模式下直接忽略。
 *
 * 共用的只有那条 STOMP 长连接，以及"落地"环节的既有实现（编码器帧率/码率/关键帧——
 * 这些与镜头类型无关，逻辑照旧，只是入口分开）。
 *
 * 协议（PC → Android，走 `/topic/device/{id}/config` 的 CONFIG_UPDATE）：
 * | ptype | 载荷 | 含义 |
 * |---|---|---|
 * | `otg_resolution` | `{width,height,fps?,format?}` | 档位=分辨率；fps 为**精确**请求值；format 0=自动/1=MJPEG/2=YUYV |
 * | `otg_fps`        | `{fps}`              | 推送帧率（真实值，**不做 ÷4**，与老通道的历史怪协议解耦） |
 * | `otg_bitrate`    | `{bitrate}`          | 码率百分比 0~100 |
 * | `otg_ctrl`       | `{key,value}`        | 硬件可调项，key/值域见能力快照 controls |
 * | `otg_get_caps`   | `{}`                 | 请求重推一次能力快照 |
 * | `otg_reset`      | `{}`                 | 硬件项还原到出厂缺省（相机打开时记下的原始值） |
 */
class OtgConfigRouter(private val mgr: WebRTCManager) {

    companion object {
        private const val LOG = "meidui"
        /** PC 侧判断"这条要不要走 OTG 通道"的统一前缀 */
        const val PREFIX = "otg_"
    }

    /** 能力快照请求回调（WebRTCManager 挂上，转给 WebSocketManager 上报） */
    var onCapsRequested: (() -> Unit)? = null

    /**
     * 消费一条 OTG 配置。
     * @return true=本类已处理（调用方直接 return）；false=不是 OTG 通道的消息，交回原逻辑
     */
    fun handle(ptype: String, config: Map<String, Any>): Boolean {
        if (!ptype.startsWith(PREFIX)) return false
        // ⭐ 全链路日志锚点①：PC 下发的每一条 otg_ 指令到达即打印（含完整载荷）。
        //   排查口诀：日志里没有这行 = PC 没发出来/信令没到；有这行没后续 = 看下面哪步断了。
        Log.d(LOG, "🔗 [OTG链路|收到] ptype=$ptype config=$config")
        when (ptype) {
            "otg_resolution" -> {
                val w = (config["width"] as? Number)?.toInt() ?: 0
                val h = (config["height"] as? Number)?.toInt() ?: 0
                if (w <= 0 || h <= 0) {
                    Log.d(LOG, "🔌 [OTG档位] ❌ 分辨率缺失: $config")
                    return true
                }
                val fps = (config["fps"] as? Number)?.toInt() ?: 0
                // format: 0=自动 / 1=MJPEG / 2=YUYV（缺省 0，老版本 PC 不带这个字段）
                val format = (config["format"] as? Number)?.toInt() ?: 0
                mgr.applyOtgResolution(w, h, fps, format)
            }

            "otg_fps" -> {
                val fps = (config["fps"] as? Number)?.toInt()
                if (fps == null || fps <= 0) {
                    Log.d(LOG, "🔌 [OTG] ❌ otg_fps 值非法: $config")
                    return true
                }
                mgr.setPushFps(fps)
                Log.d(LOG, "🔌 [OTG] 推送帧率 → ${fps}fps")
            }

            "otg_bitrate" -> {
                val pct = (config["bitrate"] as? Number)?.toInt()
                if (pct == null) {
                    Log.d(LOG, "🔌 [OTG] ❌ otg_bitrate 值非法: $config")
                    return true
                }
                // 天花板按当前分辨率的像素率算（OtgBitratePlan），不用自带摄像头那套 ladder
                mgr.setOtgQualityPercentage(pct)
            }

            "otg_ctrl" -> {
                val key = config["key"] as? String ?: ""
                val value = (config["value"] as? Number)?.toInt()
                if (key.isEmpty() || value == null) {
                    Log.d(LOG, "🔌 [OTG控制] ❌ 载荷非法: $config")
                    return true
                }
                mgr.otgCapturer()?.applyControl(key, value)
                    ?: Log.d(LOG, "🔌 [OTG控制] $key=$value 忽略：当前不是 OTG 采集器")
            }

            "otg_get_caps" -> {
                Log.d(LOG, "🔌 [OTG] PC 请求能力快照 → 重推一次")
                onCapsRequested?.invoke()
            }

            "otg_reset" -> {
                // 还原：硬件项回落到相机打开时记下的**出厂缺省**（每台设备缺省点位不同，
                // 不能由 PC 猜一个中间值发下来）。回落完会自动重推能力快照。
                Log.d(LOG, "🔌 [OTG] PC 请求还原硬件项 → 回落出厂缺省")
                mgr.otgCapturer()?.resetControlsToDefault()
                    ?: Log.d(LOG, "🔌 [OTG还原] 忽略：当前不是 OTG 采集器")
            }

            else -> Log.d(LOG, "🔌 [OTG] 未知 otg_ ptype=$ptype，忽略（config=$config）")
        }
        return true
    }
}
