package com.fz.yqlandroid.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket管理器（STOMP协议）
 * 与iOS WebSocketManager 保持一致
 * 
 * 功能：
 * 1. STOMP over WebSocket 连接
 * 2. 设备状态定时推送（每秒）
 * 3. 接收后端配置更新指令
 * 4. 心跳保活 + 自动重连
 */
class WebSocketManager private constructor() {
    
    companion object {
        private const val TAG = "WebSocketManager"
        
        val instance: WebSocketManager by lazy { WebSocketManager() }
        
        // 推流状态（静态变量，供其他组件读写）
        @Volatile var isPublishingFlag: Int = 0
        @Volatile var publishingKbps: Int = 0
        @Volatile var publishingFps: Int = 0
        @Volatile var publishingSendFps: Int = 0
        @Volatile var publishingStreamKey: String = ""
        @Volatile var networkQuality: String = "unknown"
        @Volatile var packetLoss: Double = 0.0
        @Volatile var rtt: Int = 0
    }
    
    // 连接状态
    var isConnected: Boolean = false
        private set
    var connectionStatus: String = "未连接"
        private set

    // ⭐ PC 观看端心跳：PC 拉流出画面时每秒发 VIEWER_HEARTBEAT 到 /config topic。
    //   用「最近一次心跳时间」判断 PC 是否在看（SRS/P2P 通用；超 6s 无心跳=PC 未连接/画面没出）。
    @Volatile var lastViewerHeartbeatAtMs: Long = 0
        private set
    @Volatile var lastViewerHeartbeatFps: Int = 0
        private set

    // ⭐ §53.2 PC 在线注册表（PC_PRESENCE 心跳，与画面无关）。
    //   在线 ≠ 在看：「在线但没出画面」是本轮那类故障的现场特征，必须能分开显示。
    //   localIps：§53.4 推流前判「同不同 WiFi」用（比 /24 网段，不必先建 WebRTC 会话看 ICE）。
    //   publicIp：§53.20.2 PC 的公网出口 IP（防 /24 网段号撞车误判同 WiFi）。老版 PC 缺省=空。
    data class PcPresence(val lastSeenMs: Long, val viewing: Boolean, val h265Recv: Boolean,
                          val kernel: String, val localIps: List<String>, val publicIp: String = "")
    private val pcPresence = HashMap<String, PcPresence>()
    @Volatile var lastPcPresenceAtMs: Long = 0
        private set

    /** 在线 PC 台数（>4s 无心跳视为离线） */
    fun onlinePcCount(): Int = synchronized(pcPresence) {
        val cutoff = System.currentTimeMillis() - 4000
        pcPresence.entries.removeAll { it.value.lastSeenMs < cutoff }
        pcPresence.size
    }

    /** ⭐ §53.4：决策用的在线观看端快照（先剔除超时的），供 SessionPolicy 判同网段/H265 能力 */
    fun pcPresenceSnapshot(): Map<String, PcPresence> = synchronized(pcPresence) {
        val cutoff = System.currentTimeMillis() - 4000
        pcPresence.entries.removeAll { it.value.lastSeenMs < cutoff }
        HashMap(pcPresence)
    }

    /** 在线 PC 中是否存在**收不了 H265** 的内核（网页内核）→ SRS 编码需降 H264（§53.5） */
    fun anyViewerCannotRecvH265(): Boolean = synchronized(pcPresence) {
        val cutoff = System.currentTimeMillis() - 4000
        pcPresence.entries.removeAll { it.value.lastSeenMs < cutoff }
        pcPresence.values.any { !it.h265Recv }
    }

    /** 退登录/切设备时清空，避免上一台设备的观看端状态串到下一台 */
    fun clearPcPresence() {
        synchronized(pcPresence) { pcPresence.clear() }
        lastPcPresenceAtMs = 0
    }
    
    // WebSocket
    private var webSocket: WebSocket? = null
    private var deviceId: String? = null
    private var jwtToken: String? = null
    private var appContext: Context? = null  // 🔥 用于读取SharedPreferences和设备信息
    
    // STOMP 会话
    private var stompConnected: Boolean = false
    private var stompSubscriptions: MutableMap<String, String> = mutableMapOf()
    private var subscriptionIdCounter = 0
    
    // 定时器
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    
    // 回调
    var onConfigUpdate: ((Map<String, Any>) -> Unit)? = null
    var onSpecialMessage: ((String, Map<String, Any>) -> Unit)? = null  // 特殊消息
    var onSetFpsCommand: ((Int, String, Int, String) -> Unit)? = null   // 🔥 PC端set_fps指令(fps, urgency, bitrate, reason)
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    // ⭐ P2P WebRTC 信令（/topic/device/{id}/webrtc 收到的消息，转给 P2PManager.handleSignaling）
    var onWebRTCSignaling: ((Map<String, Any>) -> Unit)? = null
    // ⭐ WebSocket 重连成功（P2P 会话需要 ICE Restart）
    var onReconnected: (() -> Unit)? = null
    private var hadConnectedOnce = false
    // ⭐ §71 本机安装实例ID（连接时从 DeviceIDManager 取）
    private var installId: String? = null
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // MARK: - 连接
    
    fun connect(deviceId: String, token: String, context: Context? = null) {
        // 🔥 如果已经连着同一个设备，不重复连接
        if (this.deviceId == deviceId && stompConnected && webSocket != null) {
            println("wb [WS] ⏭️ 已连接，跳过重复连接")
            return
        }
        
        this.deviceId = deviceId
        this.jwtToken = token
        if (context != null) this.appContext = context.applicationContext
        
        // ⭐ §71 安装实例ID：连接带上，后端单活校验（克隆机=非活跃安装 → 握手被拒/被硬踢 4001）
        installId = appContext?.let { DeviceIDManager.getInstallId(it) } ?: installId
        val url = "${APIConfig.BASE_STOMP_WS_URL}?token=$token&deviceId=$deviceId" +
                (if (!installId.isNullOrEmpty()) "&installId=$installId" else "")
        println("wb [WS] 🔄 连接URL=$url")
        println("wb [WS] deviceId=$deviceId, token=${token.take(20)}...")
        
        // 清理旧连接
        isManualDisconnect = true  // 标记为内部清理，不触发重连
        reconnectJob?.cancel()
        statusJob?.cancel()
        heartbeatJob?.cancel()
        stompConnected = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        isManualDisconnect = false  // 🔥 重置标记，后续异常断开可以自动重连
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("wb [WS] ✅ WebSocket已连接, response=${response.code}")
                sendStompConnect()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleStompFrame(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("wb [WS] 🔌 正在关闭: code=$code, reason=$reason")
                updateState(false)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("wb [WS] 🔌 已关闭: code=$code, reason=$reason, 手动=$isManualDisconnect")
                updateState(false)
                // ⭐ §71 后端传输层硬踢（4001=kicked-by-new-install）：不重连，走被踢下线流程
                if (code == 4001) {
                    isManualDisconnect = true
                    onSpecialMessage?.invoke("KICKED", mapOf<String, Any>(
                        "message" to "该账号已在另一台手机上登录，本机已被下线"))
                    return
                }
                if (!isManualDisconnect) {
                    scheduleReconnect()  // 🔥 只有非手动断开才重连
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("wb [WS] ❌ 连接失败: ${t.message}, response=${response?.code}")
                updateState(false)
                if (!isManualDisconnect) {
                    scheduleReconnect()  // 🔥 只有非手动断开才重连
                }
            }
        })
        
        connectionStatus = "连接中..."
    }
    
    // MARK: - 断开
    
    private var isManualDisconnect = false  // 🔥 标记是否手动断开（手动断开不重连）
    
    fun disconnect() {
        isManualDisconnect = true  // 🔥 标记为手动断开
        // ⭐ §53.4：手动断开 = 退登录/切设备 → 清空观看端注册表与本次会话定案，
        //   避免上一台设备/上一个账号的观看端状态串到下一次推流决策里。
        SessionPolicy.reset()
        statusJob?.cancel()
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        
        stompConnected = false
        stompSubscriptions.clear()
        statusPushCount = 0
        
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        
        updateState(false)
        println("wb [WS] 🔌 手动断开连接")
    }
    
    // MARK: - STOMP 协议
    
    private fun sendStompConnect() {
        val frame = buildStompFrame("CONNECT", mapOf(
            "accept-version" to "1.1,1.2",
            "heart-beat" to "10000,10000"
        ))
        webSocket?.send(frame)
    }
    
    private fun handleStompFrame(raw: String) {
        val lines = raw.split("\n")
        if (lines.isEmpty()) return
        
        val command = lines[0].trim()
        
        when (command) {
            "CONNECTED" -> {
                println("wb [WS] ✅ STOMP已连接")
                stompConnected = true
                updateState(true)
                
                // 订阅设备配置通道 + P2P 信令通道（与 iOS 一致）
                deviceId?.let { id ->
                    val dest = "/topic/device/$id/config"
                    println("wb [WS] 📩 订阅: $dest")
                    subscribe(dest)
                    val webrtcDest = "/topic/device/$id/webrtc"
                    println("wb [WS] 📩 订阅: $webrtcDest")
                    subscribe(webrtcDest)
                }
                
                // ⭐ 重连成功通知（首次连接不算重连）
                if (hadConnectedOnce) {
                    onReconnected?.invoke()
                }
                hadConnectedOnce = true
                
                // 启动状态推送
                println("wb [WS] 🔄 启动设备状态推送（每秒）")
                startStatusPush()
            }
            "MESSAGE" -> {
                // 解析消息体（按 destination 头路由：/webrtc=P2P信令，其余=配置）
                val bodyIndex = raw.indexOf("\n\n")
                if (bodyIndex != -1) {
                    val headerBlock = raw.substring(0, bodyIndex)
                    val destination = headerBlock.split("\n")
                        .firstOrNull { it.startsWith("destination:") }
                        ?.substringAfter("destination:")?.trim() ?: ""
                    var body = raw.substring(bodyIndex + 2).trimEnd('\u0000')
                    if (destination.contains("/webrtc")) {
                        parseWebRTCSignaling(body)
                    } else {
                        parseConfigMessage(body)
                    }
                }
            }
            "ERROR" -> {
                Log.e(TAG, "❌ STOMP错误: $raw")
            }
        }
    }
    
    private fun subscribe(destination: String) {
        val id = "sub-${subscriptionIdCounter++}"
        val frame = buildStompFrame("SUBSCRIBE", mapOf(
            "id" to id,
            "destination" to destination
        ))
        webSocket?.send(frame)
        stompSubscriptions[destination] = id
        Log.d(TAG, "📩 已订阅: $destination")
    }
    
    private var statusPushCount: Int = 0
    
    private fun sendStompMessage(destination: String, body: String) {
        if (!stompConnected) return
        val frame = buildStompFrame("SEND", mapOf(
            "destination" to destination,
            "content-type" to "application/json"
        ), body)
        val sent = webSocket?.send(frame) ?: false
        // 每10次状态推送打印一次（避免刷屏）
        if (body.contains("CONFIG_STATE")) {
            statusPushCount++
            if (statusPushCount % 10 == 1) {
                println("wb [WS] 📤 状态推送#$statusPushCount: publishStatus=${isPublishingFlag}, fps=${publishingFps}, kbps=${publishingKbps}, sent=$sent")
            }
        }
    }
    
    private fun buildStompFrame(
        command: String,
        headers: Map<String, String>,
        body: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append(command).append("\n")
        headers.forEach { (key, value) ->
            sb.append("$key:$value\n")
        }
        sb.append("\n")
        sb.append(body)
        sb.append("\u0000")
        return sb.toString()
    }
    
    // MARK: - 消息解析
    
    private fun parseConfigMessage(body: String) {
        try {
            val json = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return
            val type = json["type"] as? String
            val messageDeviceId = json["deviceId"] as? String ?: ""
            
            when (type) {
                "CONFIG_UPDATE" -> {
                    // 🔥 跳过自己发送的消息（与iOS一致）
                    val msgOperator = json["operator"] as? String
                    val myUsername = deviceId ?: ""
                    if (!msgOperator.isNullOrEmpty() && msgOperator == myUsername) {
                        Log.d(TAG, "⏭️ 跳过自己发送的消息 (operator=$msgOperator)")
                        return
                    }
                    
                    val config = json["config"] as? Map<String, Any>
                    if (config != null) {
                        val ptype = config["ptype"] as? String ?: ""
                        Log.d(TAG, "📥 收到配置更新: ptype=$ptype, config=$config")
                        
                        // 🔥 更新ConfigManager缓存（与iOS一致）
                        try {
                            val configJson = gson.toJson(config)
                            val thinConfig = gson.fromJson(configJson, ThinRemoteConfig::class.java)
                            ConfigManager.currentConfig = thinConfig
                            appContext?.let { ConfigManager.cacheConfig(it, thinConfig) }
                        } catch (_: Exception) {}
                        
                        onConfigUpdate?.invoke(config)
                    }
                    
                    // 🔥 处理 cmd=set_fps 指令（与iOS一致：PC端自适应FPS）
                    val cmd = json["cmd"] as? String
                    if (cmd == "set_fps") {
                        handleSetFpsCommand(json)
                    }
                }
                
                // 🔥 重置推流（验证deviceId后执行）
                "RESET_PUBLISH" -> {
                    if (messageDeviceId == deviceId) {
                        Log.d(TAG, "🔄 RESET_PUBLISH: deviceId匹配，重置推流")
                        onSpecialMessage?.invoke("RESET_PUBLISH", json)
                    } else {
                        Log.d(TAG, "🔄 RESET_PUBLISH: deviceId不匹配，忽略")
                    }
                }
                
                // 🔥 睡眠（停止采集）
                "shuimian" -> {
                    if (messageDeviceId == deviceId) {
                        Log.d(TAG, "💤 shuimian: deviceId匹配，停止采集")
                        onSpecialMessage?.invoke("shuimian", json)
                    }
                }
                
                // 🔥 唤醒（重新推流）
                "gongzuo" -> {
                    if (messageDeviceId == deviceId) {
                        Log.d(TAG, "☀️ gongzuo: deviceId匹配，重新推流")
                        onSpecialMessage?.invoke("gongzuo", json)
                    }
                }
                
                // 🔥 试用断开（保存试用信息到SharedPreferences，与iOS一致）
                "TryDisconnect" -> {
                    handleTryDisconnect(json)
                }

                // ⭐ §71 单活互踢：另一台手机（不同 installId）用同 deviceId 登录，后端广播 KICKED。
                //   keepInstallId=胜出方（新登录那台）；本机匹配则忽略，不匹配则停推+退回登录页。
                "KICKED" -> {
                    if (messageDeviceId == deviceId) {
                        val keep = json["keepInstallId"] as? String ?: ""
                        if (keep.isNotEmpty() && keep == installId) {
                            Log.d(TAG, "🥾 KICKED: 本机是新登录方，忽略")
                        } else {
                            Log.d(TAG, "🥾 KICKED: 本机被顶下线 (keep=${keep.take(8)}, mine=${installId?.take(8)})")
                            onSpecialMessage?.invoke("KICKED", json)
                        }
                    }
                }
                
                "CONFIG_STATE" -> {
                    // 状态回传，忽略
                }

                // ⭐ 第五十章：自己发出去的 OTG 能力快照会回到同一 topic，忽略
                "OTG_CAPS" -> { }

                // ⭐ PC 拉流心跳（PC 出画面时每秒一条）→ 记录时间戳供「PC 已连接」显示
                "VIEWER_HEARTBEAT" -> {
                    lastViewerHeartbeatAtMs = System.currentTimeMillis()
                    lastViewerHeartbeatFps = (json["fps"] as? Number)?.toInt() ?: 0
                }

                // ⭐ §53.2 PC 在线心跳（每秒一条，**与有没有画面无关**）。
                //   有它才能把「PC 在线」和「PC 在看」分成两个状态——以前只有拉流心跳，
                //   PC 登录着但没画面时显示「PC未连接」，把故障现象说成了对方没上线。
                //   h265Recv：PC 内核能否接收 H265（网页内核=Chromium 134 收 H265 必黑屏），
                //   供 §53.5 编码仲裁（SRS 模式按最弱观看端降 H264）。
                "PC_PRESENCE" -> {
                    val pcId = json["fromDevice"] as? String ?: ""
                    if (pcId.isNotEmpty()) {
                        lastPcPresenceAtMs = System.currentTimeMillis()
                        val viewing = json["viewing"] as? Boolean ?: false
                        val h265Recv = json["h265Recv"] as? Boolean ?: true   // 旧版 PC 视为能收
                        val kernel = json["kernel"] as? String ?: "unknown"
                        val localIps = (json["localIps"] as? String ?: "")
                            .split(",").filter { it.isNotBlank() }
                        // ⭐ §53.20.2：PC 的公网出口 IP（防 /24 网段号撞车误判同 WiFi）
                        val publicIp = json["publicIp"] as? String ?: ""
                        var inputChanged = false
                        var isNew = false
                        synchronized(pcPresence) {
                            val old = pcPresence[pcId]
                            isNew = old == null
                            pcPresence[pcId] = PcPresence(System.currentTimeMillis(), viewing,
                                                          h265Recv, kernel, localIps, publicIp)
                            // 只有"会改变决策"的字段变了才评估重新协商，避免每秒心跳都跑一遍
                            inputChanged = isNew || old?.h265Recv != h265Recv ||
                                           old?.localIps != localIps || old?.publicIp != publicIp
                            if (isNew) {
                                Log.d("meidui", "🖥 [PC在线] $pcId 上线（内核=$kernel 能收H265=$h265Recv 在看=$viewing 网段=$localIps 公网=${publicIp.ifEmpty { "未报" }}）")
                            }
                        }
                        if (inputChanged) {
                            SessionPolicy.onViewerInputChanged(
                                if (isNew) "PC上线($pcId)" else "PC网络/能力变化($pcId)",
                                isNewViewer = isNew)
                        }
                    }
                }
            }
            
            // 🔥 检查cmd字段（可能在任何type的消息中）
            val cmd = json["cmd"] as? String
            if (cmd == "set_fps" && type != "CONFIG_UPDATE") {
                handleSetFpsCommand(json)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: ${e.message}")
        }
    }
    
    // ⭐ P2P WebRTC 信令解析（/topic/device/{id}/webrtc）
    private fun parseWebRTCSignaling(body: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: return
            println("wb [P2P] 📥 收到信令: type=${json["type"]}, from=${json["fromDevice"]}")
            onWebRTCSignaling?.invoke(json)
        } catch (e: Exception) {
            Log.e(TAG, "解析 P2P 信令失败: ${e.message}")
        }
    }
    
    // MARK: - ⭐ P2P WebRTC 信令发送（统一发到 /app/webrtc/signal，与 iOS 完全一致）
    
    // ⭐ §53.25：epoch = PC 发起本轮协商时生成的轮次标识（WEBRTC_REQUEST 带来，会话记住）。
    //   该会话所有出站信令回带它，PC 只收当前轮次——幽灵 Offer/迟到 ICE 从协议层根绝。
    //   null = 老版 PC 没带（兼容），不写字段。
    fun sendWebRTCSignalingSDP(sdpType: String, sdp: String, toDevice: String, epoch: Long? = null) {
        val id = deviceId ?: return
        val payload = mutableMapOf<String, Any>(
            "type" to "WEBRTC_SDP", "sdpType" to sdpType, "sdp" to sdp,
            "fromDevice" to id, "toDevice" to toDevice
        )
        if (epoch != null) payload["epoch"] = epoch
        sendWebRTCSignalingPayload(payload)
    }
    
    fun sendWebRTCSignalingICE(candidate: String, sdpMid: String, sdpMLineIndex: Int, toDevice: String, epoch: Long? = null) {
        val id = deviceId ?: return
        val payload = mutableMapOf<String, Any>(
            "type" to "WEBRTC_ICE", "candidate" to candidate, "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex, "fromDevice" to id, "toDevice" to toDevice
        )
        if (epoch != null) payload["epoch"] = epoch
        sendWebRTCSignalingPayload(payload)
    }
    
    fun sendWebRTCSignaling(type: String, reason: String = "", toDevice: String, epoch: Long? = null) {
        val id = deviceId ?: return
        val payload = mutableMapOf<String, Any>("type" to type, "fromDevice" to id, "toDevice" to toDevice)
        if (reason.isNotEmpty()) payload["reason"] = reason
        if (epoch != null) payload["epoch"] = epoch
        sendWebRTCSignalingPayload(payload)
    }
    
    private fun sendWebRTCSignalingPayload(payload: Map<String, Any>) {
        sendStompMessage("/app/webrtc/signal", gson.toJson(payload))
        println("wb [P2P] 📤 信令已发送: type=${payload["type"]}, to=${payload["toDevice"]}")
    }
    
    // 🔥 处理 set_fps 指令（与iOS handleSetFpsCommand一致）
    private fun handleSetFpsCommand(msgDict: Map<String, Any>) {
        val fps = (msgDict["fps"] as? Number)?.toInt() ?: return
        val urgency = msgDict["urgency"] as? String ?: "normal"
        val reason = msgDict["reason"] as? String ?: ""
        val bitrate = (msgDict["bitrate"] as? Number)?.toInt() ?: 0
        
        Log.d(TAG, "🎯 [set_fps] 收到PC端指令: fps=$fps, urgency=$urgency, reason=$reason, bitrate=$bitrate")
        
        // 通过回调传递给WebRTCManager
        onSetFpsCommand?.invoke(fps, urgency, bitrate, reason)
    }
    
    // 🔥 处理 TryDisconnect（与iOS完全一致：保存试用信息到SharedPreferences）
    private fun handleTryDisconnect(msgDict: Map<String, Any>) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
        
        val shouldDisconnect = msgDict["shouldDisconnect"] as? Boolean ?: false
        val trialRequired = msgDict["trialRequired"] as? Boolean ?: false
        val activated = msgDict["activated"] as? Boolean ?: false
        val trialEnded = msgDict["trialEnded"] as? Boolean ?: false
        val isDailyTrial = msgDict["isDailyTrial"] as? Boolean ?: false
        
        // 🔥 保存到SharedPreferences（与iOS UserDefaults一致）
        prefs.edit().apply {
            putBoolean("trial_required", trialRequired)
            putBoolean("activated", activated)
            (msgDict["activationLevel"] as? Number)?.let { putInt("activation_level", it.toInt()) }
            (msgDict["activationLevelName"] as? String)?.let { putString("activation_level_name", it) }
            (msgDict["activationExpireAt"] as? String)?.let { putString("activation_expire_at", it) }
            // ⭐ §53.9：服务器推送的激活状态变化里也带开通时间（后台改等级/续期后无需重登即刷新「我的」页）
            (msgDict["activationTime"] as? String)?.let { putString("activation_time", it) }
            putBoolean("trial_ended", trialEnded)
            (msgDict["currentStage"] as? Number)?.let { putInt("current_stage", it.toInt()) }
            (msgDict["totalStages"] as? Number)?.let { putInt("total_stages", it.toInt()) }
            (msgDict["stageSeconds"] as? Number)?.let { putInt("stage_seconds", it.toInt()) }
            (msgDict["remainingSeconds"] as? Number)?.let { putInt("remaining_seconds", it.toInt()) }
            (msgDict["usedSeconds"] as? Number)?.let { putInt("used_seconds", it.toInt()) }
            putBoolean("is_daily_trial", isDailyTrial)
            (msgDict["activationRemainingSeconds"] as? Number)?.let { putInt("activation_remaining_seconds", it.toInt()) }
            apply()
        }
        
        // 🔥 判断是否需要强制退出（与iOS一致）
        var needDisconnect = shouldDisconnect
        var disconnectMessage = msgDict["message"] as? String ?: "试用时间已到"
        val activationRemainingSeconds = (msgDict["activationRemainingSeconds"] as? Number)?.toInt()
        val remainingSeconds = (msgDict["remainingSeconds"] as? Number)?.toInt()
        
        if (activated && isDailyTrial) {
            if (activationRemainingSeconds != null && activationRemainingSeconds <= 0) {
                needDisconnect = true
                disconnectMessage = "日试用已到期，请续费或扫码绑定"
            }
        } else if (!activated && trialRequired) {
            if (trialEnded) {
                needDisconnect = true
                disconnectMessage = "今日试用已用完，请明天再来或扫码绑定"
            } else if (remainingSeconds != null && remainingSeconds <= 0) {
                needDisconnect = true
                disconnectMessage = "当前阶段试用完成，请重启进入下一阶段"
            }
        }
        
        if (needDisconnect) {
            Log.d(TAG, "⏱️ TryDisconnect: ⚠️ 需要强制退出: $disconnectMessage")
            onSpecialMessage?.invoke("TryDisconnect", msgDict + mapOf("needDisconnect" to true, "disconnectMessage" to disconnectMessage))
        }
    }
    
    // MARK: - 设备状态推送
    
    private fun startStatusPush() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                sendDeviceStatus()
                delay(1000) // 每秒推送一次
            }
        }
    }
    
    /**
     * 🔥 设备状态推送（与iOS sendDeviceStatus完全一致）
     * 每秒推送一次，包含推流状态、网络质量、电量、试用信息等
     */
    private fun sendDeviceStatus() {
        val id = deviceId ?: return
        val destination = "/topic/device/$id/config"
        
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        isoFormat.timeZone = TimeZone.getTimeZone("UTC")
        
        // 从SharedPreferences读取保存的信息（与iOS UserDefaults一致）
        val ctx = appContext
        val tokenPrefs = ctx?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
        val streamPushIp = tokenPrefs?.getString("stream_push_ip", "") ?: ""
        
        // 试用/激活信息
        val trialRequired = tokenPrefs?.getBoolean("trial_required", false) ?: false
        val activated = tokenPrefs?.getBoolean("activated", false) ?: false
        val activationLevel = tokenPrefs?.getInt("activation_level", 0) ?: 0
        val activationLevelName = tokenPrefs?.getString("activation_level_name", "") ?: ""
        val activationExpireAt = tokenPrefs?.getString("activation_expire_at", "") ?: ""
        val trialEnded = tokenPrefs?.getBoolean("trial_ended", false) ?: false
        val currentStage = tokenPrefs?.getInt("current_stage", 0) ?: 0
        val totalStages = tokenPrefs?.getInt("total_stages", 0) ?: 0
        val stageSeconds = tokenPrefs?.getInt("stage_seconds", 0) ?: 0
        val remainingSeconds = tokenPrefs?.getInt("remaining_seconds", 0) ?: 0
        val usedSeconds = tokenPrefs?.getInt("used_seconds", 0) ?: 0
        val isDailyTrial = tokenPrefs?.getBoolean("is_daily_trial", false) ?: false
        val activationRemainingSeconds = tokenPrefs?.getInt("activation_remaining_seconds", 0) ?: 0
        // 🔥 可用画质列表（登录时下发，供PC/试用门控显示）
        val qualityAccess = tokenPrefs?.getString("quality_access", "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList<String>()
        
        // ⭐ 连接方式由 WebRTCManager 实时决策（0=SRS, 1=P2P），PC 跟随 connectstype 切换（与 iOS 一致）
        val connectstype = WebRTCManager.effectiveConnectstype
        val connectMode = if (connectstype == 1) "p2p" else "srs"
        
        val state = mutableMapOf<String, Any>(
            "networkType" to getNetworkType(),
            "publishStatus" to isPublishingFlag,
            "streamKey" to publishingStreamKey,
            // ⭐ 第五十章：摄像头模式 + OTG 能力版本号（各几字节，随心跳常带）。
            //   PC 靠 cameraMode 决定走 otg_ 独立通道；capsVersion 变了才去要一次完整能力快照。
            "cameraMode" to (tokenPrefs?.getString("camera_mode", "builtin") ?: "builtin"),
            "otgCapsVersion" to com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.version,
            "streamPushIp" to streamPushIp,
            "connectstype" to connectstype,
            "connectMode" to connectMode,
            // ⭐ H265：P2P 实际生效编码（"h264"/"h265"），PC 据此选择解码管线（与 iOS 一致）
            "videoCodec" to H265Support.effectiveCodec,
            // ⭐ §53.4.5「互相监督」：本次链路/编码是**怎么定下来的**（人话），PC 顶栏直接显示
            "connectReason" to SessionPolicy.connectReason,
            "p2pViewerCount" to P2PManager.currentViewerCount,
            "kbps" to publishingKbps,
            "fps" to publishingFps,
            "sendFps" to publishingSendFps,
            "networkQuality" to networkQuality,
            "packetLoss" to packetLoss,
            "rtt" to rtt,
            "deviceType" to mapOf(
                "os" to "Android ${Build.VERSION.RELEASE}",
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}"
            ),
            "battery" to getBatteryLevel(),
            // 试用/激活信息（与iOS一致）
            "trialRequired" to trialRequired,
            "activated" to activated,
            "activationLevel" to activationLevel,
            "activationLevelName" to activationLevelName,
            "activationExpireAt" to activationExpireAt,
            "qualityAccess" to qualityAccess,
            "trialEnded" to trialEnded,
            "currentStage" to currentStage,
            "totalStages" to totalStages,
            "stageSeconds" to stageSeconds,
            "remainingSeconds" to remainingSeconds,
            "usedSeconds" to usedSeconds,
            "isDailyTrial" to isDailyTrial,
            "activationRemainingSeconds" to activationRemainingSeconds
        )
        
        val payload = mapOf(
            "type" to "CONFIG_STATE",
            "deviceId" to id,
            "state" to state,
            "timestamp" to isoFormat.format(Date())
        )
        
        val json = gson.toJson(payload)
        sendStompMessage(destination, json)
    }
    
    /**
     * ⭐ 第五十章：上报 OTG(UVC) 能力快照 → PC 动态生成调节面板。
     *
     * 单发（不挂在每秒心跳上，包大得多）：UVC 开流枚举完主动推一次；PC 发 `otg_get_caps` 再补推。
     * 走的是与 CONFIG_STATE 同一条 STOMP 长连接、同一个 `/config` topic，只是 type 不同。
     */
    fun sendOtgCaps() {
        val id = deviceId ?: return
        val caps = com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.caps.value ?: run {
            println("wb [WS] ⏭️ sendOtgCaps: 当前无 OTG 能力快照（未开流/自带摄像头模式）")
            return
        }
        val payload = mapOf(
            "type" to "OTG_CAPS",
            "deviceId" to id,
            "caps" to mapOf(
                "cameraMode" to "otg",
                "deviceName" to caps.deviceName,
                "width" to caps.width,
                "height" to caps.height,
                "format" to caps.format,       // 当前实际采集格式 MJPEG/YUYV
                "version" to caps.version,
                // 当前生效的推送帧率/码率：设备侧才是真值（初始值来自后端下发的配置），
                // PC 面板照它显示，不要自己猜一个缺省
                "pushFps" to com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.pushFps,
                "bitratePct" to com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.bitratePct,
                // 当前热控推流上限（0=无限制）——"fps拖不上去"多半是它，面板要显示出来
                "thermalCapFps" to com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.thermalCapFps,
                // 档位列表 = 设备枚举出的分辨率（几档就是几档）
                // encodable=false 的档位硬件编码器吃不下，选了必黑，PC 不给选
                "sizes" to caps.sizes.map {
                    mapOf("width" to it.width, "height" to it.height,
                          "maxFps" to it.maxFps, "maxKbps" to it.maxKbps,
                          "encodable" to it.encodable,
                          "encMaxFps" to it.encMaxFps)   // 该尺寸编码器最高帧率=推流真实上限
                },
                // 硬件可调项（PC 只渲染 supported=true 的）
                "controls" to caps.controls.map {
                    mapOf(
                        "key" to it.key, "label" to it.label, "type" to it.type,
                        "supported" to it.supported, "cur" to it.cur,
                        "min" to it.min, "max" to it.max, "options" to it.options
                    )
                }
            ),
            "timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        )
        sendStompMessage("/topic/device/$id/config", gson.toJson(payload))
        println("wb [WS] 📤 OTG能力快照已上报: ${caps.sizes.size}档分辨率, ${caps.controls.count { it.supported }}项可调, ver=${caps.version}")
    }

    /**
     * 获取电池电量 (0~100)
     */
    private fun getBatteryLevel(): Int {
        val ctx = appContext ?: return -1
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }
    
    /**
     * 获取网络类型（WiFi/4G/5G/Unknown）
     */
    private fun getNetworkType(): String {
        val ctx = appContext ?: return "Unknown"
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "Unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (_: Exception) { "Unknown" }
    }
    
    /**
     * 发送FPS更新消息（通知PC端当前推流FPS）
     */
    fun sendFpsUpdate(fps: Int) {
        val id = deviceId ?: return
        val destination = "/topic/device/$id/config"
        
        // 推送FPS × 4 = 后端FPS（与iOS一致）
        val backendFps = fps * 4
        
        val config = mapOf(
            "fps" to backendFps,
            "device_id" to id,
            "ptype" to "fps"
        )
        
        val payload = mapOf(
            "type" to "CONFIG_UPDATE",
            "deviceId" to id,
            "config" to config,
            // ⭐ 与 iOS 一致：带 operator=自己，广播回来时被 parseConfigMessage 的
            //   「跳过自己发送的消息」自检过滤（此前缺这个字段 → 自检永远不命中 →
            //   自己上报的 fps 回声被当成后端新指令处理，见 §21.21b 回声棘轮）
            "operator" to id,
            "timestamp" to System.currentTimeMillis()
        )
        
        val json = gson.toJson(payload)
        sendStompMessage(destination, json)
        Log.d(TAG, "📤 FPS更新: 推送${fps}fps → 后端${backendFps}fps")
    }
    
    /**
     * 🔥 发送 set_fps_ack 确认（与iOS一致）
     */
    fun sendSetFpsAck(fps: Int, status: String = "applied") {
        val id = deviceId ?: return
        val destination = "/topic/device/$id/config"
        val payload = mapOf(
            "cmd" to "set_fps_ack",
            "fps" to fps,
            "status" to status,
            "timestamp" to System.currentTimeMillis()
        )
        sendStompMessage(destination, gson.toJson(payload))
        Log.d(TAG, "🎯 [set_fps_ack] fps=$fps, status=$status")
    }
    
    // MARK: - 重连
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000) // 5秒后重连
            val id = deviceId
            val token = jwtToken
            if (id != null && token != null) {
                Log.d(TAG, "🔄 尝试重连...")
                connect(id, token)
            }
        }
    }
    
    private fun updateState(connected: Boolean) {
        isConnected = connected
        connectionStatus = if (connected) "已连接" else "未连接"
        onConnectionChanged?.invoke(connected)
    }
    
    fun destroy() {
        scope.cancel()
        disconnect()
    }
}
