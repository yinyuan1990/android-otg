package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.*

/**
 * P2P/WebRTC 直连管理类（对照 iOS P2PManager.swift 移植）。
 *
 * - 与 SRS 模式互斥：connect_mode == "p2p" 时由 WebRTCManager 启动本类，SRS 推流不启用。
 * - 多观看端：每个观看 PC 一个独立 PeerConnection（Offerer）。
 * - ⭐ §53.19/§53.21：P2P = **纯局域网直连（host-only）**。TURN 中继与 STUN 打洞代码已物理删除
 *   （用户拍板）：跨网一律走 SRS，本类只负责同 WiFi 的 host↔host 会话；ICE 失败 = 确认非局域网 → 回落 SRS。
 * - 信令走 WebSocketManager 的 /app/webrtc/signal（收 /topic/device/{id}/webrtc），
 *   协议与 iOS 完全一致，PC 端零改动。
 *
 * ⚠️ 移植时吸取的 iOS 坑点（详见文档《项目路径总览-各端速查.md》§21.5）：
 * 1. degradationPreference 必须 MAINTAIN_RESOLUTION（maintainFramerate 会导致弱网分辨率乱串）。
 * 2. 码率与帧率分开写 RtpParameters（一次全刷会互相牵连，「调码率改了 fps」）。
 * 3. 所有编码参数操作做成「AllSessions」遍历，绝不依赖单一 videoSender（iOS 断链半年的教训）。
 * 4. 不加任何周期强制 IDR（刚摘掉的攒帧卡顿根因）；关键帧纯按需（PLI/WS request_keyframe/切档）。
 */
class P2PManager(private val context: Context) {

    companion object {
        private const val TAG = "P2PManager"
        /** 当前 P2P 观看端数（供心跳上报，WebSocketManager 读取） */
        @Volatile var currentViewerCount: Int = 0
        private const val MAX_ICE_RETRIES = 2
    }

    /** 数据源：由 WebRTCManager 提供工厂、视频轨与当前编码参数 */
    interface DataSource {
        val p2pFactory: PeerConnectionFactory?
        val p2pLocalVideoTrack: VideoTrack?
        /** 当前码率区间（kbps）：Pair(min, max) */
        fun p2pBitrateRangeKbps(): Pair<Int, Int>
        /** 当前目标推送 FPS */
        fun p2pTargetFps(): Int
        /** 当前分辨率缩放比 */
        fun p2pScaleDown(): Double
    }

    var dataSource: DataSource? = null

    @Volatile var isActive = false
        private set
    /** 是否就绪接收观看请求（采集/视频轨已就绪） */
    @Volatile var isReadyForViewers = false

    // 每个观看 PC 一个独立会话（主线程访问）
    private val viewerSessions = LinkedHashMap<String, PeerConnection>()
    private val viewerSenders = LinkedHashMap<String, RtpSender>()
    private val pendingRemoteIce = HashMap<String, MutableList<IceCandidate>>()
    private val pendingIceRestart = HashSet<String>()
    private val iceRetryCount = HashMap<String, Int>()
    // ⭐ 会话创建时间：① WEBRTC_REQUEST 去重只在 3s 内生效（防切网后卡死的 CONNECTING 僵尸会话
    //   永远吞掉 PC 的重连请求）；② 创建后 1s 内到达的 WEBRTC_HANGUP 视为旧会话残留信令，忽略
    //   （§23.2 首开 17s 竞态：PC 先 HANGUP 旧会话再 REQUEST，两条消息乱序到达会把新会话干掉）。
    private val sessionCreatedAt = HashMap<String, Long>()
    // ⭐ §53.3①：PC 带来的 requestId（每次 connectP2P/重发递增）。变了就必须拆旧建新，
    //   不能再按"会话建立中"忽略——否则新登录的 PC 会被上一轮的幽灵会话吞掉请求。
    private val lastRequestId = HashMap<String, Long>()
    // ⭐ §54：同 epoch 重发到达且会话未连通、会话已建超过此毫秒数 → 判定 Offer 丢失，拆旧重发
    //   （PC 收到 Offer 就会停止重发，"同轮次重发还在来"本身就是没送达的证据）。
    private val STALE_OFFER_REBUILD_MS = 3000L
    // ⭐⭐ §53.25：会话 epoch——PC 每轮协商生成一个（重发不换、重建才换）。REQUEST 带来时记住；
    //   该会话所有出站信令回带；入站 Answer/ICE 轮次不符直接丢弃；同 epoch 重复 REQUEST 天然幂等。
    private val sessionEpoch = HashMap<String, Long>()

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 本机网络状态。§21.27：由 WebRTCManager 的统一网络监听喂入（仅用于检测"蜂窝↔WiFi 类型变化"切网信号）
    private var isOnCellular = false

    private val prefs get() = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    private val maxViewers: Int get() = prefs.getInt("max_p2p_viewers", 4).let { if (it > 0) it else 4 }

    /** 已连接（ICE connected/completed）的观看会话，供 WebRTCManager 采集码率/网络 stats。 */
    val connectedViewerPeerConnections: List<PeerConnection>
        get() = synchronized(viewerSessions) {
            // ⭐ 按 pcId 排序保证稳定顺序（iOS 曾因 Dictionary 无序导致多观看端统计基线来回跳 →
            //   假 PLI 风暴/假丢包，引发攒帧卡顿。LinkedHashMap 本身有序，排序双保险：
            //   首路观看端断开重连后插入顺序变化时基线也不跳）。
            viewerSessions.entries
                .sortedBy { it.key }
                .map { it.value }
                .filter {
                    val s = it.iceConnectionState()
                    s == PeerConnection.IceConnectionState.CONNECTED ||
                            s == PeerConnection.IceConnectionState.COMPLETED
                }
        }

    val viewerCount: Int get() = synchronized(viewerSessions) { viewerSessions.size }

    /** ⭐ [meidui 诊断] 各观看会话 ICE 状态一览（P2P 推送 fps=0 排查用） */
    fun sessionStatesSummary(): String = synchronized(viewerSessions) {
        if (viewerSessions.isEmpty()) "无观看会话(等PC发WEBRTC_REQUEST)"
        else viewerSessions.entries.joinToString(", ") { "${it.key}=${it.value.iceConnectionState()}" }
    }

    /** ⭐ [meidui 诊断] 首个已连接会话的视频 Sender（P2P 模式读编码器帧率上限/active 用，与统计源同序） */
    val firstConnectedSender: RtpSender?
        get() = synchronized(viewerSessions) {
            viewerSessions.entries.sortedBy { it.key }
                .firstOrNull {
                    val s = it.value.iceConnectionState()
                    s == PeerConnection.IceConnectionState.CONNECTED ||
                            s == PeerConnection.IceConnectionState.COMPLETED
                }?.let { viewerSenders[it.key] }
        }

    // MARK: - 生命周期

    fun start() {
        if (isActive) return
        isActive = true
        isReadyForViewers = true
        // §21.27：网络监听已统一收口到 WebRTCManager（P2P/SRS 共用），这里不再自注册
        Log.d(TAG, "✅ P2PManager 启动，maxViewers=$maxViewers（纯局域网直连，无中继/打洞）")
    }

    fun stop() {
        isReadyForViewers = false
        closeAllViewerSessions(notifyPC = true)
        isActive = false
        Log.d(TAG, "🛑 P2PManager 停止")
    }

    // MARK: - 网络事件（§21.27 统一入口在 WebRTCManager，这里只留出口）

    /** §21.27 由 WebRTCManager 的统一网络监听喂入蜂窝状态（切网检测用）。返回 true=类型变化（切网事件） */
    fun updateCellularState(cellular: Boolean): Boolean {
        val changed = cellular != isOnCellular
        isOnCellular = cellular
        return changed
    }

    /** §21.27 统一出口：WS 重连成功 / 切网（经 WebRTCManager.publishHealthCheck）→ 全部会话 ICE Restart */
    fun restartAllSessions(source: String) {
        if (viewerCount == 0) {
            Log.d(TAG, "📶 [$source] 无观看会话，等 PC 发 WEBRTC_REQUEST")
            return
        }
        Log.d(TAG, "🔌 [$source] 重连所有 P2P 会话")
        mainScope.launch { restartAllIceForNetworkSwitch() }
    }

    /** 兼容旧调用点 */
    fun onWebSocketReconnected() = restartAllSessions("WS重连")

    /**
     * ⭐ 需求#9（2026-07-31）：选择性恢复——**ICE 还活着的会话绝不动**（对齐 iOS recoverSessionsIfBroken）。
     * P2P 媒体是局域网直连、不经服务器：公网抖一下 WS 重连成功时 ICE 往往还活着，
     * 旧逻辑无条件拆所有会话重建 = 自己把好画面掐灭几秒。只拆 ICE 已死的会话并发
     * HANGUP(network_switch_reconnect)——PC 收到后不拆 pipeline、自动重发 REQUEST（既有路径）。
     * 真切网场景不走本函数（publishHealthCheck 里按 source 分流，切网仍走 restartAllSessions）。
     */
    fun recoverDeadSessionsOnly(source: String) {
        val dead = synchronized(viewerSessions) {
            viewerSessions.filter { (_, pc) ->
                val st = try { pc.iceConnectionState() } catch (_: Exception) { null }
                st != PeerConnection.IceConnectionState.CONNECTED &&
                st != PeerConnection.IceConnectionState.COMPLETED
            }.keys.toList()
        }
        if (dead.isEmpty()) {
            Log.d("meidui", "🔌 [$source] $viewerCount 个 P2P 会话 ICE 均存活 → 保画面不拆（需求#9）")
            return
        }
        Log.d("meidui", "🔌 [$source] 拆除 ${dead.size} 个 ICE 已死会话（存活的不动）")
        for (pcId in dead) {
            iceRetryCount[pcId] = 0
            val e = synchronized(viewerSessions) { sessionEpoch[pcId] }
            removeViewerSession(pcId, notifyPC = false)
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "network_switch_reconnect", pcId, e)
        }
        onNetworkSwitchReconnect?.invoke()
    }

    private fun restartAllIceForNetworkSwitch() {
        // ⭐ 切网重连关键修复：切网瞬间旧 WS 多半已死，此时发 ICE Restart Offer = 发进黑洞
        //   （PC 永远收不到），且会话被标 pendingIceRestart 卡住。改为等 WS 重连成功后
        //   （onWebSocketReconnected 会再次调本方法）统一重连，信令必达。
        if (!WebSocketManager.instance.isConnected) {
            Log.w(TAG, "📶 切网但 WS 未连接，ICE Restart 推迟到 WS 重连后统一执行")
            Log.d("meidui", "📶 [P2P切网] WS断开中，Offer 不发（防黑洞），等 WS 重连后统一 ICE Restart")
            return
        }
        // ⭐ 2026-07-09 修「切网后必须手动重登 PC 才出画面」根因（对标 iOS）：
        //   观看端恒为 PC GStreamer，其 webrtcbin 不支持在旧实例上 ICE Restart（收新 ufrag 的
        //   re-offer 不会重启 libnice/重新收集候选 → 卡死）。切网【不再尝试 ICE Restart】，
        //   一律拆会话 + HANGUP(network_switch_reconnect)，由 PC 整体重建 pipeline + 重发 REQUEST。
        val sessions = synchronized(viewerSessions) { viewerSessions.toMap() }
        Log.d("meidui", "📶 [P2P切网] 拆除并让 PC 重连 ${sessions.size} 个会话(不做ICE Restart): ${sessionStatesSummary()}")
        for ((pcId, _) in sessions) {
            iceRetryCount[pcId] = 0
            val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25：拆除前取轮次回带
            removeViewerSession(pcId, notifyPC = false)
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "network_switch_reconnect", pcId, e)
        }
        onNetworkSwitchReconnect?.invoke()   // 通知上层置"重连中"（StreamingScreen 左上角显示）
    }

    /** ⭐ 切网触发重连回调（WebRTCManager 用于置"重连中"给 UI，PC 心跳恢复后清除） */
    var onNetworkSwitchReconnect: (() -> Unit)? = null

    // ⭐ §53.21：原「传输策略(effectiveForceRelay) / §25.7e 线路预判(applyLanPrecheck) /
    //   loadIceServers」已物理删除——P2P 只做局域网 host↔host 直连，无 TURN/STUN；
    //   同不同 WiFi 由 SessionPolicy 在推流前判定（localIps 网段 + 公网出口 IP，§53.20.2）。

    // MARK: - 信令处理（由 WebSocketManager 收 /topic/device/{id}/webrtc 后转发进来）

    fun handleSignaling(message: Map<String, Any>) {
        val type = message["type"] as? String ?: return
        val fromDevice = message["fromDevice"] as? String ?: ""

        // ⭐ 崩溃修复（跨线程 use-after-free）：本方法由 WebSocketManager 在 OkHttp 读线程调用，
        //   而所有 PeerConnection 的创建/访问/释放本类都约定「主线程访问」（见 viewerSessions 声明处）。
        //   旧代码直接在 WS 线程里 createViewerSession / setRemoteDescription / pc.close()，
        //   同一批 pc 又被 Observer 里的 mainScope.launch{ addIceCandidate / iceConnectionState / retryIce }
        //   在主线程并发触碰 → PC 切网/重启发来 HANGUP 时 WS 线程 close() 释放 native 对象，主线程排队的
        //   ICE 回调打到已释放 pc → SIGSEGV（libjingle_peerconnection_so.so）。
        //   统一 hop 到 mainScope，让信令与 ICE/Observer 处理在同一线程串行，彻底消除 native 竞态。
        mainScope.launch {
            // ⭐⭐ §53.25：会话 epoch——PC 每轮协商生成一个（重发不换），我们记住并在该会话
            //   所有出站信令里回带；入站 Answer/ICE/HANGUP 轮次不符 = 上一轮的过期信令，直接丢弃。
            //   缺字段（老版 PC）= 跳过校验，退回时间窗行为。
            val msgEpoch = (message["epoch"] as? Number)?.toLong()
            fun staleEpoch(): Boolean {
                val cur = synchronized(viewerSessions) { sessionEpoch[fromDevice] } ?: return false
                val e = msgEpoch ?: return false
                if (e != cur) {
                    Log.w(TAG, "🗑 丢弃过期轮次信令 type=$type from=$fromDevice msgEpoch=$e 当前=$cur")
                    return true
                }
                return false
            }
            when (type) {
                "VIEWER_CONNECTED" -> Log.d(TAG, "✅ PC $fromDevice 已收到画面")
                // ⭐ §53.3①：真的拆会话（以前只打日志）。PC 退出时发的这条通知白发 → 会话留在
                //   CONNECTING 变"幽灵会话"，把 PC 下次登录的请求吞掉（iOS 侧同款问题更严重）。
                "VIEWER_DISCONNECTED" -> {
                    // ⭐⭐ §54.6（2026-07-31 PC 日志实锤）：加"新生会话保护"。旧版 PC 对每次内部
                    //   断开都广播这条消息（不带 epoch）且可能迟到——PC 已发新 REQUEST、我们刚建好
                    //   新会话发完 Offer 后它才到，无条件拆 = 新会话在 trickle ICE 前被杀 → 黑屏循环。
                    //   会话很年轻（<2s）时这条断开通知必是上一轮的迟到消息 → 忽略。
                    val had = synchronized(viewerSessions) { viewerSessions.containsKey(fromDevice) }
                    if (had) {
                        val ageMs = System.currentTimeMillis() -
                                (synchronized(viewerSessions) { sessionCreatedAt[fromDevice] } ?: 0L)
                        if (ageMs < 2000) {
                            Log.w(TAG, "🗑 PC $fromDevice 的 VIEWER_DISCONNECTED 迟到（会话仅 ${ageMs}ms）→ 忽略，保护新会话")
                        } else {
                            Log.d(TAG, "🔌 PC $fromDevice 断开 → 拆会话（防幽灵会话吞掉下次 WEBRTC_REQUEST）")
                            removeViewerSession(fromDevice, notifyPC = false)
                        }
                    } else {
                        Log.d(TAG, "🔌 PC $fromDevice 断开（无活动会话）")
                    }
                }
                "WEBRTC_REQUEST" -> {
                    if (!isReadyForViewers) {
                        WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "not_ready", fromDevice)
                        return@launch
                    }
                    // requestId：逐条消息的时间戳，仅日志关联；轮次判定用 epoch（§53.25）
                    val reqId = (message["requestId"] as? Number)?.toLong()
                    createViewerSession(fromDevice, reqId, msgEpoch)
                }
                "WEBRTC_SDP" -> {
                    val sdpType = message["sdpType"] as? String ?: ""
                    val sdp = message["sdp"] as? String ?: ""
                    if (sdpType == "answer" && !staleEpoch()) handleRemoteAnswer(sdp, fromDevice)
                }
                "WEBRTC_ICE" -> { if (!staleEpoch()) handleRemoteIce(message, fromDevice) }
                "WEBRTC_HANGUP" -> {
                    if (staleEpoch()) return@launch
                    // ⭐ §23.2 竞态保护：会话刚创建（<1s）就收到 HANGUP = PC 针对「上一个会话」的挂断
                    //   乱序迟到（PC 先 HANGUP 再 REQUEST，服务器转发顺序不保证）。无条件移除会把
                    //   正在 createOffer 的新会话干掉 → Offer 发到死会话 → 首开白等 15s+。
                    val createdAt = synchronized(viewerSessions) { sessionCreatedAt[fromDevice] } ?: 0L
                    val ageMs = System.currentTimeMillis() - createdAt
                    if (createdAt > 0 && ageMs < 1000) {
                        Log.w(TAG, "🛡️ 忽略 HANGUP($fromDevice)：会话仅 ${ageMs}ms 前创建，判定为旧会话残留信令")
                        Log.d("meidui", "🛡️ [P2P] 忽略旧会话残留 HANGUP(from=$fromDevice, 会话年龄=${ageMs}ms)")
                    } else {
                        removeViewerSession(fromDevice, notifyPC = false)
                    }
                }
            }
        }
    }

    private fun handleRemoteAnswer(sdp: String, pcId: String) {
        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return
        // ⭐ [meidui 诊断] 打出 PC Answer 实际协商到的视频 codec（应为 H264；出现 VP8/VP9 = munge 没生效）
        run {
            val codecs = Regex("a=rtpmap:\\d+\\s+([A-Za-z0-9]+)/90000").findAll(sdp)
                .map { it.groupValues[1] }.filter { !it.equals("rtx", true) }.distinct().toList()
            Log.d("meidui", "P2P Answer($pcId) 协商视频codec=${codecs.joinToString("/")}")
        }
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() {
                mainScope.launch {
                    pendingIceRestart.remove(pcId)
                    // flush 缓冲的远端 ICE
                    pendingRemoteIce.remove(pcId)?.forEach { pc.addIceCandidate(it) }
                    Log.d(TAG, "✅ 收到 PC $pcId Answer，会话建立中")
                }
            }
            override fun onSetFailure(e: String?) {
                Log.e(TAG, "❌ setRemoteDescription 失败($pcId): $e")
            }
        }, answer)
    }

    private fun handleRemoteIce(message: Map<String, Any>, pcId: String) {
        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return
        val candidate = message["candidate"] as? String ?: ""
        if (candidate.isEmpty()) return   // 忽略 end-of-candidates
        val mid = message["sdpMid"] as? String ?: "0"
        val mline = (message["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        val ice = IceCandidate(mid, mline, candidate)
        mainScope.launch {
            if (pc.remoteDescription == null || pendingIceRestart.contains(pcId)) {
                pendingRemoteIce.getOrPut(pcId) { mutableListOf() }.add(ice)
            } else {
                pc.addIceCandidate(ice)
            }
        }
    }

    // MARK: - 会话管理

    fun createViewerSession(pcId: String, requestId: Long? = null, epoch: Long? = null) {
        val ds = dataSource ?: run { Log.e(TAG, "❌ dataSource 为空"); return }
        val factory = ds.p2pFactory ?: run { Log.e(TAG, "❌ factory 为空"); return }

        synchronized(viewerSessions) {
            viewerSessions[pcId]?.let { existing ->
                // ⭐⭐ §53.25 幂等判据（确定性，优先）：同 epoch = 同一轮重发 → 幂等忽略；
                //   epoch 变了 = PC 新一轮（重建 pipeline）→ 拆旧建新。
                val cur = sessionEpoch[pcId]
                if (epoch != null && cur != null) {
                    if (epoch == cur) {
                        // ⭐ §54（2026-07-31）：PC 侧等 Offer 已改为**同 epoch 1.5s 常驻重发（永不放弃）**。
                        //   PC 只在「没收到 Offer」时才重发——同轮次重发还在到达 = 上一份 Offer 没送达。
                        //   无条件幂等忽略会让这条会话变成吞掉全部重发的幽灵会话（永久黑屏）。
                        //   规则：会话未连通 且 已建 > STALE_OFFER_REBUILD_MS → 拆旧重发全新 Offer；
                        //   已连通会话不受影响（PC 连上后不再发同轮次 REQUEST），3s 内重发仍幂等忽略。
                        val st = existing.connectionState()
                        val connected = (st == PeerConnection.PeerConnectionState.CONNECTED)
                        val ageMs = System.currentTimeMillis() - (sessionCreatedAt[pcId] ?: 0L)
                        if (!connected && ageMs > STALE_OFFER_REBUILD_MS) {
                            Log.w(TAG, "♻️ PC $pcId 同轮次重发但会话 ${ageMs}ms 未连通(state=$st) → 拆旧重发 Offer（§54 防幽灵会话吞常驻重发）")
                            Log.d("meidui", "♻️ [P2P] 同轮次重发+未连通${ageMs}ms → 拆旧重发 Offer")
                            // 落到下方的拆旧建新路径
                        } else {
                            Log.w(TAG, "⚠️ PC $pcId 同轮次重发(epoch=$epoch) → 幂等忽略，等 Answer")
                            return
                        }
                    } else {
                        Log.w(TAG, "♻️ PC $pcId 新轮次请求(epoch $cur→$epoch) → 拆旧建新")
                        // 落到下方的拆旧建新路径
                    }
                } else {
                    val s = existing.connectionState()
                    val ageMs = System.currentTimeMillis() - (sessionCreatedAt[pcId] ?: 0L)
                    // ⭐ §53.3① / §53.16 时间窗（兜底，仅老版 PC 无 epoch 时用）：
                    //   requestId 是逐条消息时间戳，只留日志关联（§53.16 教训：别拿它判轮次）。
                    if ((s == PeerConnection.PeerConnectionState.NEW ||
                         s == PeerConnection.PeerConnectionState.CONNECTING) && ageMs < 2000) {
                        Log.w(TAG, "⚠️ PC $pcId 会话建立中(${ageMs}ms, reqId=$requestId)，忽略重复请求")
                        return
                    }
                    Log.w(TAG, "🔁 PC $pcId 旧会话状态=$s(${ageMs}ms)，拆掉重建响应新 REQUEST")
                    Log.d("meidui", "🔁 [P2P] REQUEST 触发旧会话重建: state=$s age=${ageMs}ms")
                }
            }
        }
        // 已存在旧会话（非建立中）→ 拆掉重建
        if (synchronized(viewerSessions) { viewerSessions.containsKey(pcId) }) {
            removeViewerSession(pcId, notifyPC = false)
        }
        if (requestId != null) lastRequestId[pcId] = requestId

        // ⭐ §53.20.3 P2P=单人直连，先到先得：已有**别的 PC** 的会话时，后来者直接拒绝并提示，
        //   绝不拆先来者的会话（同 pcId 的重复/重连请求已在上面的去重窗处理）。
        val occupied = synchronized(viewerSessions) { viewerSessions.keys.firstOrNull { it != pcId } }
        if (occupied != null) {
            Log.w(TAG, "🚧 单人直连已被 $occupied 占用 → 拒绝后来的 $pcId（single_mode_occupied）")
            Log.d("meidui", "🚧 [P2P] 单人直连已被 $occupied 占用 → 拒绝 $pcId")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "single_mode_occupied", pcId)
            return
        }

        if (viewerCount >= maxViewers) {
            Log.e(TAG, "❌ 已达最大观看人数($maxViewers)，拒绝 $pcId")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_REJECT", "max_viewers_reached", pcId)
            return
        }

        val videoTrack = ds.p2pLocalVideoTrack ?: run {
            Log.e(TAG, "❌ 视频轨未就绪，无法创建会话")
            return
        }

        // ⭐⭐ §53.19（用户拍板，与 iOS 一致）：P2P **只做局域网直连**——去掉 TURN 中继与 STUN 打洞。
        //   传空 iceServers → 只产生 host 候选：同 WiFi 秒连；不在同 WiFi 无 srflx/relay → ICE 失败回落。
        //   §53.21：中继/打洞代码（TURN 配置、relay 钉住、软切/硬切）已全部物理删除。
        val servers = emptyList<PeerConnection.IceServer>()
        Log.d(TAG, "🔔 P2P 局域网直连(host-only，无 TURN/STUN)")

        val cfg = PeerConnection.RTCConfiguration(servers)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        cfg.iceCandidatePoolSize = 2
        // P0-2 对齐 iOS：ICE 稳定性参数（8s 无收包才判 disconnected，弱网更耐抖）
        cfg.iceConnectionReceivingTimeout = 8000
        cfg.iceBackupCandidatePairPingInterval = 2000
        // ⭐ §53.19：无 STUN/TURN，ALL 实际只剩 host 候选（=局域网直连）
        cfg.iceTransportsType = PeerConnection.IceTransportsType.ALL

        val observer = createObserver(pcId)
        val newPC = factory.createPeerConnection(cfg, observer) ?: run {
            Log.e(TAG, "❌ 创建 PeerConnection 失败 $pcId")
            return
        }

        val sender = newPC.addTrack(videoTrack, listOf("s0"))
        synchronized(viewerSessions) {
            viewerSessions[pcId] = newPC
            viewerSenders[pcId] = sender
            sessionCreatedAt[pcId] = System.currentTimeMillis()
            // ⭐ §53.25：记住本会话的协商轮次（出站信令回带；老版 PC 无 epoch 则清掉旧值）
            if (epoch != null) sessionEpoch[pcId] = epoch else sessionEpoch.remove(pcId)
            currentViewerCount = viewerSessions.size
        }
        // 会话创建时初始化整组参数（码率 + 帧率），后续走拆分后的独立方法
        applyBitrate(sender)
        applyFramerate(sender)

        Log.d(TAG, "✅ 会话创建成功 $pcId，当前观看 $viewerCount/$maxViewers")

        val cons = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        newPC.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                // ⭐ §53.24：幽灵 Offer 抑制——Offer 创建是异步的，期间会话可能已被拆除
                //  （PC 断开 HANGUP / 新 REQUEST 拆旧建新）。过期 Offer 发出去会与新会话的
                //   Offer 交错，PC 每收一个新 ufrag 就重建一次 pipeline → 重建风暴。
                val current = synchronized(viewerSessions) { viewerSessions[pcId] }
                if (current !== newPC) {
                    Log.w(TAG, "🗑 会话已拆除，丢弃过期 Offer($pcId)")
                    return
                }
                // ⭐ H264 限定（对齐 iOS preferredCodec=H264）：Android DefaultVideoEncoderFactory
                //   的 codec 顺序是软件编码器(VP8/VP9/AV1)在前、H264 在后 → Offer 首选 VP8；
                //   PC GStreamer 是 Answerer 且解码链路写死 rtph264depay(只解 H264)，
                //   协商成 VP8 = ICE 连上但画面永远出不来（SRS 不受影响：SRS Answer 只回 H264）。
                val munged = SessionDescription(sdp.type, mungeOfferForCodec(sdp.description))
                newPC.setLocalDescription(SilentSdpObserver, munged)
                val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25 回带轮次
                WebSocketManager.instance.sendWebRTCSignalingSDP("offer", munged.description, pcId, e)
                Log.d(TAG, "📤 已发送 Offer 给 $pcId（${H265Support.codecLabel()} 限定，epoch=${e ?: "无"}）")
            }
            override fun onCreateFailure(e: String?) { Log.e(TAG, "❌ 创建 Offer 失败 $pcId: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, cons)
    }

    fun removeViewerSession(pcId: String, notifyPC: Boolean) {
        if (notifyPC) {
            val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "android_close", pcId, e)
        }
        val pc = synchronized(viewerSessions) {
            val removed = viewerSessions.remove(pcId)
            viewerSenders.remove(pcId)
            sessionCreatedAt.remove(pcId)
            sessionEpoch.remove(pcId)   // §53.25
            currentViewerCount = viewerSessions.size
            removed
        }
        try { pc?.close() } catch (_: Exception) {}
        pendingRemoteIce.remove(pcId)
        pendingIceRestart.remove(pcId)
        iceRetryCount.remove(pcId)
        lastRequestId.remove(pcId)
        Log.d(TAG, "🔌 移除会话 $pcId，剩余 $viewerCount")
    }

    fun closeAllViewerSessions(notifyPC: Boolean) {
        val sessions = synchronized(viewerSessions) {
            val copy = viewerSessions.toMap()
            viewerSessions.clear()
            viewerSenders.clear()
            sessionCreatedAt.clear()
            sessionEpoch.clear()   // §53.25
            currentViewerCount = 0
            copy
        }
        for ((pcId, pc) in sessions) {
            if (notifyPC) {
                WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "android_stop_publish", pcId)
            }
            try { pc.close() } catch (_: Exception) {}
        }
        pendingRemoteIce.clear()
        pendingIceRestart.clear()
        iceRetryCount.clear()
    }

    // MARK: - PeerConnection Observer

    private fun createObserver(pcId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25 回带轮次
            WebSocketManager.instance.sendWebRTCSignalingICE(
                candidate.sdp, candidate.sdpMid ?: "0", candidate.sdpMLineIndex, pcId, e)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "📡 [P2P] $pcId ICE状态: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    mainScope.launch { iceRetryCount.remove(pcId) }
                    Log.d(TAG, "✅ $pcId ICE 已连接")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "❌ $pcId ICE 失败，重连")
                    mainScope.launch {
                        synchronized(viewerSessions) { viewerSessions[pcId] }
                            ?.let { retryIceConnection(pcId, it) }
                    }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "⚠️ $pcId ICE 断开，15s 后检查")
                    mainScope.launch {
                        delay(15000)
                        val pc = synchronized(viewerSessions) { viewerSessions[pcId] } ?: return@launch
                        val s = pc.iceConnectionState()
                        if (s == PeerConnection.IceConnectionState.DISCONNECTED ||
                            s == PeerConnection.IceConnectionState.FAILED) {
                            retryIceConnection(pcId, pc)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    // MARK: - ICE 重连（局域网内重试；耗尽 = 确认非局域网 → 回落 SRS）

    private fun retryIceConnection(pcId: String, pc: PeerConnection) {
        val cur = iceRetryCount[pcId] ?: 0
        if (cur < MAX_ICE_RETRIES) {
            iceRetryCount[pcId] = cur + 1
            pendingIceRestart.add(pcId)
            val cons = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) return
                    // ⭐ §53.24：幽灵 Offer 抑制（与 createViewerSession 同款）
                    val current = synchronized(viewerSessions) { viewerSessions[pcId] }
                    if (current !== pc) {
                        Log.w(TAG, "🗑 会话已拆除，丢弃过期 ICE Restart Offer($pcId)")
                        return
                    }
                    // ⭐ ICE Restart 的 Offer 同样做 codec 限定（与首次 Offer 一致，防重协商时倒回 VP8）
                    val munged = SessionDescription(sdp.type, mungeOfferForCodec(sdp.description))
                    pc.setLocalDescription(SilentSdpObserver, munged)
                    val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25
                    WebSocketManager.instance.sendWebRTCSignalingSDP("offer", munged.description, pcId, e)
                    Log.d(TAG, "🔄 ICE Restart Offer 已发送 $pcId (${cur + 1}/$MAX_ICE_RETRIES)")
                }
                override fun onCreateFailure(e: String?) { Log.e(TAG, "ICE Restart Offer 失败 $pcId: $e") }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, cons)
        } else {
            // ⭐ §53.19：P2P 已是纯局域网直连（无 TURN/STUN），ICE 重试耗尽 = 确认不在局域网
            //   → 回落 SRS（原"不回退 SRS"是连接方式静态时代的口径，现在会永远黑屏）。
            Log.e(TAG, "❌ $pcId ICE 重试耗尽（无中继=确认非局域网）→ 回落 SRS")
            iceRetryCount.remove(pcId)
            val e = synchronized(viewerSessions) { sessionEpoch[pcId] }   // §53.25
            removeViewerSession(pcId, notifyPC = false)
            SessionPolicy.forceSrsForSession("P2P ICE 失败重试耗尽($pcId)，无中继=确认非局域网")
            WebSocketManager.instance.sendWebRTCSignaling("WEBRTC_HANGUP", "ice_failed", pcId, e)
        }
    }

    // ⭐ §53.21：原「链路择优 switchAllSessionsToRelay（§25.7 软切/硬切 TURN 中继）」已物理删除——
    //   P2P 无中继可切，直连质量差/路径非局域网时由 SessionPolicy 重新协商切 SRS。

    // MARK: - 编码参数（码率与帧率解耦，AllSessions 遍历 —— iOS §21.5 教训）

    /** 仅同步「码率」到所有直连会话（不改 maxFramerate）。 */
    fun applyBitrateToAllSessions() {
        forEachSender { applyBitrate(it) }
    }

    /** 仅同步「帧率」到所有直连会话（不改码率）。 */
    fun applyFramerateToAllSessions() {
        forEachSender { applyFramerate(it) }
    }

    /**
     * P2P 本地强制关键帧（作用于所有直连会话）。
     * 与 WebRTCManager.forceKeyframe 相同的码率微调 trick（libwebrtc 未暴露 generateKeyFrame），
     * +1kbps 触发编码器重配出 IDR，20ms 后原样恢复（含 null，不篡改码率配置）。
     */
    fun forceKeyframeAllSessions() {
        val senders = synchronized(viewerSessions) { viewerSenders.toMap() }
        if (senders.isEmpty()) return
        for ((pcId, sender) in senders) {
            try {
                val params = sender.parameters
                if (params.encodings.isEmpty()) continue
                val originalMax = params.encodings[0].maxBitrateBps
                val base = originalMax ?: 3_000_000
                params.encodings[0].maxBitrateBps = base + 1000
                sender.parameters = params
                mainScope.launch {
                    delay(20)
                    val s = synchronized(viewerSessions) { viewerSenders[pcId] } ?: return@launch
                    try {
                        val p2 = s.parameters
                        if (p2.encodings.isNotEmpty()) {
                            p2.encodings[0].maxBitrateBps = originalMax
                            s.parameters = p2
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "forceKeyframe($pcId) 失败: ${e.message}")
            }
        }
        Log.d(TAG, "🔑 forceKeyframe → ${senders.size} 个直连会话")
    }

    private fun forEachSender(block: (RtpSender) -> Unit) {
        val senders = synchronized(viewerSessions) { viewerSenders.values.toList() }
        for (s in senders) {
            try { block(s) } catch (e: Exception) { Log.e(TAG, "sender 参数写入失败: ${e.message}") }
        }
    }

    private fun applyBitrate(sender: RtpSender) {
        val ds = dataSource ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        val (minK, maxK) = ds.p2pBitrateRangeKbps()
        params.encodings[0].minBitrateBps = minK * 1000
        params.encodings[0].maxBitrateBps = maxK * 1000
        params.encodings[0].scaleResolutionDownBy = ds.p2pScaleDown()
        // ⭐ 必须 MAINTAIN_RESOLUTION：弱网只降帧/降码率，分辨率锁死=档位预设。
        //   （iOS 曾漏成 maintainFramerate → P2P 弱网分辨率乱串，§21.5 的坑）
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
    }

    private fun applyFramerate(sender: RtpSender) {
        val ds = dataSource ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        params.encodings[0].maxFramerate = ds.p2pTargetFps()
        sender.parameters = params
    }

    // MARK: - SDP codec 限定（对齐 iOS preferredCodec=H264，见 createViewerSession 处注释）

    /**
     * ⭐ H265：三处 Offer（首发/ICE重试/切中继）统一走这里按会话编码分派。
     *   H264 会话 → 原 forceH264InVideoSection 不动；H265 会话 → H265Support.mungeOfferH265
     *   （限定 H265，Offer 无 H265 时自动回落 H264，逻辑全在 H265Support.kt）。
     */
    private fun mungeOfferForCodec(sdp: String): String =
        if (H265Support.isH265Session()) H265Support.mungeOfferH265(sdp)
        else forceH264InVideoSection(sdp)

    /**
     * 把 m=video 段限定为 H264（含其关联 RTX）：
     * - 重写 m=video 行的 payload 列表（H264 pt 在前、RTX pt 在后，其余剔除）；
     * - 删除被剔除 pt 的 a=rtpmap / a=rtcp-fb / a=fmtp 行；
     * - 找不到 H264（个别设备无 H264 硬编）或解析异常时原样返回，绝不因 munge 弄坏协商。
     */
    internal fun forceH264InVideoSection(sdp: String): String {
        return try {
            val lines = sdp.split("\r\n").toMutableList()
            val mVideoIdx = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mVideoIdx < 0) return sdp
            var sectionEnd = lines.size
            for (i in mVideoIdx + 1 until lines.size) {
                if (lines[i].startsWith("m=")) { sectionEnd = i; break }
            }

            val rtpmapRe = Regex("^a=rtpmap:(\\d+)\\s+([^/]+)/")
            val fmtpAptRe = Regex("^a=fmtp:(\\d+)\\s+.*apt=(\\d+)")
            val h264Pts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = rtpmapRe.find(lines[i]) ?: continue
                if (m.groupValues[2].equals("H264", ignoreCase = true)) h264Pts.add(m.groupValues[1])
            }
            if (h264Pts.isEmpty()) {
                Log.w(TAG, "⚠️ Offer 无 H264（设备无 H264 编码器？），SDP 原样发出")
                return sdp
            }
            val rtxPts = LinkedHashSet<String>()
            for (i in mVideoIdx + 1 until sectionEnd) {
                val m = fmtpAptRe.find(lines[i]) ?: continue
                if (m.groupValues[2] in h264Pts) rtxPts.add(m.groupValues[1])
            }
            val keep = h264Pts + rtxPts

            // 重写 m=video 行：m=video <port> <proto> <pt...>
            val mTokens = lines[mVideoIdx].split(" ")
            if (mTokens.size <= 3) return sdp
            lines[mVideoIdx] = (mTokens.take(3) + h264Pts + rtxPts).joinToString(" ")

            // 剔除非 H264 pt 的属性行（倒序删避免索引错位）
            val ptAttrRe = Regex("^a=(rtpmap|rtcp-fb|fmtp):(\\d+)[\\s:]?")
            for (i in sectionEnd - 1 downTo mVideoIdx + 1) {
                val m = ptAttrRe.find(lines[i]) ?: continue
                if (m.groupValues[2] !in keep) lines.removeAt(i)
            }
            Log.d(TAG, "🎬 Offer 已限定 H264: pt=${h264Pts.joinToString("/")}, rtx=${rtxPts.joinToString("/")}")
            lines.joinToString("\r\n")
        } catch (e: Exception) {
            Log.e(TAG, "forceH264InVideoSection 解析失败，SDP 原样发出: ${e.message}")
            sdp
        }
    }

    private object SilentSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "SDP创建失败: $e") }
        override fun onSetFailure(e: String?) { Log.e(TAG, "SDP设置失败: $e") }
    }
}
