package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * ⭐ §77：自带摄像头 5 档「分辨率 / 帧率 / 码率」的**唯一取值处**。
 *
 * ## 为什么要有这个类
 * 这些数值原来是硬编码在 `WebRTCManager.calculateLadder()` 里的常量，每次运营想调码率
 * （典型场景：客户反馈某档糊/马赛克，或要压带宽成本）都得**改代码重新发版**，
 * iOS 还要等 App Store 审核。现在改成后端 `video.ladder.config` 下发、登录响应带回来，
 * 落盘后下次算档位就生效。
 *
 * ## 取值优先级
 * `后端下发值 > 本地内置默认值`，且**逐字段**判断——后端某项没配/配成 0 就用默认值那一项，
 * 不是整档回退。所以运营可以只调 `max`，分辨率和帧率照旧走设备自适应。
 *
 * ## 分辨率/帧率为什么默认「不覆盖」
 * Android 的档位分辨率只是**目标值**，实际采集分辨率由 `findBestResolution` 在设备
 * Camera2 枚举结果里就近选。配一个设备不支持的分辨率不会黑屏（会就近落到别的），
 * 但会打乱各档的梯度，所以除非明确要改，后台留空即可（=0=不覆盖）。
 *
 * ## 默认值：2026-08-18 各档 max 统一 +1500（min 保持 max 的 60%）
 * 原值 P4K 4000 / HIGH·ULTRA 3500 / STANDARD 3000 / LOW 1500 是 2026-07-10 下调后的，
 * 与 iOS 同档差了 2000~3000（iOS P4K 7000、STANDARD 4000），Android 画质明显吃亏。
 */
object LadderConfigStore {

    private const val TAG = "meidui"
    private const val PREFS = "token_prefs"
    private const val KEY_JSON = "video_ladder_config"

    /** 一档的完整参数。w/h/fps 为 0 = 该项不覆盖，沿用设备自适应结果 */
    data class Entry(val w: Int, val h: Int, val fps: Int, val maxKbps: Int, val minKbps: Int)

    /** 后台 JSON 里的档位名（与 iOS、后端三方约定一致，别改） */
    const val P4K = "p4k"
    const val HIGH = "high"
    const val ULTRA = "ultra"
    const val STANDARD = "standard"
    const val LOW = "low"

    /**
     * 内置默认值（后端没配时用这套）。
     * w/h = 目标分辨率（就近匹配的锚点），fps=0 表示不指定（由 desiredFps + 设备能力决定）。
     */
    private val DEFAULTS: Map<String, Entry> = mapOf(
        P4K to Entry(1920, 1440, 0, 5500, 3300),
        HIGH to Entry(1440, 1080, 0, 5000, 3000),
        ULTRA to Entry(1280, 720, 0, 5000, 3000),
        STANDARD to Entry(1024, 768, 0, 4500, 2700),
        LOW to Entry(640, 480, 0, 3000, 1800)
    )

    /** 后端下发值（登录时落盘，进程启动时 load 一次） */
    @Volatile private var remote: Map<String, Entry> = emptyMap()

    /** 后端是否配了「采集目标帧率」（对应 calculateLadder 的 desiredFps，0=不覆盖用 30） */
    @Volatile var remoteCaptureFps: Int = 0
        private set

    /**
     * 登录响应拿到 videoLadder 后调用：落盘 + 立即生效。
     * @param json 形如 {"captureFps":30,"p4k":{"w":1920,"h":1440,"fps":0,"max":5500,"min":3300}, ...}
     *             传 null/空串 = 后端没配，清掉旧的本地覆盖回到内置默认（便于后台改回默认值）
     */
    fun saveFromLogin(context: Context, json: String?) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, json ?: "").apply()
        } catch (e: Exception) {
            Log.d(TAG, "📐 [§77档位配置] 落盘失败: ${e.message}")
        }
        parse(json)
    }

    /** 进程启动/推流前调用，把上次登录存的配置读回内存 */
    fun load(context: Context) {
        val json = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, "")
        } catch (e: Exception) {
            null
        }
        parse(json)
    }

    private fun parse(json: String?) {
        if (json.isNullOrBlank()) {
            remote = emptyMap()
            remoteCaptureFps = 0
            Log.d(TAG, "📐 [§77档位配置] 后端未配置，使用内置默认值")
            return
        }
        try {
            val root = JSONObject(json)
            remoteCaptureFps = root.optInt("captureFps", 0).coerceIn(0, 240)
            val parsed = mutableMapOf<String, Entry>()
            for (name in listOf(P4K, HIGH, ULTRA, STANDARD, LOW)) {
                val o = root.optJSONObject(name) ?: continue
                val def = DEFAULTS[name]!!
                // 逐字段兜底：后端缺项/给 0 就用默认值那一项
                val max = o.optInt("max", 0).takeIf { it > 0 } ?: def.maxKbps
                val min = o.optInt("min", 0).takeIf { it > 0 } ?: (max * 0.6).toInt()
                parsed[name] = Entry(
                    w = o.optInt("w", 0),
                    h = o.optInt("h", 0),
                    fps = o.optInt("fps", 0).coerceIn(0, 240),
                    maxKbps = max,
                    // min 不能超过 max，否则编码器参数自相矛盾
                    minKbps = min.coerceAtMost(max)
                )
            }
            remote = parsed
            Log.d(TAG, "📐 [§77档位配置] 已应用后端下发: ${parsed.size}档, 采集帧率覆盖=$remoteCaptureFps")
            parsed.forEach { (k, v) ->
                Log.d(TAG, "📐   $k → ${v.minKbps}-${v.maxKbps}kbps" +
                        (if (v.w > 0 && v.h > 0) " 目标${v.w}x${v.h}" else " 目标分辨率不覆盖") +
                        (if (v.fps > 0) " @${v.fps}fps" else ""))
            }
        } catch (e: Exception) {
            remote = emptyMap()
            remoteCaptureFps = 0
            Log.d(TAG, "📐 [§77档位配置] 解析失败，回退内置默认: ${e.message}")
        }
    }

    /** 取某档最终参数（后端优先，逐字段回退默认） */
    fun entryOf(name: String): Entry = remote[name] ?: DEFAULTS[name] ?: DEFAULTS[STANDARD]!!

    fun nameOf(profile: LadderProfile): String = when (profile) {
        LadderProfile.P4K -> P4K
        LadderProfile.HIGH -> HIGH
        LadderProfile.ULTRA -> ULTRA
        LadderProfile.STANDARD -> STANDARD
        LadderProfile.LOW -> LOW
    }

    fun entryOf(profile: LadderProfile): Entry = entryOf(nameOf(profile))
}
