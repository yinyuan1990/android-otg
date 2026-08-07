package com.fz.yqlandroid.ui.screen

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fz.yqlandroid.manager.WebRTCManager
import com.fz.yqlandroid.manager.WebSocketManager
import com.fz.yqlandroid.navigation.AppViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * 推流页面
 * 与iOS ContentView 保持一致
 * 
 * UI面板（曝光/焦距/亮度/清晰度）仅做数据同步显示，不主动操作
 * 所有参数由后端STOMP下发，iOS端只负责显示
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StreamingScreen(
    appViewModel: AppViewModel,
    onLogout: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    // §56.11 协程作用域（未读回复弹框「已读」按钮里调网络用）
    val scope = rememberCoroutineScope()
    
    // 权限（⚠️ 只放相机/麦克风——预览用 allPermissionsGranted 做门槛，
    //   通知权限绝不能混进来：用户拒绝通知会连预览一起挡掉「预览画面出不来」）
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )
    // 通知权限独立申请（Android 13+，仅影响前台服务保活通知是否显示，不挡预览/推流）
    val notifPermissionState = if (android.os.Build.VERSION.SDK_INT >= 33) {
        com.google.accompanist.permissions.rememberPermissionState(
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else null
    LaunchedEffect(Unit) {
        if (notifPermissionState != null && !notifPermissionState.status.let {
                it is com.google.accompanist.permissions.PermissionStatus.Granted
            }) {
            notifPermissionState.launchPermissionRequest()
        }
    }
    
    // WebRTC管理器
    val webRTCManager = remember { WebRTCManager(context) }

    // 🔊 后台保活（对标 iOS BackgroundAudioManager）：无声音频 + WakeLock，防止息屏后推流被冻结
    val keepAliveManager = remember { com.fz.yqlandroid.manager.KeepAliveManager(context) }

    // 🖥️ 前台常亮（对标 iOS isIdleTimerDisabled）：进入推流页时禁止自动息屏，离开时恢复
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepAliveManager.stop()
        }
    }

    // 🔄 回前台自动恢复采集：前台服务是主保障，这里是双保险——
    //    部分 OEM 电池优化仍可能在后台断相机，ON_RESUME 时若推流中但采集已死则自动重启相机会话
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                webRTCManager.recoverCaptureIfNeeded("onResume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // UI状态
    var showControls by remember { mutableStateOf(true) }
    var isStreaming by remember { mutableStateOf(false) }
    // ⭐ 需求#1（2026-07-31）：试用到期弹框（对齐 iOS 的 showTrialEndAlert）。
    //   以前 Android 收到 TryDisconnect 只默默停推流，用户完全不知道为什么画面没了。
    var showTrialEndDialog by remember { mutableStateOf(false) }
    var trialEndMessage by remember { mutableStateOf("") }
    // ⭐ 需求#13（2026-07-31）：版本更新软提示（登录响应最新版本 ≠ 本地 versionName → 推流页进入时弹一次）
    var showUpdatePrompt by remember { mutableStateOf(false) }
    var updatePromptText by remember { mutableStateOf("") }
    // ⭐ §56.11（2026-08-06）：留言未读回复弹框（登录后进推流页拉一次；点「已读」后端置已读，之后不再弹）
    var showUnreadRepliesDialog by remember { mutableStateOf(false) }
    var unreadReplies by remember { mutableStateOf<List<com.fz.yqlandroid.network.UnreadReplyItem>>(emptyList()) }
    // ⭐ 需求#3（2026-07-31）：小米后台推流引导（自启动+省电策略无 API，只能引导用户手动开）
    var showXiaomiGuide by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var currentFps by remember { mutableIntStateOf(0) }
    var currentKbps by remember { mutableIntStateOf(0) }
    var currentCapFps by remember { mutableIntStateOf(0) }  // ⭐ 相机实际采集帧率（左上角显示）
    var pcConnected by remember { mutableStateOf(false) }   // ⭐ PC 观看端是否在看（有画面）
    var pcOnlineCount by remember { mutableIntStateOf(0) }  // ⭐ §53.2 在线 PC 台数（与有没有画面无关）
    var reconnecting by remember { mutableStateOf(false) }  // ⭐ 切网重连中（左上角显示"网络切换重连中…"）
    
    // 右侧面板 - 显示同步数据（后端下发 + WebRTC实际值）
    var zoomValue by remember { mutableFloatStateOf(1.0f) }
    
    // 底部面板 - 仅显示同步数据（后端下发，不操作）
    var exposureValue by remember { mutableFloatStateOf(240f) }   // 曝光 60~600
    var focusValue by remember { mutableFloatStateOf(0.5f) }      // 焦距 0~1
    var fluencyValue by remember { mutableFloatStateOf(100f) }    // 流畅 0~100
    var isoGainValue by remember { mutableFloatStateOf(0f) }      // ISO增益 0~100（滤镜移除后，亮度改显 ISO 增益）
    var selectedProfile by remember { mutableStateOf("高清") }     // 清晰度档位

    // ⭐ §53.9 会员状态（档位标绿用）。登录/服务器推送时写进 token_prefs，这里读一次即可
    //   （改等级要重登或收到推送后进出本页刷新，与「我的」页同口径）。
    val memberActivated = remember {
        context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            .getBoolean("activated", false)
    }
    val memberLevel = remember {
        context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            .getInt("activation_level", 0)
    }
    
    // WebSocket配置同步回调
    DisposableEffect(webRTCManager) {
        webRTCManager.onConnectionStateChanged = { status ->
            connectionStatus = status
        }
        webRTCManager.onStatsUpdate = { kbps, fps, rtt ->
            currentKbps = kbps
            currentFps = fps
        }
        // ⭐ 采集帧率每秒回调（不依赖观看端连接，P2P 等待期也有值）
        webRTCManager.onCapFpsUpdate = { capFps ->
            currentCapFps = capFps
        }
        // ⭐ PC 观看端连接状态（P2P=ICE 已连会话；SRS=VIEWER_HEARTBEAT 6s 内有心跳）
        webRTCManager.onPcConnectedUpdate = { connected ->
            pcConnected = connected
        }
        // ⭐ §53.2 在线 PC 台数（PC_PRESENCE 心跳，与有没有画面无关）
        webRTCManager.onPcOnlineUpdate = { count ->
            pcOnlineCount = count
        }
        // ⭐ 切网重连中：拆会话+HANGUP 后等 PC 重连，PC 心跳恢复后回调 false
        webRTCManager.onReconnectingUpdate = { r ->
            reconnecting = r
        }
        // ⭐ §53.4-定稿：§52.6 的「非同 WiFi → 退回登录页让用户改线路」已废弃。
        //   线路现在由系统在推流前按网络关系自动决定（SessionPolicy），跨网直接走多人线路，
        //   用户什么都不用做。这个回调已无人触发，保留空实现仅为回滚方便。
        webRTCManager.onNotSameWifi = {
            android.util.Log.d("meidui", "ℹ️ [线路] 收到已废弃的 onNotSameWifi（§53.4 改为自动重新协商），忽略")
        }
        
        // 🔥 监听后端配置下发 → 同步UI数据 + 实际执行控制
        WebSocketManager.instance.onConfigUpdate = { config ->
            val ptype = config["ptype"] as? String ?: ""
            
            // 1️⃣ 同步UI数据（显示用）
            when (ptype) {
                "zoom" -> (config["zoom"] as? Number)?.let { zoomValue = it.toFloat() }
                "cjfps" -> (config["cjfps"] as? Number)?.let { exposureValue = it.toFloat() }
                "focus" -> (config["focus"] as? Number)?.let { focusValue = it.toFloat() }
                // 滤镜已移除：仅 ISO 增益（PC 硬件链路 test_brightness，0~100）仍在设备端显示
                "test_brightness" -> ((config["value"] as? Number) ?: (config["testBrightness"] as? Number))?.let { isoGainValue = it.toFloat() }
                "type" -> {
                    val type = config["type"] as? String ?: ""
                    selectedProfile = when (type.lowercase()) {
                        "p4k", "4k" -> "超高清"
                        "ultra" -> "超高帧"
                        "high" -> "超清"
                        "standard" -> "高清"
                        "low" -> "超低网"
                        else -> selectedProfile
                    }
                }
                "bitrate" -> (config["bitrate"] as? Number)?.let { fluencyValue = it.toFloat() }
                "fps" -> (config["fps"] as? Number)?.let { /* FPS不需要UI同步 */ }
                "direction" -> { /* 摄像头切换不需要UI同步 */ }
            }
            
            // 2️⃣ 实际执行控制（调用WebRTC）
            webRTCManager.applyRemoteConfig(config)
        }
        
        // 🔥 监听特殊消息（RESET_PUBLISH/睡眠/唤醒/试用断开）
        WebSocketManager.instance.onSpecialMessage = { type, messageDict ->
            webRTCManager.handleSpecialMessage(type, messageDict)
            when (type) {
                "shuimian" -> {
                    isStreaming = false
                    keepAliveManager.stop()   // 🔊 睡眠：停止保活
                }
                "gongzuo" -> {
                    isStreaming = true
                    keepAliveManager.start()  // 🔊 唤醒工作：恢复保活
                }
                "TryDisconnect" -> {
                    val needDisconnect = messageDict["needDisconnect"] as? Boolean ?: false
                    if (needDisconnect) {
                        // 强制退出推流（试用到期被踢：禁止 WS 重连健康检查自动拉起推流）
                        webRTCManager.autoRecoverEnabled = false
                        webRTCManager.stopPublish()
                        keepAliveManager.stop()  // 🔊 断开推流：停止保活
                        isStreaming = false
                        // ⭐ 需求#1：弹框告知原因（对齐 iOS）。断开 WS 防 TryDisconnect 每秒重复到达；
                        //   showTrialEndDialog 本身即防重（已弹就不再动）。
                        if (!showTrialEndDialog) {
                            trialEndMessage = (messageDict["disconnectMessage"] as? String).orEmpty()
                            showTrialEndDialog = true
                        }
                        WebSocketManager.instance.disconnect()
                    }
                }
            }
        }
        
        // 🔥 监听 PC端 cmd=set_fps 指令（与iOS applyRemoteFps一致）
        // ⭐ 该通道 fps 已是推送口径（PC FPS_LEVELS={15,30,45,60}），不÷4；
        //    此前误走 setTargetFps（÷4 通道，专用于 CONFIG_UPDATE 的 fps 字段=0~240）会把 30 除成 7fps
        WebSocketManager.instance.onSetFpsCommand = { fps, urgency, bitrate, reason ->
            Log.d("StreamingScreen", "🎯 [set_fps] fps=$fps, urgency=$urgency")
            webRTCManager.applyRemotePushFps(fps, urgency)
            // 发送确认
            WebSocketManager.instance.sendSetFpsAck(fps)
        }
        
        onDispose {
            webRTCManager.destroy()
            WebSocketManager.instance.disconnect()
        }
    }
    
    // ⭐ 需求#13：推流前版本检查（软提示，进入推流页时弹一次；后台未配置=空串则跳过）
    LaunchedEffect(Unit) {
        try {
            val latest = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                .getString("latest_android_version", "") ?: ""
            val local = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            if (latest.isNotEmpty() && local.isNotEmpty() && latest != local) {
                updatePromptText = "发现新版本 v$latest（当前 v$local），请更新后使用"
                showUpdatePrompt = true
            }
        } catch (_: Exception) { /* 版本读取失败不拦截使用 */ }
    }

    // ⭐ §56.11：登录后拉未读留言回复（有则弹框，点「已读」后不再弹）
    LaunchedEffect(Unit) {
        try {
            val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            val userId = tokenPrefs.getInt("user_id", 0)
            val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
            if (userId > 0 && jwtToken.isNotEmpty()) {
                com.fz.yqlandroid.network.NetworkService.getUnreadReplies(userId, jwtToken).onSuccess { replies ->
                    if (replies.isNotEmpty()) {
                        unreadReplies = replies
                        showUnreadRepliesDialog = true
                    }
                }
            }
        } catch (_: Exception) { /* 拉取失败不拦截使用 */ }
    }

    // ⭐ 需求#3：小米切后台断推流的权限处理（进推流页时做，一次性）：
    //   ① 不在电池优化白名单 → 弹系统授权框（所有品牌通用，MIUI 上等价于省电策略→无限制的关键一半）；
    //   ② 小米/红米机型 → 首次再弹一次「自启动」引导（该开关无 API，只能带用户去 MIUI 设置页手动开）。
    LaunchedEffect(Unit) {
        try {
            if (!com.fz.yqlandroid.manager.OemPermissionHelper.isIgnoringBatteryOptimizations(context)) {
                com.fz.yqlandroid.manager.OemPermissionHelper.requestIgnoreBatteryOptimizations(context)
            }
            val prefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            if (com.fz.yqlandroid.manager.OemPermissionHelper.isXiaomi &&
                !prefs.getBoolean("xiaomi_bg_guide_shown", false)) {
                showXiaomiGuide = true
            }
        } catch (_: Exception) { /* 权限引导失败不拦截推流 */ }
    }

    // 🔥 初始化WebRTC（必须在预览之前完成）
    LaunchedEffect(Unit) {
        webRTCManager.setContext(context)
        webRTCManager.initialize()
        
        // 🔥 加载设备初始配置（与iOS一致）
        val config = com.fz.yqlandroid.manager.ConfigManager.currentConfig
            ?: com.fz.yqlandroid.manager.ConfigManager.loadCachedConfig(context)
            ?: com.fz.yqlandroid.manager.ConfigManager.getDefaultConfig()
        
        // 同步初始UI数据
        zoomValue = config.zoom
        config.cjfps?.let { exposureValue = it.toFloat() }
        config.focus?.let { focusValue = it }
        config.bitrate?.let { fluencyValue = it.toFloat() }
        selectedProfile = when (config.type.lowercase()) {
            "p4k", "4k" -> "超高清"
            "ultra" -> "超高帧"
            "high" -> "超清"
            "low" -> "超低网"    // ⭐ 对齐 iOS 5 档：初始映射此前缺 low，登录后档位高亮错到「高清」
            else -> "高清"
        }
        
        // 🔥 应用全部初始配置到WebRTC（对标 iOS applyThinRemoteConfigInit）：
        //    档位/方向/变焦/快门(cjfps)/对焦/亮度/码率/FPS 一次性挂上。
        //    此前只应用了 type+direction，导致 cjfps 等“滤镜/图像参数”启动时没生效。
        //    延迟到预览就绪后应用，确保 controlCapturer 已创建（采集器对下发状态另有缓存+重放兜底）。
        kotlinx.coroutines.delay(800)
        webRTCManager.applyInitialConfig(config)

        Log.d("StreamingScreen", "📋 初始配置已加载并全量应用: type=${config.type}, direction=${config.direction}, zoom=${config.zoom}, fps=${config.fps}, cjfps=${config.cjfps}, focus=${config.focus}, bitrate=${config.bitrate}")
    }
    
    // 自动推流
    LaunchedEffect(Unit) {
        
        // 连接WebSocket
        val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
        val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
        val deviceId = tokenPrefs.getString("device_id", "") ?: ""
        val streamPushIp = tokenPrefs.getString("stream_push_ip", "") ?: ""
        val permanentToken = tokenPrefs.getString("permanent_token", "") ?: ""
        
        if (jwtToken.isNotEmpty() && deviceId.isNotEmpty()) {
            WebSocketManager.instance.connect(deviceId, jwtToken, context)
        }
        
        // 自动推流（与iOS一致：相机就绪后自动开始推流）
        println("jfh [Streaming] 推流参数: streamPushIp='$streamPushIp', permanentToken='${permanentToken.take(10)}...', jwtToken='${jwtToken.take(10)}...'")
        
        if (streamPushIp.isNotEmpty() && permanentToken.isNotEmpty()) {
            // 等权限 + 预览就绪后自动推
            println("jfh [Streaming] ⏳ 等待2秒后自动推流...")
            kotlinx.coroutines.delay(2000)
            if (!isStreaming) {
                println("jfh [Streaming] 🚀 开始自动推流: IP=$streamPushIp, key=$permanentToken")
                webRTCManager.startPublish(streamPushIp, "tenantA", permanentToken)  // 🔥 app与iOS一致
                keepAliveManager.start()  // 🔊 推流即启动后台保活，防止息屏后被冻结
                isStreaming = true
            } else {
                println("jfh [Streaming] ⏭️ 已在推流中，跳过")
            }
        } else {
            println("jfh [Streaming] ❌ 推流参数缺失! streamPushIp='$streamPushIp', permanentToken='$permanentToken'")
        }
    }
    
    // 权限检查
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    // ========== 主界面 ==========
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // 相机预览
        if (permissionsState.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { renderer ->
                        try {
                            webRTCManager.setupRenderer(renderer)
                            webRTCManager.startPreview()
                        } catch (e: Exception) {
                            Log.e("StreamingScreen", "预览启动失败: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 无权限提示
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("需要相机和麦克风权限", color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                        Text("授权")
                    }
                }
            }
        }

        // ⭐ 第四十八章：OTG 模式能力叠显（软/硬件可调参数+上下限，供 PC 调节面板改造对照）。
        //   OTG 占用 USB 口无法连 adb，直接叠到画面层最直观；同内容也打进 meidui 日志 → 后端「OTG日志」。
        //   仅 OTG 开流后有内容（UvcCapabilityStore），自带摄像头模式恒为空、不显示。
        val otgCapLines by com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.lines.collectAsState()
        if (otgCapLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 90.dp, start = 8.dp, end = 8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                otgCapLines.forEach { line ->
                    Text(
                        text = line,
                        color = if (line.contains("✗")) Color(0xFFFFB74D) else Color(0xFF9CFF9C),
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }

        // ⭐ §56.17：OTG MJPEG 协商失败不再静默回退 YUYV —— 直接弹框报错，
        //   错误详情带 PROBE 谈判诊断出的设备真实带宽要价（为什么 -51 一目了然）。
        val otgFatalError by com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.fatalError.collectAsState()
        if (otgFatalError != null) {
            AlertDialog(
                onDismissRequest = { com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.clearError() },
                title = { Text("外接摄像头协商失败") },
                text = {
                    Text(
                        text = otgFatalError ?: "",
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = { com.fz.yqlandroid.manager.uvc.UvcCapabilityStore.clearError() }) {
                        Text("知道了")
                    }
                }
            )
        }
        
        // ===== 顶部导航栏（与iOS一致） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Column {
                // 状态栏占位
                Spacer(modifier = Modifier.statusBarsPadding())
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0xFFFAFAFA))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左 - 关闭按钮
                    IconButton(
                        onClick = {
                            if (isStreaming) webRTCManager.stopPublish()
                            keepAliveManager.stop()  // 🔊 退出推流页：停止保活
                            WebSocketManager.instance.disconnect()
                            onLogout()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color(0xFF1A1A1A),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // 中 - 标题
                    Text(
                        text = "监控端",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // 右 - 我的
                    IconButton(
                        onClick = onNavigateToProfile,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "我的",
                            tint = Color(0xFF1A1A1A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        
        // ⭐ 需求#3：小米后台推流引导（首次进入弹一次；"去设置"跳 MIUI 自启动页，"已设置"永久不再弹）
        if (showXiaomiGuide) {
            AlertDialog(
                onDismissRequest = { /* 必须二选一，防误触消失 */ },
                title = { Text("小米手机后台推流设置") },
                text = {
                    Text("检测到小米/红米手机。切到后台后若推流中断，需要开启：\n\n" +
                         "1. 自启动（应用管理 → 本应用 → 自启动）\n" +
                         "2. 省电策略 → 无限制\n\n" +
                         "设置一次永久生效。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        com.fz.yqlandroid.manager.OemPermissionHelper.openAutoStartSettings(context)
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showXiaomiGuide = false
                        context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("xiaomi_bg_guide_shown", true).apply()
                    }) { Text("已设置，不再提示") }
                }
            )
        }

        // ⭐ 需求#13：版本更新软提示（不拦截推流，知道了即关）
        if (showUpdatePrompt) {
            AlertDialog(
                onDismissRequest = { showUpdatePrompt = false },
                title = { Text("发现新版本") },
                text = { Text(updatePromptText) },
                confirmButton = {
                    TextButton(onClick = { showUpdatePrompt = false }) { Text("知道了") }
                }
            )
        }

        // ⭐ §56.11：留言未读回复弹框（点「已读」→ 后端置已读，之后登录不再弹；点外部关闭 = 下次登录还会弹）
        if (showUnreadRepliesDialog) {
            AlertDialog(
                onDismissRequest = { showUnreadRepliesDialog = false },
                title = { Text("客服回复了你的留言") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items(unreadReplies.size) { i ->
                            val r = unreadReplies[i]
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                if (!r.messageContent.isNullOrEmpty()) {
                                    Text("我：${r.messageContent}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Text("回复：${r.content ?: ""}", fontSize = 14.sp)
                                Text(
                                    "${r.adminName ?: "客服"} · ${r.createdAt?.replace("T", " ")?.take(16) ?: ""}",
                                    fontSize = 11.sp, color = Color.Gray
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showUnreadRepliesDialog = false
                        // 标记已读（失败也不打扰用户，下次登录会再弹一次）
                        scope.launch {
                            try {
                                val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                                val userId = tokenPrefs.getInt("user_id", 0)
                                val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
                                if (userId > 0) com.fz.yqlandroid.network.NetworkService.markRepliesRead(userId, jwtToken)
                            } catch (_: Exception) {}
                        }
                    }) { Text("已读，不再提醒") }
                }
            )
        }

        // ⭐ 需求#1：试用到期弹框（不可点外关闭；确定 → 清理并回登录页，对齐 iOS「取消→登录页」路径）
        if (showTrialEndDialog) {
            AlertDialog(
                onDismissRequest = { /* 强制用户看到并确认 */ },
                title = { Text("试用已结束") },
                text = { Text(trialEndMessage.ifEmpty { "试用已结束，请续费或扫码绑定后继续使用" }) },
                confirmButton = {
                    TextButton(onClick = {
                        showTrialEndDialog = false
                        keepAliveManager.stop()
                        onLogout()
                    }) { Text("回到登录页") }
                }
            )
        }

        // ===== 右侧操作面板（Zoom + 摄像头切换） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .padding(bottom = 100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Zoom 缩放控制器（仅显示，数据从后端同步）
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // + 放大
                    IconButton(onClick = { }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    
                    // 分隔点
                    repeat(3) {
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                    }
                    
                    // Zoom值
                    Text(
                        text = String.format("%.1fX", zoomValue),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    // 分隔点
                    repeat(3) {
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                    }
                    
                    // - 缩小
                    IconButton(onClick = { }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Remove, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                
                // 摄像头切换按钮
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable { webRTCManager.switchCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    // 外圈
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .then(Modifier.padding(2.dp))
                    )
                    // 内圈
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A74CA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "切换摄像头",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        
        // ===== 底部设置面板（仅显示同步数据，不操作） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(16.dp)
            ) {
                // 灰色卡片内容
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF4F4F8), RoundedCornerShape(12.dp))
                        .padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 曝光
                    SliderRow(
                        label = "曝光",
                        value = exposureValue,
                        range = 60f..600f,
                        displayText = String.format("%.0f", exposureValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 焦距
                    SliderRow(
                        label = "焦距",
                        value = focusValue,
                        range = 0f..1f,
                        displayText = String.format("%.2f", focusValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 流畅
                    SliderRow(
                        label = "流畅",
                        value = fluencyValue,
                        range = 0f..100f,
                        displayText = "清晰"
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // ISO 增益（滤镜移除后保留：0~100，PC 硬件链路 test_brightness）
                    SliderRow(
                        label = "ISO增益",
                        value = isoGainValue,
                        range = 0f..100f,
                        displayText = String.format("%.0f", isoGainValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 清晰度档位选择（仅显示，数据从后端同步）
                    // ⭐ §53.9 布局修复：5 档挤不下、最后一个「超高帧」被裁掉。
                    //   原因是各项按内容自然宽度排列 + 12dp 间距 + 18dp 外边距，窄屏放不下又不会换行。
                    //   改为**五项等分宽度**（每项 weight(1f)）并收紧间距/外边距——一行永远放得下，
                    //   不依赖换行、也不会随机裁掉最后一项（比换行更适合这个紧凑面板）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // ⭐ 对齐 iOS 5 档（ContentView 顺序 low→standard→high→p4k→ultra）：
                        //   此前少画「超低网」，服务器下发 type=low 时 UI 无处高亮。
                        // ⭐ §53.9：会员已开通的档位标绿。等级与档位对应（用户口径：等级1=2个档位）：
                        //   等级1 高清会员 → 超低网+高清；等级2 → +超清；等级3 → +超高清；等级4 → 全部。
                        // ⭐ 2026-08-01 用户要求：主页档位「超高清」「超高帧」互换**显示位置**（名称/等级映射不变：
                        //   超高清仍=level3、超高帧仍=level4，只是列表顺序把两者对调）
                        listOf("超低网", "高清", "超清", "超高帧", "超高清").forEach { name ->
                            val requiredLevel = when (name) {
                                "超低网", "高清" -> 1
                                "超清" -> 2
                                "超高清" -> 3          // 名称/等级不变
                                else -> 4              // 超高帧
                            }
                            QualityRadioItem(
                                name = name,
                                isSelected = selectedProfile == name,
                                unlocked = memberActivated && memberLevel >= requiredLevel,
                                modifier = Modifier.weight(1f),   // 五档等分，窄屏也放得下
                                onClick = { } // 仅显示，不操作
                            )
                        }
                    }
                }
            }
        }
        
        // ===== 推流状态指示（左上角，controls隐藏时也显示） =====
        if (!showControls) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Color(0xFF4CAF50) else Color(0xFFFF5722))
                )
                Spacer(modifier = Modifier.width(6.dp))
                // ⭐ 2026-07-11 用户要求：左上角只显示「码率 + PC是否连接」，
                //   编码/采集fps/推流fps 一律隐藏（未推流时仍显示连接状态文案）。
                Text(
                    text = if (isStreaming) "${currentKbps}kbps"
                           else connectionStatus,
                    color = Color.White,
                    fontSize = 12.sp
                )
                // ⭐ §53.2：「在线」与「在看」拆成两段，别再用一个灯表达两件事。
                //   绿=在线且在看 / 橙=在线但没出画面（→ 查拉流侧，不是账号或网络没登录）/ 灰=没上线。
                //   以前只看拉流心跳（PC 只在有画面时才发），PC 登录着但没画面会显示「无PC」，
                //   把故障现象说成了对方没上线，误导排障方向。
                if (isStreaming) {
                    val pcDotColor = when {
                        pcConnected -> Color(0xFF4CAF50)
                        pcOnlineCount > 0 -> Color(0xFFFF9800)
                        else -> Color(0xFF9E9E9E)
                    }
                    // ⭐ 2026-08-01 用户拍板：主页**不显示**观看端数量（撤掉台数，只留状态）
                    val pcText = when {
                        pcConnected -> "PC在线·在看"
                        pcOnlineCount > 0 -> "PC在线·未出画面"
                        else -> "无PC"
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(pcDotColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = pcText,
                        color = pcDotColor,
                        fontSize = 12.sp
                    )
                    // ⭐ 切网重连中（P2P）：过程可视化，PC 心跳恢复后自动消失
                    if (reconnecting && !pcConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFC107))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "网络切换重连中…",
                            color = Color(0xFFFFC107),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ===== 滑块行组件（仅显示，与iOS CustomSlider一致） =====
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.width(42.dp)
        )
        
        Spacer(modifier = Modifier.width(20.dp))
        
        // 滑块（只读显示）
        Box(modifier = Modifier.weight(1f).height(16.dp)) {
            // 背景轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .background(Color(0xFFDBDBE0), RoundedCornerShape(24.dp))
            )
            
            // 进度填充
            val progress = if (range.endInclusive > range.start) {
                ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            } else 0f
            
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF008BFF), RoundedCornerShape(24.dp))
            )
            
            // 黄色圆形拖块
            Box(
                modifier = Modifier
                    .offset(
                        x = (progress * 200).dp.coerceAtMost(200.dp) // 近似位置
                    )
                    .size(16.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFD65B), Color(0xFFFBAC00))
                        )
                    )
            )
        }
        
        Spacer(modifier = Modifier.width(10.dp))
        
        // 值显示
        Text(
            text = displayText,
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

// ===== 清晰度单选项（与iOS QualityRadioButton一致） =====
@Composable
private fun QualityRadioItem(
    name: String,
    isSelected: Boolean,
    // ⭐ §53.9：该档位是否已随会员开通 → 整项绿色背景标注
    unlocked: Boolean = false,
    // ⭐ §53.9 布局修复：调用方传 weight(1f) 让 5 档等分宽度（窄屏也放得下，不再裁掉最后一项）
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (unlocked) Color(0xFF4CAF50).copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 等分后内容居中（圆点与文字间留 3dp），视觉更整齐
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
    ) {
        // 圆圈（等分布局下收窄，给文字留位置）
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            // 选中内圈
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1197D6))
                )
            }
        }
        
        Text(
            text = name,
            fontSize = 10.sp,
            color = Color(0xFF1A1A1A),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            // 等分宽度下强制单行：档位名都是 2~3 个字，绝不允许折行把这一排撑高
            maxLines = 1,
            softWrap = false
        )
    }
}
