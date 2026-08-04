package com.fz.yqlandroid.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/**
 * 🔊 后台保活（对标 iOS `BackgroundAudioManager` 的无声音频保活 + `isIdleTimerDisabled`）
 *
 * 问题：Android 系统息屏 / App 切后台后，CPU 会进入休眠、后台进程被限制，
 *      导致 WebRTC 采集/编码/推流线程被挂起 —— 表现为「息屏后就推不了流」。
 *
 * iOS 的做法（ContentView.swift / BackgroundAudioManager）：
 *   - `AVAudioSession(.playback, mixWithOthers)` + 循环播放一段无声音频，
 *     让系统认为 App 在持续播放媒体，从而允许后台/锁屏继续运行；
 *   - `UIApplication.shared.isIdleTimerDisabled = true` 前台时禁止自动息屏。
 *
 * Android 等价方案（本类）：
 *   1. **无声音频保活**：用 `AudioTrack` 以极低采样率持续写「全零 PCM」（绝对静音），
 *      属性标记为 `USAGE_MEDIA`，系统据此认为 App 在播放媒体，降低息屏/后台被限频、被杀的概率；
 *      不依赖任何二进制音频资源文件，纯零采样，无任何可听声音。
 *   2. **PARTIAL_WAKE_LOCK**：保持 CPU 运行，防止息屏后编码/推流线程被冻结。
 *
 * ⚠️ 前台常亮（防止亮屏时自动息屏）由 Activity 的 `FLAG_KEEP_SCREEN_ON` 负责，见 StreamingScreen。
 * ⚠️ 若要在 App 完全退到后台时也长时间保活，Android 还需配合前台服务(Foreground Service)；
 *    本类已能覆盖「息屏但 App 仍在前台/推流」的主场景，前台服务可作为后续增强（见 PROGRESS.md）。
 */
class KeepAliveManager(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioTrack: AudioTrack? = null
    private var writerThread: Thread? = null

    /** 启动保活：前台服务(camera) + 无声音频 + WakeLock。重复调用安全（幂等）。 */
    @Synchronized
    fun start() {
        if (running) {
            Log.d(TAG, "⏭️ 保活已在运行，跳过")
            return
        }
        running = true
        // 🔥 2026-07-02 后台断流根治：无声音频/WakeLock 只能保 CPU，保不住相机——
        //    Android 9+ 后台应用会被系统直接断开 CameraDevice（约1分钟内），
        //    必须挂 camera 类型前台服务系统才允许后台继续采集。
        com.fz.yqlandroid.service.StreamingForegroundService.start(appContext)
        acquireWakeLock()
        startSilentAudio()
        Log.d(TAG, "🔊 后台保活已启动（前台服务 + 无声音频 + WakeLock）")
    }

    /** 停止保活：释放前台服务、音频与 WakeLock。重复调用安全（幂等）。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        com.fz.yqlandroid.service.StreamingForegroundService.stop(appContext)
        stopSilentAudio()
        releaseWakeLock()
        Log.d(TAG, "🔇 后台保活已停止")
    }

    // ==================== WakeLock ====================

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$WAKELOCK_TAG:keepAlive"
                ).apply { setReferenceCounted(false) }
            }
            // 无超时：推流期间常驻，stop() 时显式释放
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败: ${e.message}")
        }
    }

    // ==================== 无声音频 ====================

    private fun startSilentAudio() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            ).let { if (it <= 0) DEFAULT_BUF_BYTES else it }

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    minBuf, AudioTrack.MODE_STREAM
                )
            }

            audioTrack = track
            track.play()

            // 持续写「全零 PCM」= 绝对静音；线程随 running 结束而退出
            writerThread = thread(name = "KeepAliveSilentAudio", isDaemon = true) {
                val silence = ShortArray(minBuf / 2)  // 16-bit → /2；全 0 即静音
                while (running) {
                    try {
                        val written = track.write(silence, 0, silence.size)
                        if (written < 0) {
                            Log.w(TAG, "AudioTrack.write 返回 $written，停止写入")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "无声音频写入异常: ${e.message}")
                        break
                    }
                }
            }
            Log.d(TAG, "🔊 无声音频已启动 (sampleRate=$SAMPLE_RATE, buf=$minBuf)")
        } catch (e: Exception) {
            Log.e(TAG, "启动无声音频失败: ${e.message}")
        }
    }

    private fun stopSilentAudio() {
        try {
            writerThread?.join(500)
        } catch (_: InterruptedException) {
        }
        writerThread = null
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放无声音频失败: ${e.message}")
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "KeepAliveManager"
        private const val WAKELOCK_TAG = "yqlandroid"
        // 低采样率足够维持「正在播放媒体」状态，同时最省资源
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_BUF_BYTES = 4096
    }
}
