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
 * P2P 诊断日志上报器（第二十二章）
 *
 * 总后台「P2P日志」开关打开时，把本机 logcat 里的 P2P 诊断日志（meidui / P2PManager 标签，
 * 即 tt.txt 同源内容）批量上报到后端，按推流ID分流落盘，供总后台下载离线排查卡顿。
 *
 * - 前缀固定 "Android-p2p"；streamId = 推流ID（streamKey）
 * - 开关：GET /api/p2plog/config（活跃期间每 60s 复查；关闭时零上报、logcat 读取线程停掉）
 * - 上报：POST /api/p2plog/upload（每 10s 批量一次，单批上限 256KB）
 * - 采集方式 = `logcat --pid=<自身pid>`（应用读自己的日志无需任何权限），
 *   不改动任何既有 Log.d 调用点，覆盖所有 meidui/P2PManager 诊断行。
 * - 推流开始（startPublish 生成 streamKey 后）start，停流 stop。SRS/P2P 模式都采集
 *   （meidui 里 SRS 模式的攒帧诊断同样有价值），前缀统一 Android-p2p。
 */
object P2PLogReporter {

    private const val TAG = "P2PLogReporter"
    private const val PREFIX = "Android-p2p"
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val CONFIG_INTERVAL_MS = 60_000L
    private const val MAX_BATCH_BYTES = 256 * 1024
    private const val MAX_BUFFER_LINES = 5000          // 网络异常时防内存膨胀
    /** 上报的 logcat 标签（其余标签不采，防止刷爆） */
    private val CAPTURE_TAGS = listOf("meidui", "P2PManager", "jfh")

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

    /** 推流开始时调用（streamKey 已生成） */
    fun start(newStreamId: String) {
        streamId = newStreamId
        if (active) return
        active = true
        Log.d(TAG, "P2P日志上报器启动 streamId=$streamId（等服务器开关）")

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

    /** 停流时调用 */
    fun stop() {
        if (!active) return
        active = false
        scope.launch { flush() }   // 冲刷剩余
        stopLogcat()
        configJob?.cancel(); configJob = null
        flushJob?.cancel(); flushJob = null
        Log.d(TAG, "P2P日志上报器停止")
    }

    // MARK: - 开关

    private fun checkConfig() {
        try {
            val req = Request.Builder().url("${APIConfig.BASE_URL}/api/p2plog/config").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val newEnabled = JSONObject(body).optBoolean("enabled", false)
                if (newEnabled != enabled) {
                    Log.d(TAG, "服务器P2P日志开关: ${if (newEnabled) "开" else "关"}")
                }
                enabled = newEnabled
                // ⭐ 幂等自愈（修「第一次能上报、重新推流后不上报」）：
                //   本对象是进程级单例，stop() 停掉 logcat 采集线程后 enabled 仍是 true；
                //   第二次 start() 时开关值无变化，旧逻辑只在「值变化」时才 startLogcat →
                //   采集线程永远没人重启。现改为：只要开关=开就确保采集在跑（startLogcat
                //   自带 isActive 幂等保护），logcat 进程意外死亡也能在下轮复查自动拉起。
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

    // MARK: - logcat 采集（读自己进程的日志，无需权限、不改任何 Log 调用点）

    private fun startLogcat() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch {
            try {
                val pid = android.os.Process.myPid()
                // -T 1: 只从当前时刻开始（不倒灌历史）; -v time: 带时间戳; 按标签白名单过滤
                val filters = CAPTURE_TAGS.joinToString(" ") { "$it:D" } + " *:S"
                val cmd = "logcat --pid=$pid -T 1 -v time $filters"
                val proc = Runtime.getRuntime().exec(cmd)
                logcatProcess = proc
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (isActive && active && enabled) {
                        val line = reader.readLine() ?: break
                        synchronized(lock) {
                            if (bufferLines < MAX_BUFFER_LINES) {
                                buffer.append(line).append('\n')
                                bufferLines++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat 采集异常: ${e.message}")
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
                // ⭐ H265：H265 会话日志与 H264 分开（Android-p2p → Android-p2p-h265，后端分文件落盘）
                put("prefix", H265Support.logUploadPrefix(PREFIX))
                put("streamId", if (streamId.isEmpty()) "unknown" else streamId)
                put("content", content)
            }
            val req = Request.Builder()
                .url("${APIConfig.BASE_URL}/api/p2plog/upload")
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
