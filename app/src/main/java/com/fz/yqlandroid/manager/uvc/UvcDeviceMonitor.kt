package com.fz.yqlandroid.manager.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.CopyOnWriteArraySet

/**
 * ⭐ 外接 OTG 摄像头（UVC）设备监视器 —— 第四十八章，与自带摄像头链路完全分离。
 *
 * 职责：USB 插拔广播注册、UVC 设备枚举（AUSBC 内部按 USB class 14 过滤）、
 * USB 权限弹窗、向 [UvcVideoCapturer] 提供已授权的 UsbControlBlock。
 *
 * 仅登录页选「外接OTG」（token_prefs.camera_mode == "otg"）后由 UvcVideoCapturer 启动，
 * 自带摄像头模式下本类零触碰。
 *
 * 实现注意：AUSBC 的 USBMonitor 是进程级单例（USBMonitor.getInstance），
 * 因此这里只做 register/unRegister，绝不调 destroy()（会毁掉单例，之后再也起不来）。
 */
object UvcDeviceMonitor {

    private const val TAG = "UvcDeviceMonitor"

    /** 授权后多久没等到连接回调就自诊断 + 重试（ms） */
    private const val CONNECT_WATCHDOG_MS = 3000L
    /** 我方自管授权用的广播 action（AUSBC 的连接回调没来时兜底走这条自管路径） */
    private const val ACTION_UVC_PERMISSION = "com.fz.yqlandroid.USB_PERMISSION"

    interface Listener {
        /** 设备已授权可用（USB 权限已拿到，ctrlBlock 可直接开 UVCCamera） */
        fun onUvcDeviceReady(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock)
        /** 设备被拔出/断开 */
        fun onUvcDeviceGone(device: UsbDevice)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var client: MultiCameraClient? = null
    private var appContext: Context? = null

    /** 当前已授权设备（采集器重启场景直接复用，无需再等广播） */
    @Volatile private var readyDevice: UsbDevice? = null
    @Volatile private var readyCtrlBlock: USBMonitor.UsbControlBlock? = null

    /** 已发起过权限请求的设备（deviceId），防重复弹窗 */
    private val permissionRequested = HashSet<Int>()

    /** 我方自管授权的广播接收器（懒注册，stop 注销） */
    private var usbReceiver: BroadcastReceiver? = null
    /** 授权→连接看门狗重试次数（防止无限重试刷屏） */
    private var connectWatchdogRuns = 0

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** 当前已授权设备，无则 null */
    fun currentReady(): Pair<UsbDevice, USBMonitor.UsbControlBlock>? {
        val d = readyDevice ?: return null
        val cb = readyCtrlBlock ?: return null
        return d to cb
    }

    /**
     * 启动监视（幂等）。注册 USB 插拔广播；对已插着的设备主动补一轮权限请求
     * （部分 ROM 的 USBMonitor register 不回放已插设备的 onAttach）。
     */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (client == null) {
            client = MultiCameraClient(ctx, connectCallback)
        }
        client?.register()
        // 已插着的设备主动请求权限（register 的 attach 回放不一定来）
        mainHandler.postDelayed({ requestPermissionForAttached() }, 500)
        Log.d("meidui", "🔌 [OTG] UvcDeviceMonitor 启动（注册USB广播）")
    }

    /** 停止监视：只注销广播，不 destroy 单例 USBMonitor（见类注释） */
    fun stop() {
        try { client?.unRegister() } catch (e: Exception) {
            Log.w(TAG, "unRegister failed: ${e.message}")
        }
        usbReceiver?.let { try { appContext?.unregisterReceiver(it) } catch (_: Exception) {} }
        usbReceiver = null
        permissionRequested.clear()
        connectWatchdogRuns = 0
        Log.d("meidui", "🔌 [OTG] UvcDeviceMonitor 停止（注销USB广播）")
    }

    private fun requestPermissionForAttached() {
        val c = client ?: return
        val devices = try { c.getDeviceList() } catch (e: Exception) { null } ?: return
        // ⚠️ getDeviceList 返回全部 USB 设备（含 OTG 集线器/键鼠），必须过滤出 UVC 摄像头(class 14)
        val camera = devices.firstOrNull { CameraUtils.isUsbCamera(it) }
        if (camera == null) {
            Log.d("meidui", "🔌 [OTG] 未检测到外接UVC摄像头（USB设备${devices.size}个均非摄像头；请确认手机支持OTG且已插好）")
            return
        }
        requestPermissionOnce(camera)
    }

    private fun requestPermissionOnce(device: UsbDevice) {
        if (readyDevice != null) return   // 已有可用设备，不再抢
        if (!permissionRequested.add(device.deviceId)) return
        val ctx = appContext
        val usbManager = ctx?.getSystemService(Context.USB_SERVICE) as? UsbManager
        val has = usbManager?.hasPermission(device) ?: false
        Log.d("meidui", "🔌 [OTG] 请求USB权限: ${device.productName ?: device.deviceName} (deviceId=${device.deviceId}, hasPermission=$has)")

        if (has) {
            // 已有系统级 USB 权限 → 让 AUSBC 直接连接（USBMonitor.hasPermission=true 会直接 processConnect→onConnectDev）
            client?.requestPermission(device)
        } else if (usbManager != null && ctx != null) {
            // ⭐ 自管授权（第四十八章修复）：AUSBC 自带弹窗的结果广播在部分 Android 14+/国产 ROM 收不到，
            //   导致 onConnectDev 永不触发（相机开不了、capFps=0）。改用我方 PendingIntent+接收器拿授权，
            //   授权成功后再调 AUSBC.requestPermission（此刻 hasPermission=true 直连），绕开它那条收不到的广播。
            ensureUsbReceiver(ctx)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_UVC_PERMISSION).setPackage(ctx.packageName), flags)
            usbManager.requestPermission(device, pi)
            Log.d("meidui", "🔌 [OTG] 已弹出USB授权对话框（自管），等待用户点『确定』")
        } else {
            client?.requestPermission(device)   // 兜底：拿不到 UsbManager 时仍走 AUSBC
        }
        scheduleConnectWatchdog(device)
    }

    /** 懒注册我方 USB 授权结果接收器（Android 13+ 显式 NOT_EXPORTED） */
    private fun ensureUsbReceiver(ctx: Context) {
        if (usbReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION_UVC_PERMISSION) return
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d("meidui", "🔌 [OTG] USB授权结果: granted=$granted, dev=${device?.productName ?: device?.deviceName}")
                if (granted && device != null) {
                    // 已授权 → 让 AUSBC 连接（现在 hasPermission=true，直接 onConnectDev）
                    mainHandler.post { client?.requestPermission(device) }
                } else {
                    device?.let { permissionRequested.remove(it.deviceId) }
                    toast("未授权访问外接摄像头（请重插并点『确定』）")
                }
            }
        }
        val filter = IntentFilter(ACTION_UVC_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
        usbReceiver = r
    }

    /** 授权后 3s 仍无 onConnectDev（相机未开）→ 打印真实权限状态并有限次重试，帮助远程定位 */
    private fun scheduleConnectWatchdog(device: UsbDevice) {
        mainHandler.postDelayed({
            if (readyDevice != null) { connectWatchdogRuns = 0; return@postDelayed }
            val usbManager = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager
            val has = usbManager?.hasPermission(device) ?: false
            connectWatchdogRuns++
            Log.d("meidui", "🔌 [OTG] ⏳ 授权后${CONNECT_WATCHDOG_MS}ms仍未开流(onConnectDev未回调): hasPermission=$has 第${connectWatchdogRuns}次")
            if (connectWatchdogRuns > 3) {
                Log.d("meidui", "🔌 [OTG] ❌ 多次仍未开流。若 hasPermission=false=授权框未弹/未点确定；若=true=库连接回调异常或OTG供电不足反复重枚举")
                return@postDelayed
            }
            permissionRequested.remove(device.deviceId)
            if (has) {
                client?.requestPermission(device)          // 有权限没连上 → 再戳一次 AUSBC 连接
                scheduleConnectWatchdog(device)
            } else {
                requestPermissionOnce(device)              // 没权限 → 重新申请（内部自带看门狗）
            }
        }, CONNECT_WATCHDOG_MS)
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private val connectCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            device ?: return
            Log.d("meidui", "🔌 [OTG] 检测到外接摄像头插入: ${device.productName ?: device.deviceName}")
            requestPermissionOnce(device)
        }

        override fun onDetachDec(device: UsbDevice?) {
            device ?: return
            permissionRequested.remove(device.deviceId)
            if (readyDevice?.deviceId == device.deviceId) {
                readyDevice = null
                readyCtrlBlock = null
                Log.d("meidui", "🔌 [OTG] 外接摄像头已拔出: ${device.productName ?: device.deviceName}")
                toast("外接摄像头已断开")
                listeners.forEach { it.onUvcDeviceGone(device) }
            }
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            ctrlBlock ?: return
            readyDevice = device
            readyCtrlBlock = ctrlBlock
            connectWatchdogRuns = 0
            Log.d("meidui", "🔌 [OTG] USB权限已授予，设备可用: ${device.productName ?: device.deviceName}")
            toast("外接摄像头已连接")
            listeners.forEach { it.onUvcDeviceReady(device, ctrlBlock) }
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            // 与拔出同处理（授权撤销/异常断开）
            onDetachDec(device)
        }

        override fun onCancelDev(device: UsbDevice?) {
            device ?: return
            // 用户拒绝授权：清除标记，重插或重新进入推流页可再弹
            permissionRequested.remove(device.deviceId)
            Log.d("meidui", "🔌 [OTG] 用户拒绝了USB权限: ${device.productName ?: device.deviceName}")
            toast("未授权访问外接摄像头")
        }
    }
}
