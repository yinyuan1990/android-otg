package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * SessionPolicy —— 「本次推流走什么链路、用什么编码」的唯一决策点（§53.4-定稿）。
 * 与 iOS `Managers/SessionPolicy.swift` **同构**（同样的输入、同样的判定、同样的防抖参数）。
 *
 * 设计口径（用户 2026-07-28 拍板）：
 *  1. **推流前定案**：登录成功后设备与 PC 已能互相通信（`/topic/device/{id}/config` 双方都订阅），
 *     所以在开始推流之前就能把「网络关系」和「观看端能力」交换清楚，一次定下 mode + codec。
 *     **推流中不再切换**（旧方案"先起 P2P、发现跨网再回落/退登录页"作废）。
 *  2. **能 P2P 只限同一 WiFi**，否则一律 SRS —— 跨网时 P2P 只能走 TURN 中继，物理路径与 SRS
 *     完全相同却拿不到服务端重传/GOP cache/一对多分发（§52.5），是最差的一档组合。
 *  3. **编码由总后台配置**（默认 H265）；**不支持就回退 H264**：观看端内核收不了 H265
 *     （网页内核=Chromium 134，§49.6-10）或本机没有 H265 硬编 → 降 H264。
 *  4. 登录页只保留「自带 / 外接OTG」，不再让用户选线路与编码。
 *  5. 一方断线 / 切网 = 决策输入变了 → 重新协商：停推流 → 重新决策 → 起推流（带冷却与次数上限）。
 *
 * 删掉本文件 + 还原 WebRTCManager 里的调用点即可回退到"登录页手选"的老行为。
 */
object SessionPolicy {

    private const val TAG = "SessionPolicy"

    /** 本次会话的链路 */
    enum class Mode(val connectstype: Int) { P2P(1), SRS(0) }

    /** 一次决策的完整结果。equals 只比 mode+codec —— reason 变化不触发重新协商 */
    data class Decision(val mode: Mode, val codec: String, val reason: String) {
        override fun equals(other: Any?): Boolean {
            if (other !is Decision) return false
            return mode == other.mode && codec == other.codec
        }
        override fun hashCode(): Int = mode.hashCode() * 31 + codec.hashCode()
    }

    // ---------- 可调参数（与 iOS 保持一致） ----------

    /** ⭐⭐ 2026-08-02 用户拍板：当前版本三端一律直接走 SRS，P2P 以后单独出专版、不再混用。
     *  true = compute() 协商直接返回 SRS（P2P 判定/重协商代码保留不删）；P2P 专版改回 false 即恢复。
     *  与 iOS `SessionPolicy.srsOnlyBuild` 同构。 */
    private const val SRS_ONLY_BUILD = true

    /** 推流前等 PC_PRESENCE 的宽限期（ms）：两端登录有先后，刚开机时消息可能还没到 */
    const val PRESENCE_GRACE_MS = 2000L
    /** 两次重新协商的最小间隔（ms） */
    private const val RENEGOTIATE_COOLDOWN_MS = 5000L
    /** 单次推流会话内最多重新协商几次；超了钉死 SRS（对所有网络都成立） */
    private const val MAX_RENEGOTIATE_PER_SESSION = 3

    // ---------- 状态 ----------

    @Volatile private var current: Decision? = null
    @Volatile private var lastRenegotiateAtMs = 0L
    @Volatile private var renegotiateCount = 0
    @Volatile private var pinnedToSrs = false
    @Volatile private var graceConsumed = false
    /** ⭐ §53.20.1：标记「下一次 decideForPublish 是重协商触发的重启」。没有它，重协商
     *  = 停推流→startPublish→decideForPublish 把钉住/计数全重置 —— 「钉住 SRS」活不过
     *  一次重启，P2P↔SRS 每 5~10s 拆建一轮无限打架（客户实测=画面周期性卡顿）。 */
    @Volatile private var renegotiationInFlight = false

    /** 输入变化且新结果与已定案不同 → 回调上层做「停推流 → 重新决策 → 起推流」 */
    @Volatile var onRenegotiateNeeded: ((String) -> Unit)? = null

    /** 本次会话定案的原因（人话），随 CONFIG_STATE.connectReason 上报给 PC 顶栏显示 */
    @Volatile var connectReason: String = ""
        private set

    val currentDecision: Decision? get() = current

    // ---------- 喂输入 ----------

    /**
     * 观看端状态有变化时由 WebSocketManager 调用（只在"可能改变决策"的字段变了时才调，
     * 不是每条心跳都调）。内部评估是否需要重新协商。
     */
    fun onViewerInputChanged(trigger: String, isNewViewer: Boolean = false) {
        val pending = pendingNetworkChange
        if (pending) pendingNetworkChange = false
        // ⭐ §53.20.3 单人模式先到先得：P2P 会话进行中，**其它观看端的任何 presence 变化都不触发
        //   重新协商**——不只是"新上线"（与 iOS 同构）。此前只挡 isNewViewer，导致第二台 PC 的
        //   后续心跳字段一变/跨网就把 compute() 拖去"非同网段→SRS"，把正在 P2P 的第一台踢下来，
        //   第二台被拒又切回 P2P → P2P↔SRS 来回翻（用户实测"混乱"）。单人模式只认先来的那台；
        //   其它 PC 由 P2P 层回 single_mode_occupied 提示占线；本机切网(pending)例外照常评估；
        //   真正的 P2P 对端变差由 ICE 失败 → forceSrsForSession 兜底。
        if (!pending && current?.mode == Mode.P2P) {
            log("🚧 单人直连进行中，观看端($trigger)presence 变化不触发重协商（单人模式；其它 PC 由 P2P 层拒绝占线）")
            return
        }
        val handled = evaluateForRenegotiate(if (pending) "$trigger + 本机切过网" else trigger)
        // ⭐⭐ 2026-08-01 修「切网后卡死在 P2P、切不到 SRS」（与 iOS 同构）：切网标记是唯一的评估
        //   触发源（PC 没动，其心跳字段永远不变）。评估被 5s 冷却挡下（快速来回切网必撞）时标记
        //   已被消费——不还回去就再也没有任何东西触发重评估，跨网了还钉在 P2P 上黑屏。
        //   还回去后 PC 心跳 1s 一条，冷却一过自动重试。
        if (pending && !handled) {
            pendingNetworkChange = true
        }
    }

    /**
     * 设备自己切网（WiFi↔蜂窝/换 WiFi）。
     *
     * ⚠️ §53.12：**只打标记，不在这里评估**。切网瞬间 WS 多半已断、PC 的 presence 也停了，
     * 这时候算出来的"网段关系"是拿旧的/空的观看端网段去比，最不可靠；更要紧的是切网事件
     * 同时会触发原有的 `publishHealthCheck` 自愈，两条恢复路径在同一事件里抢着重启推流，
     * 顺序还不确定 —— 实测表现就是「Android 切换网络后不出画面」。
     * 等 PC 的 PC_PRESENCE 重新到达（网络已稳、网段是新的）时，由 onViewerInputChanged 一并评估。
     */
    fun onLocalNetworkChanged() {
        pendingNetworkChange = true
        log("📶 本机切网 → 标记待重新决策（等观看端心跳恢复后再评估，避免与切网自愈打架）")
    }

    @Volatile private var pendingNetworkChange = false

    /**
     * 兜底：推流前预判同 WiFi，但实测 ICE 路径不是局域网（AP 隔离/多网卡/NAT 掩盖网段）。
     * 直接把本次会话钉在 SRS 并重新协商——比让用户自己去登录页改线路正确（§52.6 已废弃）。
     */
    fun forceSrsForSession(reason: String) {
        val decided = current ?: return
        if (decided.mode != Mode.P2P) return
        val since = System.currentTimeMillis() - lastRenegotiateAtMs
        if (since < RENEGOTIATE_COOLDOWN_MS) {
            log("⏳ 实测非局域网($reason)，但距上次协商仅 ${since}ms，等冷却")
            return
        }
        lastRenegotiateAtMs = System.currentTimeMillis()
        pinnedToSrs = true
        current = Decision(Mode.SRS, decided.codec, "实测非局域网，改走多人线路")
        connectReason = current!!.reason
        log("🔧 $reason → 本次会话钉住多人线路(SRS)，执行重新协商")
        renegotiationInFlight = true   // §53.20.1：重启后的 decideForPublish 保留钉住状态
        onRenegotiateNeeded?.invoke(reason)
    }

    /** 停止推流：只清"本次会话定案"，**保留观看端注册表**（PC 还在线，下次推流要用它决策） */
    fun onPublishStopped() {
        current = null
        graceConsumed = false
    }

    /** 退登录 / 切设备：全清 */
    fun reset() {
        current = null
        renegotiateCount = 0
        pinnedToSrs = false
        renegotiationInFlight = false
        lastRenegotiateAtMs = 0
        graceConsumed = false
        pendingNetworkChange = false
        connectReason = ""
        WebSocketManager.instance.clearPcPresence()
    }

    /** ⭐ §53.20.1：上层收到重协商回调但未在推流（忽略执行）时清标记，
     *  否则残留标记会让下一次**用户手动**推流误当成"重协商重启"而保留过期的钉住状态。 */
    fun abortRenegotiation() {
        renegotiationInFlight = false
    }

    /**
     * 一次性宽限：推流那一刻还没收到任何 PC_PRESENCE 时返回 true，
     * 调用方等 PRESENCE_GRACE_MS 再重试一次决策（两端登录有先后，消息可能刚好没到）。
     * 只放行一次，等不到就按 SRS 走，绝不无限等。
     */
    fun shouldWaitForPresence(): Boolean {
        if (graceConsumed) return false
        graceConsumed = true
        return WebSocketManager.instance.onlinePcCount() == 0
    }

    // ---------- 决策 ----------

    /** 按当前输入定案本次会话的 mode + codec（startPublish 调，唯一入口） */
    fun decideForPublish(context: Context?): Decision {
        // ⭐ §53.20.1：重协商触发的重启必须**继承**钉住状态与协商计数——否则
        //   forceSrsForSession 钉住 SRS → 停推流重启 → 这里清零 → 又算回 P2P → 又失败，
        //   P2P↔SRS 无限拆建（客户实测=画面周期性卡顿）。只有全新会话才清零。
        if (renegotiationInFlight) {
            renegotiationInFlight = false
        } else {
            renegotiateCount = 0
            pinnedToSrs = false
        }
        val d = compute(context)
        current = d
        connectReason = d.reason
        log("✅ 推流前定案：${d.mode}+${d.codec.uppercase()} —— ${d.reason}")
        return d
    }

    /** 纯计算，不改状态 */
    private fun compute(context: Context?): Decision {
        val reasons = ArrayList<String>()
        val ws = WebSocketManager.instance
        val viewers = ws.pcPresenceSnapshot()
        val myIps = localIpv4Addresses()

        // ① 链路：**只有所有在线观看端都与本机同网段（同 WiFi）才走 P2P**，否则 SRS。
        val backendForcesSrs = context?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            ?.getString("connect_mode", "auto")?.lowercase() == "srs"
        val mode: Mode
        if (SRS_ONLY_BUILD) {
            // ⭐⭐ 2026-08-02 用户拍板：当前版本一律直接走 SRS，P2P 另出专版、不再混用。
            mode = Mode.SRS
            reasons.add("当前版本固定多人线路（P2P另出专版）")
        } else if (pinnedToSrs) {
            // ⭐ §53.20.1：本次会话已被实测否掉 P2P（ICE 失败/协商次数达上限），
            //   重协商重启后必须还记得——不能拿网段预判再算回 P2P。
            mode = Mode.SRS
            reasons.add("本次会话已钉住多人线路")
        } else if (backendForcesSrs) {
            mode = Mode.SRS
            reasons.add("后端强制多人线路")
        } else if (viewers.isEmpty()) {
            mode = Mode.SRS
            reasons.add("暂无观看端在线，默认多人线路")
        } else {
            val anyMissingIps = viewers.values.any { it.localIps.isEmpty() }
            val allSameSubnet = viewers.values.all { sharesSubnet(myIps, it.localIps) }
            // ⭐ §53.20.2：/24 网段判定有假阳性——192.168.1.x 是全世界路由器的默认网段，
            //   设备在 A 地、PC 在 B 地完全可能撞车 → 误判同 WiFi → P2P 白失败几十秒才回落。
            //   公网出口 IP 双重校验：同一 WiFi 下两端出口必然相同（同一路由器出网）。
            //   任一侧为空（老 PC/老后端没下发 clientIp）→ 跳过该校验，退回纯网段判定。
            val myPublicIp = context?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                ?.getString("public_ip", "") ?: ""
            val publicIpMismatch = myPublicIp.isNotEmpty() && viewers.values.any {
                it.publicIp.isNotEmpty() && it.publicIp != myPublicIp
            }
            if (allSameSubnet && !anyMissingIps && !publicIpMismatch) {
                mode = Mode.P2P
                reasons.add("与观看端同 WiFi，走单人直连")
            } else {
                mode = Mode.SRS
                reasons.add(when {
                    publicIpMismatch -> "公网出口不同(非同一WiFi，网段号撞车)，走多人线路"
                    anyMissingIps -> "观看端未上报网段(旧版PC)，走多人线路"
                    else -> "与观看端不在同一 WiFi，走多人线路"
                })
            }
        }

        // ② 编码：服务器默认（总后台可配，默认 h265），服从"最弱观看端"与本机硬编能力
        var codec = serverDefaultCodec(context, mode)
        if (codec == "h265") {
            if (viewers.values.any { !it.h265Recv }) {
                codec = "h264"
                reasons.add("有观看端内核收不了 H265，已降 H264")
            } else if (!H265Support.sdkSupportsH265) {
                codec = "h264"
                reasons.add("本机无 H265 硬编，已降 H264")
            }
        }

        return Decision(mode, codec, reasons.joinToString("；"))
    }

    /** 服务器下发的默认编码（登录时写进 token_prefs；读不到按 h265 = 产品默认） */
    private fun serverDefaultCodec(context: Context?, mode: Mode): String {
        val key = if (mode == Mode.P2P) H265Support.PREFS_RUNTIME_KEY else H265Support.PREFS_RUNTIME_KEY_SRS
        return context?.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            ?.getString(key, "h265")?.lowercase() ?: "h265"
    }

    // ---------- 重新协商 ----------

    private var appContext: Context? = null
    fun attachContext(context: Context) { appContext = context.applicationContext }

    /**
     * ⭐ 2026-08-01 返回值：true=已处理完毕（协商已发起/结果不变/无需处理），
     *   false=**被冷却挡下、需要稍后重试**——调用方（onViewerInputChanged 的切网标记路径）据此
     *   把 pendingNetworkChange 还回去，否则切网评估机会被冷却吞掉后永远不会再触发（卡死在旧链路）。
     */
    private fun evaluateForRenegotiate(trigger: String): Boolean {
        val decided = current ?: return true      // 还没推流，decideForPublish 会用最新输入重算
        if (pinnedToSrs) return true              // 本次会话已钉死，无需再评估

        val fresh = compute(appContext)
        if (fresh == decided) {
            log("输入变化($trigger)但决策结果不变（${decided.mode}+${decided.codec}），不重启推流")
            return true
        }
        val since = System.currentTimeMillis() - lastRenegotiateAtMs
        if (since < RENEGOTIATE_COOLDOWN_MS) {
            log("⏳ 需要重新协商($trigger)但距上次仅 ${since}ms，等冷却后重试")
            return false   // ⭐ 冷却挡下 ≠ 处理完，调用方须保留切网标记重试
        }
        renegotiateCount++
        if (renegotiateCount > MAX_RENEGOTIATE_PER_SESSION) {
            pinnedToSrs = true
            current = Decision(Mode.SRS, decided.codec, "协商次数达上限，固定多人线路")
            connectReason = current!!.reason
            lastRenegotiateAtMs = System.currentTimeMillis()
            log("⚠️ 本次会话已重新协商 $MAX_RENEGOTIATE_PER_SESSION 次，钉死多人线路(SRS)不再切换（防抖）")
            renegotiationInFlight = true   // §53.20.1：重启后的 decideForPublish 保留钉住/计数
            onRenegotiateNeeded?.invoke("协商次数达上限→固定SRS")
            return true
        }
        lastRenegotiateAtMs = System.currentTimeMillis()
        log("🔄 重新协商($trigger)：${decided.mode}+${decided.codec} → ${fresh.mode}+${fresh.codec}（停推流→重决策→起推流）")
        renegotiationInFlight = true       // §53.20.1
        onRenegotiateNeeded?.invoke(trigger)
        return true
    }

    // ---------- 网段工具（与 P2PManager §25.7e 同一套算法，避免两份判定打架） ----------

    /** 本机全部 IPv4（WiFi / 热点 / USB 网络等，排除回环与链路本地 169.254.*） */
    fun localIpv4Addresses(): List<String> {
        val results = ArrayList<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { results.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "枚举本机 IPv4 失败: ${e.message}")
        }
        return results
    }

    /** 同网段判定（/24）：双方任意一对 IPv4 前三段相同 = 同一局域网（同 WiFi） */
    fun sharesSubnet(myIps: List<String>, peerIps: List<String>): Boolean {
        fun prefix24(ip: String): String? {
            val parts = ip.split(".")
            return if (parts.size == 4) parts.subList(0, 3).joinToString(".") else null
        }
        val mine = myIps.mapNotNull { prefix24(it) }.toSet()
        return peerIps.any { prefix24(it) in mine }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        Log.d("meidui", "🧭 [链路决策] $msg")
    }
}
