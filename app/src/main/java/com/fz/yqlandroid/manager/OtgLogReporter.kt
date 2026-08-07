package com.fz.yqlandroid.manager

import android.util.Log
import com.fz.yqlandroid.config.APIConfig
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 外接 OTG 摄像头诊断日志上报器（第四十八章b，与 P2PLogReporter 同构）
 *
 * 背景：OTG 摄像头插在手机 USB 口上时没法同时连 adb 看 logcat。总后台「OTG日志」开关
 * 打开后，把 UVC 链路诊断日志（设备识别/USB授权/开流/出帧/capFps）批量上报到后端，
 * 总后台「OTG日志」页按推流ID浏览下载。
 *
 * - 前缀固定 "Android-otg"；streamId = 推流ID（无推流时 otgpreview_时间戳）
 * - 开关：GET /api/otglog/config（活跃期间每 60s 复查；与 P2P 日志开关相互独立）
 * - 上报：POST /api/otglog/upload（每 10s 批量一次，单批上限 256KB）
 * - 采集 = `logcat --pid=<自身pid>`，标签白名单含 UvcVideoCapturer/UvcDeviceMonitor
 *   （🔌 [OTG] 诊断行大多在 meidui 标签下）。
 * - ⭐ 与 P2P 版的关键差异：开采时回放最近 500 行（-T 500）——OTG 的「检测到插入/
 *   请求USB权限/开流」等最要命的日志发生在启动瞬间，-T 1 会把它们漏掉。
 * - 生命周期：WebRTCManager.startPreview 检出 OTG 模式即 start，stopPreview 时 stop。
 */
object OtgLogReporter {

    private const val TAG = "OtgLogReporter"
    private const val PREFIX = "Android-otg"
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val CONFIG_INTERVAL_MS = 60_000L
    private const val MAX_BATCH_BYTES = 256 * 1024
    private const val MAX_BUFFER_LINES = 5000          // 网络异常时防内存膨胀
    private const val REPLAY_LINES = 500               // 开采时回放的历史行数
    /** 上报的 logcat 标签（其余标签不采，防止刷爆）。
     *  ⭐ 含 AUSBC/libuvc 的 native 标签（UVCCamera/UVCPreview/libUVCCamera/CameraUVC/USBMonitor）——
     *    「相机开流成功但无帧」的根因日志（isoc 传输失败/带宽不足/MJPEG不兼容）都在这些 tag 下。 */
    private val CAPTURE_TAGS = listOf(
        "meidui", "jfh", "UvcVideoCapturer", "UvcDeviceMonitor",
        "UVCCamera", "UVCPreview", "libUVCCamera", "libuvc", "CameraUVC", "USBMonitor", "UVCButtonCallback")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var configJob: Job? = null
    private var flushJob: Job? = null
    private var logcatJob: Job? = null
    private var logcatProcess: Process? = null

    private val buffer = StringBuilder()
    private var bufferLines = 0
    private val lock = Any()

    @Volatile private var active = false
    @Volatile private var enabled = false
    @Volatile private var streamId = ""

    /** ⭐ §56.15（2026-08-07）登录账号：随每批上报带给后端，日志目录名变成
     *  「账号_推流ID」——总后台 OTG 日志页直接看得出是哪个用户的日志。
     *  由 WebRTCManager 在 start() 之前设置（本 object 无 context，自己拿不到）。 */
    @Volatile var username = ""

    /** OTG 模式启动采集时调用（无推流时传空，内部生成预览会话ID） */
    fun start(newStreamId: String) {
        streamId = if (newStreamId.isNotEmpty()) newStreamId
                   else "otgpreview_${System.currentTimeMillis() / 1000}"
        if (active) return
        active = true
        Log.d(TAG, "OTG日志上报器启动 streamId=$streamId（等服务器开关）")

        configJob = scope.launch {
            while (isActive && active) {
                checkConfig()
                delay(CONFIG_INTERVAL_MS)
            }
        }
        flushJob = scope.launch {
            while (isActive && active) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** 停止预览/推流时调用 */
    fun stop() {
        if (!active) return
        active = false
        scope.launch { flush() }   // 冲刷剩余
        stopLogcat()
        configJob?.cancel(); configJob = null
        flushJob?.cancel(); flushJob = null
        Log.d(TAG, "OTG日志上报器停止")
    }

    // MARK: - 开关

    private fun checkConfig() {
        try {
            val req = Request.Builder().url("${APIConfig.BASE_URL}/api/otglog/config").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val newEnabled = JSONObject(body).optBoolean("enabled", false)
                if (newEnabled != enabled) {
                    Log.d(TAG, "服务器OTG日志开关: ${if (newEnabled) "开" else "关"}")
                }
                enabled = newEnabled
                // 幂等自愈：只要开关=开就确保采集在跑（同 P2PLogReporter 的修复）
                if (newEnabled) {
                    startLogcat()
                } else {
                    stopLogcat()
                    synchronized(lock) { buffer.setLength(0); bufferLines = 0 }
                }
            }
        } catch (e: Exception) {
            // 网络异常保持现状，下轮再查
        }
    }

    // MARK: - logcat 采集（读自己进程的日志，无需权限）

    /** 把一条自诊断信息直接塞进上传缓冲（⭐ 2026-08-03：logcat 起不来的机型，失败原因
     *  自己就是靠 logcat 传的——死循环。此通道绕过 logcat，保证后台至少能看到原因） */
    private fun bufferDiagLine(msg: String) {
        synchronized(lock) {
            buffer.append("[OtgLogReporter自诊断] ").append(msg).append('\n')
            bufferLines++
        }
    }

    /** ⭐ 2026-08-03 对外自诊断通道：UVC 开流/协商的关键失败点直接写这里（绕过 logcat）。
     *  背景：华为等 ROM 默认丢弃 Log.d——JEF-AN00 实测只上来 4 行 E 级 err=-51，
     *  看不到请求的是哪个 尺寸@fps@格式，等于盲修。带时间戳，与 logcat 行可对齐。 */
    fun diag(msg: String) {
        if (!active) return
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(lock) {
            if (bufferLines < MAX_BUFFER_LINES) {
                buffer.append("[诊断] ").append(ts).append(' ').append(msg).append('\n')
                bufferLines++
            }
        }
    }

    private fun startLogcat() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch {
            // ⭐ 2026-08-03：先写设备信息头（机型/系统/APK版本）——排查"哪台手机没传日志"
            //   时能直接对上是谁、跑的什么版本（老版 APK 是日志缺失的头号嫌疑）。
            bufferDiagLine("采集启动 model=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}" +
                    " sdk=${android.os.Build.VERSION.SDK_INT}" +
                    " appVer=${com.fz.yqlandroid.BuildConfig.VERSION_NAME}" +
                    " user=${username.ifEmpty { "未登录" }}")   // §56.15 头行带账号
            var gotAnyLine = false
            try {
                val pid = android.os.Process.myPid()
                // -T 500: 回放最近500行（OTG 插入/授权/开流日志发生在启动瞬间，不能漏）
                val filters = CAPTURE_TAGS.joinToString(" ") { "$it:D" } + " *:S"
                val cmd = "logcat --pid=$pid -T $REPLAY_LINES -v time $filters"
                val proc = Runtime.getRuntime().exec(cmd)
                logcatProcess = proc
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive && active && enabled) {
                        val line = reader.readLine() ?: break
                        gotAnyLine = true
                        synchronized(lock) {
                            if (bufferLines < MAX_BUFFER_LINES) {
                                buffer.append(line).append('\n')
                                bufferLines++
                            }
                        }
                    }
                }
                // ⭐ 进程秒退且一行都没吐（个别 ROM 禁 logcat）→ 上报原因，别再静默
                if (!gotAnyLine && active && enabled) {
                    bufferDiagLine("⚠️ logcat 进程退出且未输出任何日志（该机型可能限制应用读取日志）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat 采集异常: ${e.message}")
                bufferDiagLine("⚠️ logcat 采集失败: ${e.message}（该机型可能禁止应用执行 logcat）")
            } finally {
                stopLogcatProcess()
            }
        }
    }

    private fun stopLogcat() {
        logcatJob?.cancel(); logcatJob = null
        stopLogcatProcess()
    }

    private fun stopLogcatProcess() {
        try { logcatProcess?.destroy() } catch (_: Exception) {}
        logcatProcess = null
    }

    // MARK: - 上报

    private fun flush() {
        if (!enabled) return
        var content: String
        synchronized(lock) {
            if (buffer.isEmpty()) return
            content = buffer.toString()
            buffer.setLength(0)
            bufferLines = 0
        }
        if (content.toByteArray().size > MAX_BATCH_BYTES) {
            content = content.substring(content.length - MAX_BATCH_BYTES / 2)  // 超限只留最新
        }
        try {
            val json = JSONObject().apply {
                put("prefix", PREFIX)
                put("streamId", if (streamId.isEmpty()) "unknown" else streamId)
                put("content", content)
                // §56.15 带上登录账号：后端目录名变「账号_推流ID」，总后台列表直接区分是谁
                if (username.isNotEmpty()) put("username", username)
            }
            val req = Request.Builder()
                .url("${APIConfig.BASE_URL}/api/otglog/upload")
                .post(json.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                // 服务端已关闭开关 → 本地同步关（等下轮 config 复查再开）
                if (body != null && JSONObject(body).optBoolean("enabled", true).not()) {
                    enabled = false
                    stopLogcat()
                }
            }
        } catch (e: Exception) {
            // 上报失败丢弃本批（本地 logcat 里还有），不重试防堆积
        }
    }
}
