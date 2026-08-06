package com.fz.yqlandroid.manager.uvc

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ⭐ 外接 OTG 摄像头（UVC）采集器 —— 第四十八章，与自带摄像头链路完全分离。
 *
 * 实现 [org.webrtc.VideoCapturer] 接口：WebRTCManager 只需在创建 capturer 处二选一，
 * SurfaceTextureHelper/CountingObserver/videoSource/编码器/SRS/P2P 推流链路全部原样复用。
 *
 * 帧路径（CPU NV21，参考 saki/OpenCVwithUVC 同款）：
 *   libuvc 协商 MJPEG（USB2.0 带宽下 1080p30 必须 MJPEG，libuvc 内置 jpeg-turbo 解码）
 *   → IFrameCallback(NV21 直存 ByteBuffer) → 复制进池化 byte[]
 *   → [NV21Buffer] → capturerObserver.onFrameCaptured()（libwebrtc 编码前自动转 I420）。
 *
 * 生命周期：startCapture 启动 [UvcDeviceMonitor]，设备授权后自动开流；
 * 推流中拔线 → 停帧（左上角「采集0」可见），插回自动恢复；stopCapture/dispose 反向拆除。
 */
class UvcVideoCapturer(context: Context) : VideoCapturer, UvcDeviceMonitor.Listener {

    companion object {
        private const val TAG = "UvcVideoCapturer"
        /** ⭐ 2026-08-04：native "NV21" 回调实为 NV12（U/V 对调，OTG 皮肤发蓝实锤），
         *  帧回调里逐对交换色度字节纠正。若将来遇到不需要交换的设备/库版本，改 false 即可。 */
        private const val UV_SWAP = true
        /**
         * ⭐ 设备**没声明**每档 fps 时，向 libuvc 请求的帧率上限（探测用）。
         *
         * 死循环曾经是这样的：jiangdg 的 `getSupportedSizeList()` 在部分设备/版本上不填
         * `Size.fps`（实测日志里全是 `@0`，同一尺寸还会重复出现两条——那其实是两个不同帧率的
         * 帧描述符）→ PC 拿不到 fps 只能兜底 30 → 我们照着只请求 1~30 → libuvc 自然挑 30fps
         * 那个描述符 → 看起来就是"这摄像头只支持 30fps"。同一台设备插 Windows 能看到 120，
         * 因为 Windows 是直接读 UVC 描述符的。
         *
         * 所以设备没声明时就按这个上限往高了要，再用 [measuredFps] 实测拿到多少。
         * 取 60 而不是 120：推流侧编码器最高就 60（见 WebRTCManager.OTG_MAX_CAPTURE_FPS），
         * 采到 120 也推不出去，只是白发热。要放开改这一个数即可。
         */
        private const val PROBE_MAX_FPS = 60
        // 开流后多久没帧就切下一策略（ms）
        private const val NO_FRAME_TIMEOUT_MS = 2500L

        // 稳态帧率采样：连采几轮、每轮多长。取各轮**最大值**作为该档的能力上限。
        //   单窗口采样噪声很大（USB 等时传输 + MJPEG 解码本来就不均匀，实测同一档会在 24~30 之间跳），
        //   拿单次结果当"能力"再去算码率就会一路错下去。最大值才是"这档能跑到多少"。
        private const val FPS_SAMPLE_MS = 1500L
        private const val FPS_SAMPLE_ROUNDS = 3
    }

    // ⭐ 开流策略：native「开流成功但无帧」时依次降级重试（不同摄像头/OTG供电下兼容性差异大）
    private data class StreamStrategy(val format: Int, val bandwidth: Float, val name: String)

    /** PC 指定的采集格式：0=自动（MJPEG 优先，失败降 YUYV）/ 1=只用 MJPEG / 2=只用 YUYV */
    @Volatile var preferredFormat: Int = 0

    private fun strategies(): List<StreamStrategy> {
        val mjpeg = listOf(
            StreamStrategy(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH, "MJPEG@1.0"),
            StreamStrategy(UVCCamera.FRAME_FORMAT_MJPEG, 0.5f, "MJPEG@0.5")
        )
        val yuyv = listOf(
            StreamStrategy(UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.DEFAULT_BANDWIDTH, "YUYV@1.0"),
            StreamStrategy(UVCCamera.FRAME_FORMAT_YUYV, 0.3f, "YUYV@0.3")
        )
        return when (preferredFormat) {
            1 -> mjpeg + yuyv     // 指定 MJPEG：仍保留 YUYV 兜底，否则谈不拢就彻底没画面
            2 -> yuyv + mjpeg     // 指定 YUYV
            else -> mjpeg + yuyv  // 自动
        }
    }
    @Volatile private var noFrameRetry = 0
    private var noFrameWatchdog: Runnable? = null

    private val appContext = context.applicationContext

    private var observer: CapturerObserver? = null

    // 打开/关闭 UVC 相机的专用线程（native open/negotiate 可阻塞数百 ms，不能占主线程）
    private var uvcThread: HandlerThread? = null
    private var uvcHandler: Handler? = null

    private var uvcCamera: UVCCamera? = null

    // ⭐ 哑预览窗口：jiangdg libuvc 的 startPreview 必须有预览窗口(Surface)才起取流线程，
    //   否则 native 报 "window does not exist" 且 IFrameCallback 永不触发（capFps=0）。
    //   用 ImageReader 造一个自动排空的窗口占位（我们不读它的内容，帧走 IFrameCallback）。
    private var dummyReader: ImageReader? = null
    private var dummyReaderW = 0
    private var dummyReaderH = 0

    // WebRTC 请求的目标格式（UVC 侧就近协商）
    @Volatile private var requestedWidth = 1280
    @Volatile private var requestedHeight = 720
    @Volatile private var requestedFps = 30

    // UVC 实际协商出的帧格式（NV21Buffer 必须用这个尺寸）
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    // ⭐ 2026-08-03 描述符预读表：key="格式@WxH"（格式 1=MJPEG 2=YUYV），值=该档真实 fps 表（降序）。
    //   相机打开时从 USB 原始描述符解析（UvcDescriptorFps），协商/降帧/能力快照全用它——
    //   描述符规矩的摄像头切档零试错；解析不到的维持降帧收敛兜底。
    private val descriptorFps = HashMap<String, List<Int>>()

    private fun fmtKeyOf(frameFormat: Int): Int =
        if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) UvcDescriptorFps.FORMAT_MJPEG
        else UvcDescriptorFps.FORMAT_YUYV

    /** 该尺寸（任一格式）低于 belowFps 的最高描述符档；无则 null */
    private fun descFpsBelow(w: Int, h: Int, belowFps: Int): Int? =
        (descriptorFps["${UvcDescriptorFps.FORMAT_MJPEG}@${w}x${h}"].orEmpty() +
         descriptorFps["${UvcDescriptorFps.FORMAT_YUYV}@${w}x${h}"].orEmpty())
            .filter { it < belowFps }.maxOrNull()

    /** 相机打开时预读 USB 原始描述符里的每档 fps 表（提前知道，不再盲试） */
    private fun preloadDescriptorFps(device: UsbDevice) {
        descriptorFps.clear()
        try {
            val um = appContext.getSystemService(android.content.Context.USB_SERVICE)
                    as android.hardware.usb.UsbManager
            val conn = um.openDevice(device)
            if (conn == null) {
                Log.d("meidui", "🔌 [OTG] 📖 描述符预读失败: openDevice=null")
                com.fz.yqlandroid.manager.OtgLogReporter.diag("📖 描述符预读失败: openDevice=null → 维持降帧收敛兜底")
                return
            }
            val raw = try { conn.rawDescriptors } finally { try { conn.close() } catch (_: Exception) {} }
            if (raw == null || raw.isEmpty()) {
                Log.d("meidui", "🔌 [OTG] 📖 描述符预读失败: rawDescriptors 为空")
                com.fz.yqlandroid.manager.OtgLogReporter.diag("📖 描述符预读失败: rawDescriptors 为空 → 维持降帧收敛兜底")
                return
            }
            val entries = UvcDescriptorFps.parse(raw)
            for (e in entries) {
                val key = "${e.format}@${e.width}x${e.height}"
                // 同格式同尺寸出现多次（多个 VS 接口）取并集
                descriptorFps[key] = (descriptorFps[key].orEmpty() + e.fpsList).distinct().sortedDescending()
            }
            val table = entries.joinToString("  ") {
                "${if (it.format == UvcDescriptorFps.FORMAT_MJPEG) "MJPEG" else "YUYV"} ${it.width}x${it.height}=${it.fpsList}"
            }
            Log.d("meidui", "🔌 [OTG] 📖 描述符预读（${raw.size}字节 ${entries.size}条帧描述符）: $table")
            if (entries.isEmpty()) {
                com.fz.yqlandroid.manager.OtgLogReporter.diag("📖 描述符预读为空（设备没按UVC标准报帧间隔）→ 维持降帧收敛兜底")
            } else {
                com.fz.yqlandroid.manager.OtgLogReporter.diag("📖 描述符预读 ${entries.size}条: $table")
            }
        } catch (e: Exception) {
            Log.d("meidui", "🔌 [OTG] 📖 描述符预读失败: ${e.message}")
            com.fz.yqlandroid.manager.OtgLogReporter.diag("📖 描述符预读失败: ${e.message} → 维持降帧收敛兜底")
        }
    }

    @Volatile private var capturing = false        // startCapture ~ stopCapture 区间
    @Volatile private var streamRunning = false    // UVC 相机已开流

    // NV21 字节数组复用池（1080p30 每秒 ~45MB，若每帧新分配 GC 压力巨大）
    private val bufferPool = ArrayBlockingQueue<ByteArray>(4)
    @Volatile private var badFrameCount = 0L

    // ⭐ [诊断] IFrameCallback 原始命中数（在任何 early-return 之前自增）——用于「开流后无帧」看门狗
    //   区分：native 根本没送帧（计数不涨）vs 送了但被我方丢弃（计数涨但 capFps=0）。
    @Volatile private var frameCbCount = 0L

    /** 当前协商档位的**实测**帧率（设备不声明 fps 时唯一的真实来源），随能力快照上报 PC */
    @Volatile private var measuredFps = 0

    /** 首次开流是否已按设备自身列表选定档位（之后的切档才按 PC 请求就近选） */
    @Volatile private var initialSizePicked = false

    /** 协商出来的帧率（来自 UVC 帧间隔，权威值）；0=库没给，只能靠 [measuredFps] */
    @Volatile private var negotiatedFps = 0

    /** 当前实际采集格式名（MJPEG/YUYV），随能力快照上报，让"切了格式没生效"看得见 */
    @Volatile private var activeFormatName = ""

    private fun codecName(): String = com.fz.yqlandroid.manager.H265Support.effectiveCodec

    // MARK: - VideoCapturer 接口

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        // CPU NV21 路径不使用 SurfaceTextureHelper（保留参数以兼容 WebRTCManager 现有调用）
        this.observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        requestedWidth = width
        requestedHeight = height
        requestedFps = framerate
        capturing = true
        if (uvcThread == null) {
            uvcThread = HandlerThread("UvcCaptureThread").apply { start() }
            uvcHandler = Handler(uvcThread!!.looper)
        }
        Log.d("meidui", "🔌 [OTG] startCapture 请求=${width}x${height}@${framerate}fps")
        observer?.onCapturerStarted(true)
        UvcDeviceMonitor.addListener(this)
        UvcDeviceMonitor.start(appContext)
        // 已有授权设备（重启采集/切档场景）直接开流，无需等广播
        UvcDeviceMonitor.currentReady()?.let { (device, ctrl) ->
            uvcHandler?.post { openCameraLocked(device, ctrl) }
        }
    }

    override fun stopCapture() {
        capturing = false
        UvcDeviceMonitor.removeListener(this)
        runOnUvcThreadBlocking { closeCameraLocked() }
        observer?.onCapturerStopped()
        Log.d("meidui", "🔌 [OTG] stopCapture 完成")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        requestedWidth = width
        requestedHeight = height
        requestedFps = framerate
        fallbackTried = false   // 新的外部切档请求：重新给一次"全败回退最后可用配置"的机会
        triedDownSizes.clear()  // §56.12 新请求：降分辨率兜底的"已试尺寸"重新计数
        if (!capturing) {
            Log.d("meidui", "🔗 [OTG链路|重开流] ❌ capturing=false，只记参数不动流 ${width}x${height}@${framerate}")
            return
        }
        // ⭐ 全链路日志锚点③：真正重开 UVC 流（后续看「帧率精确请求」→「协商帧率」→「实测」三行）
        Log.d("meidui", "🔗 [OTG链路|重开流] ${width}x${height}@${framerate}fps 格式偏好=" +
                (when (preferredFormat) { 1 -> "MJPEG"; 2 -> "YUYV"; else -> "自动" }))
        uvcHandler?.post {
            val cam = uvcCamera ?: return@post
            try {
                noFrameRetry = 0   // 新的格式请求：从最优策略重新开始降级
                stopStreamLocked(cam)
                startStreamLocked(cam)
            } catch (e: Exception) {
                Log.e(TAG, "changeCaptureFormat失败: ${e.message}")
                Log.d("meidui", "🔌 [OTG] ❌ changeCaptureFormat失败: ${e.message}")
            }
        }
    }

    override fun dispose() {
        capturing = false
        UvcDeviceMonitor.removeListener(this)
        runOnUvcThreadBlocking { closeCameraLocked() }
        UvcDeviceMonitor.stop()
        uvcThread?.quitSafely()
        uvcThread = null
        uvcHandler = null
        observer = null
    }

    override fun isScreencast(): Boolean = false

    // MARK: - UvcDeviceMonitor.Listener

    override fun onUvcDeviceReady(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!capturing) return
        uvcHandler?.post { openCameraLocked(device, ctrlBlock) }
    }

    override fun onUvcDeviceGone(device: UsbDevice) {
        // 拔线：停帧关相机（monitor 仍注册，插回 onUvcDeviceReady 自动恢复）
        uvcHandler?.post { closeCameraLocked() }
    }

    // MARK: - UVC 相机开关（一律在 uvcThread 上执行）

    private fun openCameraLocked(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!capturing) return
        if (uvcCamera != null) {
            Log.d(TAG, "UVC相机已打开，忽略重复open")
            return
        }
        try {
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            uvcCamera = camera
            currentDeviceName = device.productName ?: device.deviceName
            preloadDescriptorFps(device)   // ⭐ 先读描述符 fps 表，随后的协商直接按真值请求
            startStreamLocked(camera)
            dumpCapabilitiesLocked(camera)   // ⭐ 能力枚举 → 画面层叠显 + OTG日志
            Log.d("meidui", "🔌 [OTG] ✅ UVC相机已开流: ${device.productName ?: device.deviceName} → ${frameWidth}x${frameHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "打开UVC相机失败: ${e.message}")
            Log.d("meidui", "🔌 [OTG] ❌ 打开UVC相机失败: ${e.message}")
            try { uvcCamera?.destroy() } catch (_: Exception) {}
            uvcCamera = null
        }
    }

    /**
     * 「格式@尺寸」黑名单：谈不拢/起流0帧的组合记下来，下次直接跳过，不再白等看门狗。
     *
     * 两轮实测教训（2026-07-26 / 07-28 日志实锤）：设备的"支持列表"是会骗人的——
     * 第一台设备 320x240 列在 MJPEG 里但 MJPEG 必失败（YUYV 才通）；同一台设备的 YUYV
     * 又在两个尺寸上全部 err=-51（MJPEG 才通）。所以黑名单必须按 **格式×尺寸** 记，
     * 不能只记 MJPEG 一边。用户强制指定格式时（preferredFormat != 0）不生效——明确要求就让它试。
     */
    private val formatBlacklist = mutableSetOf<String>()

    /**
     * ⭐ §56.12（2026-08-06，华为 JEF-AN00 240fps 卡死实锤）fps 级黑名单：
     * 描述符声明的 fps ≠ 真谈得拢——该机 MJPEG 1280x800 描述符只有 [120] 一档，
     * setPreviewSize(120) Java 层"假接受"、native prepare_preview 报 -51 → 0 帧；
     * 但重试候选每次都会把 120 重新排进去 → 永远撞死在同一个 fps 上。
     * 所以"协商成功但实测 0 帧"的 格式@尺寸@fps 必须记黑名单，下轮候选直接跳过。
     */
    private val fpsBlacklist = mutableSetOf<String>()
    private fun fpsBlKey(fmt: Int, w: Int, h: Int, fps: Int) = "${fmtName(fmt)}@${w}x$h@$fps"

    /** §56.12 全败降分辨率兜底时已试过的尺寸（防降档循环）；换设备/新外部切档时清空 */
    private val triedDownSizes = mutableSetOf<String>()

    private fun sizeKey(w: Int, h: Int) = "${w}x$h"
    private fun fmtName(fmt: Int) = if (fmt == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"
    private fun blKey(fmt: Int, w: Int, h: Int) = "${fmtName(fmt)}@${w}x$h"

    /** §56.12 描述符 key（"格式@WxH"）→ (w,h)；解析失败返回 null */
    private fun parseDescSize(key: String): Pair<Int, Int>? {
        val sz = key.substringAfter("@").split("x")
        val w = sz.getOrNull(0)?.toIntOrNull() ?: return null
        val h = sz.getOrNull(1)?.toIntOrNull() ?: return null
        return w to h
    }

    /**
     * 最后一次真正出过帧的完整配置（尺寸+格式+被接受的帧率）。
     * 所有策略全败时回退到它——2026-07-28 日志实锤：切一个谈不拢的组合失败后
     * 原逻辑直接躺平（"所有策略均0帧"），画面永久黑掉，而 30 秒前明明有一套跑得好好的配置。
     */
    private data class GoodConfig(val width: Int, val height: Int, val format: Int, val fps: Int)
    @Volatile private var lastGoodConfig: GoodConfig? = null
    @Volatile private var fallbackTried = false   // 每次外部切档只自动回退一次，防振荡

    /** 每个尺寸上一次协商成功的帧率（切回该尺寸时优先用它精确请求，别再拿 120 去撞） */
    private val knownGoodFps = mutableMapOf<String, Int>()

    /** 本轮开流实际用的格式与被设备接受的帧率（看门狗成功时据此登记 lastGood/黑名单） */
    @Volatile private var activeFormatInt = UVCCamera.FRAME_FORMAT_MJPEG
    @Volatile private var acceptedFps = 0

    /** 按当前重试档位选 格式/带宽 开流；开流后装「无帧看门狗」，2.5s 没帧自动切下一档重开 */
    private fun startStreamLocked(camera: UVCCamera) {
        frameCbCount = 0
        measuredFps = 0        // 换档重测，别把上一档的实测值带过来
        negotiatedFps = 0
        val list = strategies()
        // 跳过该尺寸已知谈不拢的格式（用户强制格式时不跳，明确要求就让它试）
        if (preferredFormat == 0) {
            var idx = minOf(noFrameRetry, list.size - 1)
            while (idx < list.size &&
                   formatBlacklist.contains(blKey(list[idx].format, requestedWidth, requestedHeight))) {
                Log.d("meidui", "🔌 [OTG] 跳过已知谈不拢的 ${list[idx].name}@${sizeKey(requestedWidth, requestedHeight)}")
                idx++
            }
            if (idx >= list.size) {
                // 全被拉黑：黑名单可能过期（换过口/供电变了），清掉该尺寸重试一轮
                Log.d("meidui", "🔌 [OTG] ${sizeKey(requestedWidth, requestedHeight)} 所有格式都在黑名单 → 清空重试")
                formatBlacklist.removeAll { it.endsWith("@${sizeKey(requestedWidth, requestedHeight)}") }
                idx = minOf(noFrameRetry, list.size - 1)
            }
            noFrameRetry = idx
        }
        val st = list[minOf(noFrameRetry, list.size - 1)]
        activeFormatInt = st.format
        try {
            val (w, h) = negotiateAndStart(camera, st.format, st.bandwidth)
            frameWidth = w
            frameHeight = h
            badFrameCount = 0
            bufferPool.clear()
            streamRunning = true
            Log.d("meidui", "🔌 [OTG] 开流策略=${st.name} 尺寸=${w}x${h}，${NO_FRAME_TIMEOUT_MS}ms后检查有无帧")
            // ⭐ 2026-08-03 自诊断通道（绕过 logcat，华为等 ROM 丢 Log.d 也能上后台）
            com.fz.yqlandroid.manager.OtgLogReporter.diag(
                "开流成功 策略=${st.name} 协商=${w}x${h}@${acceptedFps}fps" +
                "（请求=${requestedWidth}x${requestedHeight}@${requestedFps}fps）")
        } catch (e: Exception) {
            streamRunning = false
            Log.d("meidui", "🔌 [OTG] ❌ 开流失败(${st.name}): ${e.message}")
            com.fz.yqlandroid.manager.OtgLogReporter.diag(
                "❌ 开流失败 策略=${st.name} 请求=${requestedWidth}x${requestedHeight}@${requestedFps}fps" +
                " err=${e.message}（-51=INVALID_MODE 该组合摄像头不认）")
            formatBlacklist.add(blKey(st.format, requestedWidth, requestedHeight))
        }
        scheduleNoFrameWatchdog(camera)   // 无论成功/失败都装看门狗：失败或0帧都会切下一档
    }

    /** 开流后 2.5s 检查 IFrameCallback 是否真的在吐帧；0 帧则切下一策略重开（有限次） */
    private fun scheduleNoFrameWatchdog(camera: UVCCamera) {
        val handler = uvcHandler ?: return
        noFrameWatchdog?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!capturing || uvcCamera !== camera) return@Runnable
            if (frameCbCount > 0) {
                noFrameRetry = 0   // 成功出帧：重置档位，后续切档/重开从最优策略开始
                fallbackTried = false
                // 登记"最后可用配置"与该尺寸的可协商帧率：全败兜底、切回时精确请求都靠它
                lastGoodConfig = GoodConfig(frameWidth, frameHeight, activeFormatInt, acceptedFps)
                if (acceptedFps > 0) knownGoodFps[sizeKey(frameWidth, frameHeight)] = acceptedFps
                Log.d("meidui", "🔌 [OTG] ✅ IFrameCallback正常：${NO_FRAME_TIMEOUT_MS}ms内${frameCbCount}帧 " +
                        "${frameWidth}x${frameHeight}（登记可用配置 ${fmtName(activeFormatInt)}@${acceptedFps}fps）")
                dumpCapabilitiesLocked(camera)   // 出帧确认后刷新能力快照（协商尺寸/当前值已最终定）
                scheduleFpsSample(camera)        // 再等画面稳下来单独测一次帧率
                return@Runnable
            }
            // ⭐ §56.12：这个 格式@尺寸@协商fps 实测 0 帧 → 拉黑，下轮协商候选直接跳过。
            //   没有它，降帧重试的候选里描述符档（如 1280x800 唯一的 120）每次都会复活，
            //   Java 假接受 → native -51 → 0 帧无限循环（240fps 卡死的根子）。
            if (acceptedFps > 0) {
                fpsBlacklist.add(fpsBlKey(activeFormatInt, frameWidth, frameHeight, acceptedFps))
                com.fz.yqlandroid.manager.OtgLogReporter.diag(
                    "🚫 拉黑实测0帧的档位 ${fmtName(activeFormatInt)}@${frameWidth}x${frameHeight}@${acceptedFps}fps（假接受后native -51）")
            }
            // ⭐⭐ 2026-08-03 华为 JEF-AN00 实锤修复：Java 层 setPreviewSize 会"假接受"高 fps
            //  （请求120被"接受"为60/120），真正的协商在 native prepare_preview 才报 -51
            //   INVALID_MODE → 0帧。此前重试梯度只换 格式/带宽、fps 永远钉在高值 → 三种策略
            //   全死在同一个 -51 上（错不在格式）。所以 0 帧后先**降帧重试**（同策略，
            //   优先该尺寸已知可协商值，否则 120→60→30），fps 降到底了再走换格式的老梯度。
            if (requestedFps > 30) {
                val known = knownGoodFps[sizeKey(requestedWidth, requestedHeight)]
                // ⭐ 降帧目标优先级：该档已协商成功值 > 描述符声明的低一档真值 > 60/30 盲降
                val next = when {
                    known != null && known in 1 until requestedFps -> known
                    else -> descFpsBelow(requestedWidth, requestedHeight, requestedFps)
                            ?: if (requestedFps > 60) 60 else 30
                }
                Log.d("meidui", "🔌 [OTG] ⚠️ 0帧且请求${requestedFps}fps 偏高 → 降帧到${next}fps 同策略重试（native假接受高fps后-51）")
                com.fz.yqlandroid.manager.OtgLogReporter.diag(
                    "⚠️ 0帧 请求=${requestedWidth}x${requestedHeight}@${requestedFps}fps → 降帧到${next}fps 重试（不换格式）")
                requestedFps = next
                // 错不在格式：不拉黑、不推进策略档
                try {
                    stopStreamLocked(camera)
                    startStreamLocked(camera)
                } catch (e: Exception) {
                    Log.d("meidui", "🔌 [OTG] 降帧重开失败: ${e.message}")
                }
                return@Runnable
            }
            // 起流了却 0 帧：这个 格式@尺寸 拉黑
            formatBlacklist.add(blKey(activeFormatInt, requestedWidth, requestedHeight))
            noFrameRetry++
            if (noFrameRetry >= strategies().size) {
                // ⭐ 全败兜底（2026-07-28 卡死修复）：不许躺平黑屏——回退到最后一次出过帧的配置。
                //   每次外部切档只回退一次（fallbackTried），回退自身再失败就真没辙了，如实报错。
                val good = lastGoodConfig
                if (good != null && !fallbackTried) {
                    fallbackTried = true
                    Log.d("meidui", "🔌 [OTG] ❌ 请求的配置所有策略均0帧 → 回退最后可用配置 " +
                            "${good.width}x${good.height} ${fmtName(good.format)}@${good.fps}fps（PC 面板显示会随能力快照纠正）")
                    com.fz.yqlandroid.manager.OtgLogReporter.diag(
                        "❌ 请求=${requestedWidth}x${requestedHeight} 全策略0帧 → 回退最后可用配置 " +
                        "${good.width}x${good.height} ${fmtName(good.format)}@${good.fps}fps")
                    requestedWidth = good.width
                    requestedHeight = good.height
                    requestedFps = good.fps
                    preferredFormat = if (good.format == UVCCamera.FRAME_FORMAT_MJPEG) 1 else 2
                    noFrameRetry = 0
                    try {
                        stopStreamLocked(camera)
                        startStreamLocked(camera)
                    } catch (e: Exception) {
                        Log.d("meidui", "🔌 [OTG] 回退重开失败: ${e.message}")
                    }
                    return@Runnable
                }
                // ⭐ §56.12 降分辨率兜底：该尺寸两种格式全谈不拢（华为 JEF-AN00 + 1280x800 实锤：
                //   MJPEG 唯一档 120 假接受后 -51、YUYV 唯一档 10 也 0 帧）——问题是 USB 带宽，
                //   不是格式。从描述符里挑「面积次一档、没试过的」尺寸重试，别直接躺平黑屏。
                val curArea = requestedWidth.toLong() * requestedHeight
                triedDownSizes.add(sizeKey(requestedWidth, requestedHeight))
                val smaller = descriptorFps.keys
                    .mapNotNull { parseDescSize(it) }
                    .distinct()
                    .filter { (w, h) -> w.toLong() * h < curArea && !triedDownSizes.contains(sizeKey(w, h)) }
                    .maxByOrNull { (w, h) -> w.toLong() * h }
                if (smaller != null) {
                    Log.d("meidui", "🔌 [OTG] ⬇️ ${requestedWidth}x${requestedHeight} 全策略0帧 → 降分辨率重试 ${smaller.first}x${smaller.second}")
                    com.fz.yqlandroid.manager.OtgLogReporter.diag(
                        "⬇️ ${requestedWidth}x${requestedHeight} 全策略0帧（带宽谈不拢）→ 降分辨率重试 ${smaller.first}x${smaller.second}")
                    requestedWidth = smaller.first
                    requestedHeight = smaller.second
                    noFrameRetry = 0
                    try {
                        stopStreamLocked(camera)
                        startStreamLocked(camera)
                    } catch (e: Exception) {
                        Log.d("meidui", "🔌 [OTG] 降分辨率重开失败: ${e.message}")
                    }
                    return@Runnable
                }
                Log.d("meidui", "🔌 [OTG] ❌ 所有 格式/带宽 策略均0帧（无可回退配置）：该UVC设备与本机isoc不兼容，或OTG口供电不足（换带独立供电的OTG口再试）")
                com.fz.yqlandroid.manager.OtgLogReporter.diag(
                    "❌ 请求=${requestedWidth}x${requestedHeight} 全策略0帧且无可回退配置（isoc不兼容/OTG供电不足）")
                return@Runnable
            }
            Log.d("meidui", "🔌 [OTG] ⚠️ 开流后${NO_FRAME_TIMEOUT_MS}ms内0帧(native未回调IFrameCallback) → 切换到策略[${noFrameRetry}]重开")
            com.fz.yqlandroid.manager.OtgLogReporter.diag(
                "⚠️ 开流后${NO_FRAME_TIMEOUT_MS}ms内0帧 请求=${requestedWidth}x${requestedHeight}@${requestedFps}fps → 切策略[${noFrameRetry}]重开")
            try {
                stopStreamLocked(camera)
                startStreamLocked(camera)
            } catch (e: Exception) {
                Log.d("meidui", "🔌 [OTG] 切策略重开失败: ${e.message}")
            }
        }
        noFrameWatchdog = r
        handler.postDelayed(r, NO_FRAME_TIMEOUT_MS)
    }

    /**
     * ⭐ 稳态帧率采样。
     *
     * 为什么不复用无帧看门狗那 2.5s 的计数：那个窗口是从**开流那一刻**起算的，
     * 相机预热的头几百毫秒一帧不出，53 帧 ÷ 2.5s 会算成 21fps，而 WebRTC 侧 `capFps` 实测 29~31。
     * 这个偏低的值还会被回填进能力快照、再被码率公式吃进去（2000×√(20/30)=1633kbps），一路错下去。
     * 所以等画面稳了单独采一段：只数这一段窗口内的增量，除以窗口长度。
     */
    private fun scheduleFpsSample(camera: UVCCamera, round: Int = 1) {
        val handler = uvcHandler ?: return
        val startCount = frameCbCount
        handler.postDelayed({
            if (!capturing || !streamRunning || uvcCamera !== camera) return@postDelayed
            val delta = frameCbCount - startCount
            val fps = (delta * 1000 / FPS_SAMPLE_MS).toInt()
            if (fps > measuredFps) {
                measuredFps = fps
                Log.d("meidui", "🔌 [OTG] 📏 实测第${round}轮：${FPS_SAMPLE_MS}ms内${delta}帧 → ${fps}fps" +
                        " @${frameWidth}x${frameHeight}（取各轮最大）")
            } else {
                Log.d("meidui", "🔌 [OTG] 📏 实测第${round}轮：${fps}fps（未超过已记录的 ${measuredFps}fps）")
            }
            if (round < FPS_SAMPLE_ROUNDS) {
                scheduleFpsSample(camera, round + 1)
            } else {
                Log.d("meidui", "🔌 [OTG] 📏 ${frameWidth}x${frameHeight} 采集能力定为 ${measuredFps}fps（${FPS_SAMPLE_ROUNDS}轮最大）")
                dumpCapabilitiesLocked(camera)   // 定了才推能力（码率上限跟着它算）
            }
        }, FPS_SAMPLE_MS)
    }

    /** 造/复用一个自动排空的 ImageReader 哑预览窗口（尺寸变了才重建）。必须持续排空，
     *  否则窗口缓冲队列满 → native 取流线程在绘制处阻塞 → 连带停发 IFrameCallback。 */
    private fun ensureDummySurface(w: Int, h: Int): Surface {
        val cur = dummyReader
        if (cur != null && dummyReaderW == w && dummyReaderH == h) return cur.surface
        releaseDummySurface()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, uvcHandler)
        dummyReader = reader
        dummyReaderW = w
        dummyReaderH = h
        return reader.surface
    }

    private fun releaseDummySurface() {
        try { dummyReader?.close() } catch (_: Exception) {}
        dummyReader = null
        dummyReaderW = 0
        dummyReaderH = 0
    }

    private fun negotiateAndStart(camera: UVCCamera, frameFormat: Int, bandwidth: Float): Pair<Int, Int> {
        val sizes = try {
            camera.getSupportedSizeList(frameFormat)?.filterIsInstance<com.jiangdg.utils.Size>()
                ?.filter { it.width > 0 && it.height > 0 }
        } catch (_: Exception) { null }

        // ⭐ 首次开流：**不理会 WebRTC 传进来的尺寸**。
        //   那个尺寸来自自带摄像头的 ladder（如 STANDARD=1024x768），UVC 设备可能压根没有这一档，
        //   拿它去"就近协商"既莫名其妙（叠显上写着"请求1024x768"）、在大分辨率设备上还会选偏。
        //   OTG 的初始档位应当从**设备自己的列表**里挑：取编码器吃得下的最大一档。
        val target = if (!initialSizePicked) {
            val best = sizes
                ?.filter { EncoderSizeLimits.isEncodable(codecName(), it.width, it.height) }
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes?.maxByOrNull { it.width.toLong() * it.height }
            if (best != null) {
                Log.d("meidui", "🔌 [OTG] 首次开流：忽略上层传来的 ${requestedWidth}x${requestedHeight}" +
                        "（那是自带摄像头档位），改用设备自身最大可编码档 ${best.width}x${best.height}")
                requestedWidth = best.width
                requestedHeight = best.height
                initialSizePicked = true
            }
            best
        } else {
            // 后续切档（PC 发 otg_resolution）：按请求值就近选（面积差 + 宽高比差）
            sizes?.minByOrNull { s ->
                val areaDiff = Math.abs(s.width.toLong() * s.height - requestedWidth.toLong() * requestedHeight)
                val ratioDiff = Math.abs(s.width.toFloat() / s.height - requestedWidth.toFloat() / requestedHeight)
                areaDiff + (ratioDiff * 1_000_000).toLong()
            }
        }

        var w = target?.width ?: requestedWidth
        var h = target?.height ?: requestedHeight

        // ⭐ §56.12 高帧率请求重定向（240fps 卡死正解）：240 这类高帧档只存在于小尺寸
        //  （该华为实测：MJPEG 640x360/640x400/320x240=[240]，1280x800 最高 120）。
        //   请求的 fps 超过目标尺寸描述符上限时，此前会死磕当前尺寸（降帧→-51→黑屏）；
        //   现在自动改选「真正声明了 ≥请求fps」的最大可编码尺寸——高帧率语义本来就是分辨率让位。
        if (requestedFps > 60 && descriptorFps.isNotEmpty()) {
            val curMax = descriptorFps["${fmtKeyOf(frameFormat)}@${w}x${h}"]?.maxOrNull() ?: 0
            if (curMax in 1 until requestedFps) {
                val alt = descriptorFps.entries
                    .filter { it.key.startsWith("${fmtKeyOf(frameFormat)}@") && it.value.any { f -> f >= requestedFps } }
                    .mapNotNull { parseDescSize(it.key) }
                    .filter { (aw, ah) -> EncoderSizeLimits.isEncodable(codecName(), aw, ah) }
                    .maxByOrNull { (aw, ah) -> aw.toLong() * ah }
                if (alt != null) {
                    Log.d("meidui", "🔌 [OTG] 🎯 请求${requestedFps}fps 但 ${w}x${h} 描述符上限${curMax}fps" +
                            " → 自动改选支持该帧率的 ${alt.first}x${alt.second}")
                    com.fz.yqlandroid.manager.OtgLogReporter.diag(
                        "🎯 请求${requestedFps}fps 当前档${w}x${h}上限${curMax}fps → 自动切 ${alt.first}x${alt.second}@${requestedFps}fps")
                    w = alt.first
                    h = alt.second
                }
            }
        }

        // ⭐ 帧率必须**精确请求**（min=max），不能给宽区间：
        //   写 min=1 等于"什么都行"，libuvc 永远挑设备默认档（30），120 的描述符轮不到；
        //   而"放宽区间 [1,120] 兜底"实测也是废的（2026-07-28 日志：640x480 放宽后照样 err=-51，
        //   四条策略全败 → 永久黑屏）。所以失败后的正确做法是**按帧率梯度逐个精确重试**：
        //   请求值 → 该尺寸上次协商成功值 → 60/30/25/20/15/10。
        val declaredFps = try { target?.fps?.maxOrNull()?.toInt() ?: 0 } catch (_: Exception) { 0 }
        val exactFps = when {
            requestedFps > 0 -> requestedFps                       // PC 指定了就照办（含 120）
            declaredFps > 0  -> declaredFps
            else             -> PROBE_MAX_FPS
        }
        // ⭐ 2026-08-03 描述符预读优先：该 格式@尺寸 的真实 fps 表已在相机打开时解析好
        //  （preloadDescriptorFps），首选"描述符声明、且不超请求值"的最高档——直接命中，
        //   不再靠 Java 假接受 + 看门狗盲试。描述符缺失/乱写时照旧走老梯度兜底。
        val descList = descriptorFps["${fmtKeyOf(frameFormat)}@${w}x${h}"].orEmpty()
        val descPick = descList.filter { it <= exactFps }
        if (descList.isNotEmpty()) {
            Log.d("meidui", "🔌 [OTG] 📖 描述符档@${w}x${h}" +
                    "(${if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"})=$descList" +
                    " 请求${exactFps}fps → 首选${descPick.firstOrNull() ?: descList.minOrNull()}fps")
        }
        val candidatesRaw = (descPick +
                listOf(exactFps) +
                listOfNotNull(knownGoodFps[sizeKey(w, h)]) +
                // 请求低于描述符最低档时用设备最低真实档（采集偏高无害，推送侧另有节流）
                listOfNotNull(descList.minOrNull()) +
                listOf(60, 30, 25, 20, 15, 10).filter { it < exactFps })
            .filter { it >= 1 }
            .distinct()
        // ⭐ §56.12 滤掉已实测 0 帧的 fps（假接受→native -51 那种）。全被拉黑=该格式该尺寸
        //   没有可用档 → 候选为空，下方 negotiatedOk=false 抛异常 → 上层照旧走
        //   格式黑名单/降分辨率梯度，不再回头撞同一个 -51
        val candidates = candidatesRaw
            .filterNot { fpsBlacklist.contains(fpsBlKey(frameFormat, w, h, it)) }
        if (candidates.size != candidatesRaw.size) {
            Log.d("meidui", "🔌 [OTG] 候选fps已滤掉实测0帧档: $candidatesRaw → $candidates")
        }

        camera.setFrameCallback(null, 0)
        try { camera.stopPreview() } catch (_: Exception) {}
        var negotiatedOk = false
        var lastErr: Exception? = null
        for (fps in candidates) {
            try {
                camera.setPreviewSize(w, h, fps, fps, frameFormat, bandwidth)
                acceptedFps = fps
                negotiatedOk = true
                Log.d("meidui", "🔌 [OTG] 帧率精确请求 ${fps}fps @${w}x${h} ✅被接受" +
                        (if (fps != exactFps) "（请求的 ${exactFps}fps 该档没有，梯度降级）" else "") +
                        (if (declaredFps > 0) "（设备声明${declaredFps}fps）" else "") +
                        (if (descList.contains(fps)) "（命中描述符声明档）" else ""))
                break
            } catch (e: Exception) {
                lastErr = e
                Log.d("meidui", "🔌 [OTG] ${fps}fps @${w}x${h} 谈不拢(${e.message}) → 试下一档")
            }
        }
        if (!negotiatedOk) {
            throw lastErr ?: IllegalStateException("no fps candidate accepted @${w}x${h}")
        }
        // ⭐ 关键修复：挂哑预览窗口，否则 native startPreview 因无窗口不起流线程 → 无帧
        camera.setPreviewDisplay(ensureDummySurface(w, h))
        camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
        camera.startPreview()

        // 协商完成，读设备真正给的帧率（权威值，优先于后面的实测估算）
        negotiatedFps = readNegotiatedFps(camera)
        if (negotiatedFps > 0) {
            Log.d("meidui", "🔌 [OTG] ✅ 协商帧率=${negotiatedFps}fps（取自 getPreviewSize 的 UVC 帧间隔，非估算）")
        } else {
            Log.d("meidui", "🔌 [OTG] ⚠️ 库未给出协商帧率（getCurrentFrameRate/fps/intervals 都为空）→ 只能靠实测")
        }
        val fmtName = if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"
        activeFormatName = fmtName
        Log.d(TAG, "UVC开流: 请求${requestedWidth}x${requestedHeight}@${requestedFps} → 协商${w}x${h} format=$fmtName bw=$bandwidth")
        // ⭐ 第五十章：请求值与协商值不一致要看得见 —— PC 面板选了 160x120，
        //   但该格式的支持列表里没有这一档时，这里会就近落到别的尺寸（PC 显示的档位就"没生效"）。
        if (w != requestedWidth || h != requestedHeight) {
            Log.d("meidui", "🔌 [OTG] ⚠️ 协商偏移: 请求${requestedWidth}x${requestedHeight} → 实际${w}x${h}" +
                    "（$fmtName 支持列表里没有请求的那一档，已就近选取）")
        }
        return w to h
    }

    private fun stopStreamLocked(camera: UVCCamera) {
        streamRunning = false
        noFrameWatchdog?.let { uvcHandler?.removeCallbacks(it) }
        noFrameWatchdog = null
        try { camera.setFrameCallback(null, 0) } catch (_: Exception) {}
        try { camera.stopPreview() } catch (_: Exception) {}
    }

    private fun closeCameraLocked() {
        val camera = uvcCamera ?: return
        stopStreamLocked(camera)
        try { camera.destroy() } catch (e: Exception) {
            Log.w(TAG, "destroy UVC camera failed: ${e.message}")
        }
        uvcCamera = null
        releaseDummySurface()
        defaultControlValues.clear()
        formatBlacklist.clear()
        knownGoodFps.clear()
        fpsBlacklist.clear()      // §56.12 换设备/重插，实测黑名单作废
        triedDownSizes.clear()
        lastGoodConfig = null
        fallbackTried = false
        initialSizePicked = false
        UvcCapabilityStore.clear()
        Log.d("meidui", "🔌 [OTG] UVC相机已关闭")
    }

    // MARK: - ⭐ 能力枚举（第四十八章：OTG 可调参数/上下限 → 画面层叠显 + OTG 日志，供 PC 调节面板改造对照）

    @Volatile private var currentDeviceName: String? = null

    /**
     * 枚举该 UVC 设备的软/硬件可调能力并发布：
     * - 软件侧：MJPEG/YUYV 各自支持的分辨率列表（PC「档位」= 就近协商到这些尺寸）
     * - 硬件侧：jiangdg libuvc 暴露的控制项，统一 **百分比 0~100**（库内部映射到设备各自的绝对 min~max），
     *   是否支持由 UVC 能力位掩码（checkSupportFlag）决定，逐台设备不同。
     * - 明确不适用项：前后摄 direction、快门 cjfps（库无曝光时间接口）。
     * 必须在 uvcThread 上调（updateCameraParams/getXxx 走 native）。
     */
    private fun dumpCapabilitiesLocked(camera: UVCCamera) {
        val lines = mutableListOf<String>()
        val controls = mutableListOf<UvcCapabilityStore.Control>()
        val sizes = mutableListOf<UvcCapabilityStore.SizeOption>()
        try {
            camera.updateCameraParams()   // 读能力位掩码 + 各控制项的绝对 min/max（百分比映射的基础）
            lines += "OTG设备: ${currentDeviceName ?: "?"}"
            lines += "协商: ${frameWidth}x${frameHeight}@${activeFormatName}" +
                    (if (negotiatedFps > 0) " 协商${negotiatedFps}fps" else " 协商fps未知") +
                    (if (measuredFps > 0) " 实测${measuredFps}fps" else "") +
                    " (请求${requestedWidth}x${requestedHeight}@${requestedFps}fps)"

            fun sizesOf(fmt: Int, name: String) {
                val s = try {
                    camera.getSupportedSizeList(fmt)?.filterIsInstance<com.jiangdg.utils.Size>()
                        ?.filter { it.width > 0 && it.height > 0 }
                } catch (_: Exception) { null }
                // 每档分辨率带 fps 上限（Size.fps 由 UVC 帧间隔描述符算出；null=设备没报，记 0）
                s?.forEach { sz ->
                    // fps 取值：设备声明 > ⭐描述符预读（2026-08-03，全档提前知道）> 协商值 > 实测值
                    val declared = try { sz.fps?.maxOrNull()?.toInt() ?: 0 } catch (_: Exception) { 0 }
                    val descMax = descriptorFps["${fmtKeyOf(fmt)}@${sz.width}x${sz.height}"]?.maxOrNull() ?: 0
                    val isCurrent = sz.width == frameWidth && sz.height == frameHeight
                    val maxFps = when {
                        declared > 0 -> declared
                        descMax > 0 -> descMax
                        isCurrent && negotiatedFps > 0 -> negotiatedFps
                        isCurrent -> measuredFps
                        else -> 0
                    }
                    // 同尺寸两种格式都支持时只保留一条（取 fps 上限更高的），PC 档位列表按尺寸去重
                    val exist = sizes.indexOfFirst { it.width == sz.width && it.height == sz.height }
                    if (exist < 0) {
                        sizes += UvcCapabilityStore.SizeOption(sz.width, sz.height, maxFps)
                    } else if (maxFps > sizes[exist].maxFps) {
                        sizes[exist] = sizes[exist].copy(maxFps = maxFps)
                    }
                }
                lines += if (!s.isNullOrEmpty())
                    "$name(${s.size}): " + s.joinToString(" ") { sz ->
                        val maxFps = sz.fps?.maxOrNull()?.toInt() ?: 0
                        if (maxFps > 0) "${sz.width}x${sz.height}@$maxFps" else "${sz.width}x${sz.height}"
                    }
                else "$name: 无"
            }
            sizesOf(UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG分辨率")
            sizesOf(UVCCamera.FRAME_FORMAT_YUYV, "YUYV分辨率")

            fun sup(flag: Int): Boolean = try { camera.checkSupportFlag(flag.toLong()) } catch (_: Exception) { false }
            fun pct(key: String, name: String, flag: Int, get: () -> Int) {
                val ok = sup(flag)
                val cur = if (ok) (try { get() } catch (_: Exception) { -1 }) else -1
                controls += UvcCapabilityStore.Control(key, name, "pct", ok, cur)
                lines += if (ok) "$name($key): ✅ 0~100% 当前=${cur}%" else "$name($key): ✗不支持"
            }
            fun bool(key: String, name: String, flag: Int, get: () -> Boolean?) {
                val ok = sup(flag)
                val cur = if (ok) (try { get() } catch (_: Exception) { null }) else null
                controls += UvcCapabilityStore.Control(key, name, "bool", ok, if (cur == true) 1 else 0, 0, 1)
                lines += if (ok) "$name($key): ✅ 当前=${if (cur == true) "开" else "关"}" else "$name($key): ✗不支持"
            }
            pct("zoom", "变焦", UVCCamera.CTRL_ZOOM_ABS) { camera.zoom }
            bool("autoFocus", "自动对焦AF", UVCCamera.CTRL_FOCUS_AUTO) { camera.autoFocus }
            pct("focus", "手动对焦", UVCCamera.CTRL_FOCUS_ABS) { camera.focus }
            bool("autoWhiteBalance", "自动白平衡AWB", UVCCamera.PU_WB_TEMP_AUTO) { camera.autoWhiteBlance }
            pct("whiteBalance", "白平衡色温", UVCCamera.PU_WB_TEMP) { camera.whiteBlance }
            pct("brightness", "亮度", UVCCamera.PU_BRIGHTNESS) { camera.brightness }
            pct("contrast", "对比度", UVCCamera.PU_CONTRAST) { camera.contrast }
            pct("saturation", "饱和度", UVCCamera.PU_SATURATION) { camera.saturation }
            pct("hue", "色调", UVCCamera.PU_HUE) { camera.hue }
            pct("sharpness", "锐度", UVCCamera.PU_SHARPNESS) { camera.sharpness }
            pct("gamma", "伽马", UVCCamera.PU_GAMMA) { camera.gamma }
            pct("gain", "增益", UVCCamera.PU_GAIN) { camera.gain }
            val plOk = sup(UVCCamera.PU_POWER_LF)
            controls += UvcCapabilityStore.Control(
                "powerline", "抗频闪", "enum", plOk,
                cur = if (plOk) (readPowerline(camera) ?: 0) else 0,
                min = 0, max = 2, options = listOf("关", "50Hz", "60Hz")
            )
            lines += if (plOk) "抗频闪(powerline): ✅ 0=关/1=50Hz/2=60Hz" else "抗频闪(powerline): ✗不支持"
            // 与自带摄像头面板的差异项（PC 面板据此不渲染对应控件）
            lines += if (sup(UVCCamera.CTRL_AE_ABS)) "快门cjfps: ✗设备支持曝光时间但库无接口" else "快门cjfps: ✗不支持"
            lines += "前后摄direction: ✗OTG不适用 | 推送fps/码率/档位(按分辨率): ✅走 otg_ 独立通道"
        } catch (e: Exception) {
            lines += "能力枚举失败: ${e.message}"
        }
        // 每档分辨率的码率上限：以"枚举出的最大分辨率 = 现有最高档码率"为锚，按像素率等比算
        // 同时标出硬件编码器吃不下的档位（低于最小分辨率的选了必黑，PC 面板不给选）
        val codec = com.fz.yqlandroid.manager.H265Support.effectiveCodec
        val sizesWithKbps = OtgBitratePlan.annotate(sizes).map {
            it.copy(
                encodable = EncoderSizeLimits.isEncodable(codec, it.width, it.height),
                // 该尺寸下编码器能编的最高帧率 = 推流 fps 的真实上限（随快照给 PC，滑条按它开）
                encMaxFps = EncoderSizeLimits.maxFrameRate(codec, it.width, it.height)
            )
        }
        lines += EncoderSizeLimits.describe(codec)
        sizesWithKbps.forEach {
            lines += "  码率上限 ${it.width}x${it.height}@${it.maxFps} → ${it.maxKbps}kbps" +
                    if (it.encodable) "" else "  ⛔编码器不支持此尺寸(选了必黑，已屏蔽)"
        }
        val caps = UvcCapabilityStore.Caps(
            deviceName = currentDeviceName ?: "",
            width = frameWidth,
            height = frameHeight,
            format = activeFormatName,
            sizes = sizesWithKbps,
            controls = controls,
            version = System.currentTimeMillis()
        )
        // 首次枚举（相机刚打开）时把各项当前值记为出厂缺省，供「还原」回落
        if (defaultControlValues.isEmpty()) {
            controls.filter { it.supported && it.cur >= 0 }.forEach { defaultControlValues[it.key] = it.cur }
            if (defaultControlValues.isNotEmpty()) {
                Log.d("meidui", "🔌 [OTG] 已记录出厂缺省: " +
                        defaultControlValues.entries.joinToString(" ") { "${it.key}=${it.value}" })
            }
        }
        UvcCapabilityStore.set(lines, caps)
        lines.forEach { Log.d("meidui", "🔌 [OTG能力] $it") }
        // 能力一变就主动推给 PC（PC 据此重建面板），不等 PC 来问
        try { onCapsUpdated?.invoke() } catch (_: Exception) {}
    }

    /** 能力快照刷新回调 —— WebRTCManager 挂上去，用于主动上报 PC */
    @Volatile var onCapsUpdated: (() -> Unit)? = null

    /**
     * 相机刚打开时各控制项的原始值 = 该设备的**出厂缺省**，「还原」就回落到这里。
     * 比 PC 侧猜一个"中间值"发下来靠谱：每台 UVC 设备的缺省点位都不一样（有的亮度缺省 50%、
     * 有的 32%），猜错就等于把画面调坏。相机关闭时清空。
     */
    private val defaultControlValues = mutableMapOf<String, Int>()

    /** ⭐ 第五十章：「还原」—— 把所有支持的硬件项回落到开机时记下的出厂缺省，然后重推能力快照 */
    fun resetControlsToDefault() {
        val caps = UvcCapabilityStore.caps.value
        if (uvcCamera == null || caps == null) {
            Log.d("meidui", "🔌 [OTG还原] 忽略：相机未打开或无能力快照")
            return
        }
        var n = 0
        caps.controls.filter { it.supported }.forEach { c ->
            val def = defaultControlValues[c.key] ?: return@forEach
            if (applyControl(c.key, def)) n++
        }
        Log.d("meidui", "🔌 [OTG还原] 已回落 $n 项到出厂缺省")
        // 等下发落地后重新枚举一次，PC 面板的滑条随 controls[].cur 归位
        uvcHandler?.postDelayed({ uvcCamera?.let { dumpCapabilitiesLocked(it) } }, 300)
    }

    /**
     * ⭐ 第五十章：应用 PC 经 `otg_ctrl` 下发的 UVC 硬件控制项。
     *
     * 值域与能力快照一致：pct 类 0~100 百分比（库内部映射到设备绝对 min~max）、
     * bool 类 0/1、powerline 0/1/2。native 调用一律回 uvcThread。
     * 设备不支持的项 PC 面板本就不渲染，这里按能力位再挡一次（双保险）。
     *
     * @return true=已排进下发队列；false=无相机/不支持
     */
    fun applyControl(key: String, value: Int): Boolean {
        val camera = uvcCamera ?: run {
            Log.d("meidui", "🔌 [OTG控制] $key=$value 忽略：UVC相机未打开")
            return false
        }
        val supported = UvcCapabilityStore.caps.value
            ?.controls?.firstOrNull { it.key == key }?.supported ?: false
        if (!supported) {
            Log.d("meidui", "🔌 [OTG控制] $key=$value 忽略：该设备不支持此项")
            return false
        }
        uvcHandler?.post {
            try {
                when (key) {
                    "zoom" -> camera.zoom = value
                    "focus" -> camera.focus = value
                    "autoFocus" -> camera.autoFocus = value != 0
                    "autoWhiteBalance" -> camera.autoWhiteBlance = value != 0
                    "whiteBalance" -> camera.whiteBlance = value
                    "brightness" -> camera.brightness = value
                    "contrast" -> camera.contrast = value
                    "saturation" -> camera.saturation = value
                    "hue" -> camera.hue = value
                    "sharpness" -> camera.sharpness = value
                    "gamma" -> camera.gamma = value
                    "gain" -> camera.gain = value
                    "powerline" -> writePowerline(camera, value)
                    else -> {
                        Log.d("meidui", "🔌 [OTG控制] 未知控制项 $key，忽略")
                        return@post
                    }
                }
                Log.d("meidui", "🔌 [OTG控制] ✅ $key=$value 已下发")
            } catch (e: Exception) {
                Log.d("meidui", "🔌 [OTG控制] ❌ $key=$value 失败: ${e.message}")
            }
        }
        return true
    }

    // 抗频闪：AUSBC 3.5.3 的 UVCCamera 确有 get/setPowerlineFrequency（已反编译核实），直连即可
    private fun readPowerline(camera: UVCCamera): Int? =
        try { camera.powerlineFrequency } catch (_: Throwable) { null }

    private fun writePowerline(camera: UVCCamera, value: Int) {
        try { camera.powerlineFrequency = value } catch (t: Throwable) {
            Log.d("meidui", "🔌 [OTG控制] powerline 下发失败: ${t.message}")
        }
    }

    /**
     * ⭐ 读**已协商**的帧率 —— 这才是权威值，不是估出来的。
     *
     * 为什么不能从 `getSupportedSizeList()` 拿：反编译 AUSBC 3.5.3 确认，解析器虽然认
     * `min_fps`/`max_fps` 字段，但本机 native 对这台设备只吐了简化 JSON
     * （`{"formats":[{"index":1,"type":6,"size":["320x240","640x480",...]}]}`，纯宽高无帧率），
     * 所以枚举阶段 `Size.fps` 必然是 null —— 不是没读，是这层压根没给。
     *
     * 而 `getPreviewSize()` 返回的是**协商完成后**的 Size，native 手里有 `dwFrameInterval`，
     * 三条路依次试：`getCurrentFrameRate()` → `fps[]` 取最大 → `intervals[]` 换算（10^7/间隔，UVC 标准 100ns 单位）。
     */
    private fun readNegotiatedFps(camera: UVCCamera): Int {
        val size = try { camera.previewSize } catch (_: Throwable) { null } ?: return 0

        // ① 库自己算好的当前帧率
        try {
            val f = size.getCurrentFrameRate()
            if (f > 0.5f) return Math.round(f)
        } catch (_: Throwable) { /* unknown frame rate or not ready */ }

        // ② fps[] 数组（需要库先 updateFrameRate 才会填）
        try {
            val maxFps = size.fps?.maxOrNull()?.toInt() ?: 0
            if (maxFps > 0) return maxFps
        } catch (_: Throwable) {}

        // ③ 原始帧间隔 intervals[]（UVC dwFrameInterval，100ns 单位）→ 最小间隔 = 最高帧率
        try {
            val field = size.javaClass.getField("intervals")
            val arr = field.get(size) as? IntArray
            val minInterval = arr?.filter { it > 0 }?.minOrNull() ?: 0
            if (minInterval > 0) return Math.round(10_000_000f / minInterval)
        } catch (_: Throwable) {}

        return 0
    }

    /** 在 uvc 线程同步执行（stopCapture/dispose 要求返回前帧已停） */
    private fun runOnUvcThreadBlocking(block: () -> Unit) {
        val handler = uvcHandler ?: run { block(); return }
        if (Looper.myLooper() == handler.looper) { block(); return }
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    // MARK: - 帧回调（native 采集线程调用）

    private val frameCallback = IFrameCallback { frame ->
        frameCbCount++   // ⭐ 在任何 early-return 之前自增：看门狗据此判断 native 是否真在吐帧
        val obs = observer ?: return@IFrameCallback
        if (!capturing || !streamRunning) return@IFrameCallback
        val w = frameWidth
        val h = frameHeight
        val expected = w * h * 3 / 2
        if (w <= 0 || h <= 0 || frame.remaining() < expected) {
            // ⭐ 第五十章：这里原来只打一次日志就永久静默——一旦尺寸持续对不上，
            //   帧就被全部丢掉、画面永远出不来，而日志里只有孤零零一行，没法定位。
            //   现在每 60 帧打一次，并反推 native 实际给的分辨率（按 NV21 1.5 字节/像素猜同宽高比的解），
            //   小分辨率黑屏就是靠这行定性。
            badFrameCount++
            if (badFrameCount % 60 == 1L) {
                val remain = frame.remaining()
                val guessPixels = remain * 2 / 3
                Log.d("meidui", "🔌 [OTG] ⚠️ 帧尺寸不符#$badFrameCount: native给了 ${remain}字节" +
                        "(≈${guessPixels}像素)，我方按协商值 ${w}x${h} 期望 ${expected}字节 → 全部丢弃(画面必黑)")
            }
            return@IFrameCallback
        }
        // 池化复用：NV21Buffer 零拷贝持有 byte[]，webrtc 用完（编码/预览后）release 归还
        val pooled = bufferPool.poll()
        val data = if (pooled != null && pooled.size == expected) pooled else ByteArray(expected)
        frame.get(data, 0, expected)
        // ⭐⭐ 2026-08-04 修「OTG 颜色不正（皮肤发蓝）」：native 回调虽标着 PIXEL_FORMAT_NV21，
        //   实际吐的是 NV12（U 前 V 后）——用户截图实锤：中性色（车顶灰/窗户白）全正常、
        //   唯独高饱和的皮肤翻成蓝色，正是 U/V 对调的指纹（中性色 U≈V≈128 换了看不出来）。
        //   自带摄像头没事是因为它走纹理通路，只有 OTG 走这条字节通路。
        //   在自己的拷贝上把色度平面逐对交换成真 NV21（1080p 约 1~2ms/帧，可接受）。
        //   下面的「色彩采样」在交换之后，日志里 V/U 已是修正后的值。
        if (UV_SWAP) {
            var i = w * h
            while (i + 1 < expected) {
                val t = data[i]; data[i] = data[i + 1]; data[i + 1] = t
                i += 2
            }
        }
        // ⭐ 2026-08-03 颜色诊断（查"OTG颜色不正"）：第10帧 + 之后每600帧，采样中心块 Y/V/U 均值
        //   上报自诊断通道。NV21 色度平面 V 前 U 后——**对着纯红色物体**：正常应 V≫128 且 U≪128；
        //   若相反 = native 实际吐的是 NV12（UV 序），色度对调，红蓝互换。采样 16x8 色度块，开销可忽略。
        if (frameCbCount == 10L || frameCbCount % 600L == 0L) {
            try {
                var sy = 0L; var sv = 0L; var su = 0L
                val cx = w / 2; val cy = h / 2
                var n = 0
                for (row in (cy - 8) until (cy + 8)) {
                    for (col in (cx - 16) until (cx + 16)) {
                        sy += data[row * w + col].toInt() and 0xFF; n++
                    }
                }
                val chromaBase = w * h
                var m = 0
                for (crow in (cy / 2 - 4) until (cy / 2 + 4)) {
                    for (ccol in (cx / 2 - 8) until (cx / 2 + 8)) {
                        val idx = chromaBase + crow * w + ccol * 2
                        sv += data[idx].toInt() and 0xFF
                        su += data[idx + 1].toInt() and 0xFF
                        m++
                    }
                }
                com.fz.yqlandroid.manager.OtgLogReporter.diag(
                    "色彩采样#$frameCbCount ${w}x${h} ${fmtName(activeFormatInt)}" +
                    " 中心块 Y=${sy / n} V=${sv / m} U=${su / m}" +
                    "（对纯红物体：正常 V≫128、U≪128；相反=色度NV12/NV21对调）")
            } catch (_: Exception) { /* 采样越界等异常不影响推流 */ }
        }
        val nv21 = NV21Buffer(data, w, h) { bufferPool.offer(data) }
        val ts = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val videoFrame = VideoFrame(nv21, 0, ts)
        obs.onFrameCaptured(videoFrame)
        videoFrame.release()
    }
}
