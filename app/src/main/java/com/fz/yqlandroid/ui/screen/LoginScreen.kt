package com.fz.yqlandroid.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.R
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.fz.yqlandroid.manager.DeviceIDManager
import com.fz.yqlandroid.navigation.AppViewModel
import com.fz.yqlandroid.network.NetworkService
import com.fz.yqlandroid.network.LoginRequest
import kotlinx.coroutines.launch

/**
 * 登录页面
 * 与iOS MonitorLoginView 保持一致
 */
@Composable
fun LoginScreen(
    appViewModel: AppViewModel,
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: (boundControlCount: Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // ⭐ §68 登录失败弹框（服务端失败原因；设备不匹配时后端已拼上"当前设备对应的账号"）
    var errorDialog by remember { mutableStateOf<String?>(null) }
    // ⭐ §53.4-定稿：线路（多人/单人）与编码（H264/H265）已不在登录页选——
    //   线路由 SessionPolicy 在推流前按"与观看端是否同 WiFi"决定，编码取总后台默认值。
    //   登录页只保留下面的摄像头模式。
    // ⭐ 摄像头模式（第四十八章）："builtin"=自带(Camera2) / "otg"=外接OTG(UVC)，仅 Android，记住上次选择
    // ⭐⭐ 2026-08-04 OTG 专版（金凤凰OTG，包名 com.fz.yqlandroid.otg）：固定 otg，
    //   自带摄像头入口已移除——自带摄像头请用主版"金凤凰"App。代码与主版同构，只差本文件
    //   的模式固定 + applicationId + app_name。
    var selectedCameraMode by remember { mutableStateOf("otg") }
    // ⭐ 强制更新：非空 = 后台配置的最低版本高于本地 → 弹不可关闭弹窗（AppUpdateManager 公共接口）
    var forceUpdate by remember { mutableStateOf<com.fz.yqlandroid.manager.AppUpdateManager.ForceUpdate?>(null) }
    // ⭐ 设备号弹框（点击设备号显示完整 ID + 复制）
    var showDeviceIdDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    
    // 获取设备ID
    val deviceId = remember { DeviceIDManager.getDeviceID(context) }

    // ⭐ 手表/小屏适配：屏高 < 500dp（典型手表 300~450dp）时压缩顶部留白与 Logo，
    //   保证账号/密码/登录按钮不用滚太远就能看到
    val isSmallScreen = LocalConfiguration.current.screenHeightDp < 500
    
    // 加载保存的账号密码
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "") ?: ""
        val savedPassword = prefs.getString("password", "") ?: ""
        val savedRemember = prefs.getBoolean("remember", false)
        
        if (savedRemember && savedUsername.isNotEmpty()) {
            username = savedUsername
            password = savedPassword
            rememberPassword = true
        }
        // ⭐ OTG 专版：模式恒为 otg（不读旧记忆，防止从主版迁移的偏好把它带回 builtin）
        selectedCameraMode = "otg"
        // ⭐ 强制更新检查（公共接口，失败放行不拦门）
        forceUpdate = com.fz.yqlandroid.manager.AppUpdateManager.checkForceUpdate(context)
    }
    
    // 渐变背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFB1E8FA),
                        Color(0xFFC6E0FA),
                        Color(0xFFF4F4F9)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())   // ⭐ 整页可滚动：选项多时小屏也能划到底
                .imePadding()                            // ⭐ 键盘弹出时内容上移可滚（手表小屏键盘几乎盖满屏，没它=滑不动）
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(if (isSmallScreen) 12.dp else 60.dp))
            
            // Logo
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(if (isSmallScreen) 52.dp else 94.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
            
            Spacer(modifier = Modifier.height(if (isSmallScreen) 14.dp else 50.dp))
            
            // 登录表单卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    // 账号输入
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 账号图标
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF1A1A1A)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        TextField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = { Text("请输入账号", color = Color(0xFFA3A3A3)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // 密码输入
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 密码图标
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF1A1A1A)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("密码", color = Color(0xFFA3A3A3)) },
                            modifier = Modifier.weight(1f),
                            visualTransformation = if (isPasswordVisible) 
                                VisualTransformation.None 
                            else 
                                PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                        
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) 
                                    Icons.Outlined.Visibility 
                                else 
                                    Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFFA3A3A3)
                            )
                        }
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // 记住密码
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .clickable { rememberPassword = !rememberPassword },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .border(1.dp, Color(0xFF999999), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (rememberPassword) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF65AEF7), CircleShape)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))
                        
                        Text(
                            text = "记住密码",
                            fontSize = 16.sp,
                            color = Color(0xFF1A1A1A)
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // ⭐ §53.4-定稿（2026-07-28）：原「连接方式(多人/单人线路)」与「H264/H265 编码」
                    //   两组选项已移除。线路 = 推流前按"与观看端是否同 WiFi"自动决定（同 WiFi 才 P2P，
                    //   否则 SRS）；编码 = 总后台配置（默认 H265，本机硬编或观看端内核不支持则回退 H264）。
                    //   决策逻辑在 `manager/SessionPolicy.kt`（与 iOS 同构）。
                    //   登录页只保留下面的「摄像头模式：自带 / 外接OTG」。

                    // ⭐ 摄像头模式选择（第四十八章）：自带(Camera2) / 外接OTG(UVC)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "摄像头",
                            fontSize = 16.sp,
                            color = Color(0xFF1A1A1A)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // ⭐ OTG 专版：只保留外接OTG（自带入口在主版"金凤凰"）
                        listOf("otg" to "外接OTG").forEach { (mode, label) ->
                            val selected = selectedCameraMode == mode
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) Color(0xFF65AEF7) else Color(0xFFF0F0F0))
                                    .clickable { selectedCameraMode = mode }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    color = if (selected) Color.White else Color(0xFF666666)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(if (isSmallScreen) 14.dp else 30.dp))
            
            // 错误提示
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = Color.Red,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            
            // 登录按钮
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "请输入账号和密码"
                        return@Button
                    }
                    
                    isLoading = true
                    errorMessage = null
                    
                    scope.launch {
                        try {
                            // ⭐ §72 硬件密钥签名：payload=deviceId|installId|时间戳（三者与后端约定一致）
                            val loginInstallId = DeviceIDManager.getInstallId(context)
                            val hwTs = System.currentTimeMillis().toString()
                            val hwSign = com.fz.yqlandroid.manager.HwKeyManager.sign("$deviceId|$loginInstallId|$hwTs")
                            val result = NetworkService.login(
                                LoginRequest(
                                    username = username,
                                    password = password,
                                    deviceId = deviceId,
                                    userType = "device",
                                    installId = loginInstallId,
                                    hwPub = com.fz.yqlandroid.manager.HwKeyManager.getPublicKeyB64() ?: "",
                                    hwSign = hwSign ?: "",
                                    hwTs = if (hwSign != null) hwTs else "",
                                    hwLevel = com.fz.yqlandroid.manager.HwKeyManager.securityLevel() ?: ""
                                )
                            )
                            
                            result.fold(
                                onSuccess = { response ->
                                    // 🔥 Step 1: 保存登录凭证（与iOS一致）
                                    val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().apply {
                                        putString("username", username)
                                        if (rememberPassword) {
                                            putString("password", password)
                                        } else {
                                            remove("password")
                                        }
                                        putBoolean("remember", rememberPassword)
                                        // ⭐ §53.4：线路/编码已不由用户选（登录页也不再显示），
                                        //   这里只记住摄像头模式（自带 / 外接OTG）。
                                        putString("selected_camera_mode", selectedCameraMode)
                                        apply()
                                    }
                                    
                                    // 🔥 Step 2: 保存所有Token和用户信息（与iOS UserDefaults一致）
                                    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                                    tokenPrefs.edit().apply {
                                        putString("jwt_token", response.token)
                                        putString("permanent_token", response.permanentToken)
                                        putString("device_id", response.deviceId)
                                        putString("username", response.username)
                                        response.nickname?.let { putString("nickname", it) }
                                        response.userType?.let { putString("user_type", it) }
                                        response.userId?.let { putInt("user_id", it) }
                                        response.streamPushIp?.let { putString("stream_push_ip", it) }
                                        response.streamPushPort?.let { putInt("stream_push_port", it) }
                                        response.membershipType?.let { putString("membership_type", it) }
                                        response.status?.let { putString("status", it) }
                                        putInt("bound_control_count", response.boundControlCount ?: 0)
                                        
                                        // ⭐ §53.4-定稿：连接方式**改以后端下发为准**（不再被登录页选择覆盖）。
                                        //   "srs" = 总后台一键强制多人线路；其它(auto/p2p/缺省) = 交给
                                        //   SessionPolicy 在推流前按"与观看端是否同 WiFi"自动决定。
                                        val backendConnectMode = (response.connectMode ?: "auto").lowercase()
                                        putString("connect_mode", backendConnectMode)
                                        // ⭐ 摄像头模式运行时决策值（WebRTCManager.startPreview 读取，第四十八章）
                                        putString("camera_mode", selectedCameraMode)
                                        // ⭐ §53.4.4 编码默认值改由总后台配置（本机硬编或观看端内核
                                        //   不支持时由 SessionPolicy/H265Support 自动回退 h264）。
                                        //   §56.27：产品默认改 h264——字段缺省（老后端）也按 h264，与后端部署无关。
                                        val codecP2p = (response.videoCodecP2p ?: "h264").lowercase()
                                        val codecSrs = (response.videoCodecSrs ?: "h264").lowercase()
                                        putString(com.fz.yqlandroid.manager.H265Support.PREFS_RUNTIME_KEY, codecP2p)
                                        putString(com.fz.yqlandroid.manager.H265Support.PREFS_RUNTIME_KEY_SRS, codecSrs)
                                        // ⭐ §53.20.2：本机公网出口 IP，SessionPolicy 拿它与 PC 的
                                        //   publicIp 比对，防 /24 网段号撞车误判同 WiFi。老后端=空。
                                        putString("public_ip", response.clientIp ?: "")
                                        // ⭐ 需求#13（2026-07-31）：后端下发的 Android 最新版本号（空=不提示）。
                                        //   StreamingScreen 进入时与本地 versionName 比对，不一致弹软提示。
                                        putString("latest_android_version", response.latestVersions?.android ?: "")
                                        putInt("max_p2p_viewers", response.maxP2PViewers ?: 4)
                                        // ⭐ §53.21：force_relay / ice_servers_json 不再落地——P2P 中继与
                                        //   打洞代码已物理删除（纯局域网 host-only 直连），无消费方。
                                        //   顺带清掉历史残留，防旧 key 误导排查。
                                        remove("force_relay")
                                        remove("ice_servers_json")
                                        println("jfh [Login] ✅ 连接方式(后端)=$backendConnectMode, 编码默认(后端) P2P=$codecP2p/SRS=$codecSrs（P2P=纯局域网直连，无中继/打洞）")
                                        
                                        // 🔥 保存试用/激活信息（与iOS saveTrialInfo完全一致）
                                        response.trialInfo?.let { trial ->
                                            putBoolean("trial_required", trial.trialRequired)
                                            putBoolean("activated", trial.activated ?: false)
                                            trial.activationLevel?.let { putInt("activation_level", it) }
                                            trial.activationLevelName?.let { putString("activation_level_name", it) }
                                            trial.activationExpireAt?.let { putString("activation_expire_at", it) }
                                            // ⭐ §53.9 开通时间（「我的」页把"注册时间"整行换成「<等级>会员 + 开通时间」）
                                            trial.activationTime?.let { putString("activation_time", it) }
                                            // 日试用
                                            putBoolean("is_daily_trial", trial.isDailyTrial ?: false)
                                            trial.activationRemainingSeconds?.let { putInt("activation_remaining_seconds", it) }
                                            // 试用状态
                                            putBoolean("trial_ended", trial.trialEnded ?: false)
                                            trial.currentStage?.let { putInt("current_stage", it) }
                                            trial.totalStages?.let { putInt("total_stages", it) }
                                            trial.stageSeconds?.let { putInt("stage_seconds", it) }
                                            trial.remainingSeconds?.let { putInt("remaining_seconds", it) }
                                            trial.usedSeconds?.let { putInt("used_seconds", it) }
                                            
                                            val isDailyStr = if (trial.isDailyTrial == true) ", 日试用" else ""
                                            println("jfh [Login] ✅ 保存试用信息: trialRequired=${trial.trialRequired}, activated=${trial.activated}, level=${trial.activationLevelName}$isDailyStr")
                                        }
                                        apply()
                                    }
                                    
                                    // 🔥 Step 3: 更新ViewModel
                                    appViewModel.loginSuccess(
                                        token = response.token,
                                        permanent = response.permanentToken,
                                        device = response.deviceId,
                                        user = response.username,
                                        controlCount = response.boundControlCount ?: 0
                                    )
                                    
                                    // 🔥 Step 4: 获取设备初始配置（与iOS一致）
                                    val boundCount = response.boundControlCount ?: 0
                                    scope.launch {
                                        println("jfh [Login] 🔄 获取设备配置...")
                                        val config = com.fz.yqlandroid.manager.ConfigManager.fetchThinConfig(
                                            deviceId = response.deviceId,
                                            jwtToken = response.token
                                        )
                                        if (config != null) {
                                            com.fz.yqlandroid.manager.ConfigManager.cacheConfig(context, config)
                                            println("jfh [Login] ✅ 设备配置获取成功: type=${config.type}, direction=${config.direction}")
                                        } else {
                                            println("jfh [Login] ⚠️ 设备配置获取失败，使用默认值")
                                        }
                                        
                                        // 🔥 Step 5: 连接WebSocket（与iOS一致）
                                        println("jfh [Login] 🔄 连接WebSocket: deviceId=${response.deviceId}")
                                        com.fz.yqlandroid.manager.WebSocketManager.instance.connect(
                                            deviceId = response.deviceId,
                                            token = response.token,
                                            context = context
                                        )
                                        
                                        // 🔥 Step 6: 等WebSocket连接后跳转（最多2秒，超时也跳转）
                                        var waited = 0
                                        while (!com.fz.yqlandroid.manager.WebSocketManager.instance.isConnected && waited < 20) {
                                            kotlinx.coroutines.delay(100)
                                            waited++
                                        }
                                        if (com.fz.yqlandroid.manager.WebSocketManager.instance.isConnected) {
                                            println("jfh [Login] ✅ WebSocket已连接，跳转")
                                        } else {
                                            println("jfh [Login] ⚠️ WebSocket超时，仍然跳转")
                                        }
                                        isLoading = false
                                        onLoginSuccess(boundCount)
                                    }
                                },
                                onFailure = { error ->
                                    isLoading = false
                                    // §68 服务端失败原因改弹框（设备不匹配时含本机对应账号）
                                    errorDialog = error.message ?: "登录失败"
                                }
                            )
                        } catch (e: Exception) {
                            isLoading = false
                            errorDialog = e.message ?: "网络错误"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF65AEF7)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "登录",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // 注册入口
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "没有账号？",
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
                TextButton(onClick = onNavigateToRegister) {
                    Text(
                        text = "立即注册",
                        fontSize = 14.sp,
                        color = Color(0xFF65AEF7),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            // ⭐ 滚动容器内不能用 weight(1f)（无界高度会崩），改固定间距
            Spacer(modifier = Modifier.height(28.dp))
            
            // 设备ID显示：点击弹框看完整 ID 并可复制
            Text(
                text = "设备ID: ${deviceId.take(8)}…（点击查看/复制）",
                fontSize = 12.sp,
                color = Color(0xFF999999),
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .clickable { showDeviceIdDialog = true }
            )
        }
    }

    // ⭐ 设备号弹框：完整 ID + 复制按钮
    if (showDeviceIdDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceIdDialog = false },
            title = { Text("设备ID") },
            text = {
                Text(
                    text = deviceId,
                    fontSize = 14.sp,
                    color = Color(0xFF333333)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(deviceId))
                    Toast.makeText(context, "设备ID已复制", Toast.LENGTH_SHORT).show()
                    showDeviceIdDialog = false
                }) {
                    Text("复制", color = Color(0xFF65AEF7), fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceIdDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ⭐ §68 登录失败弹框：展示后端返回的失败原因；账号与设备不匹配时，
    //   后端已把「当前设备对应的账号」拼进 error 文案，这里原样弹出即可
    errorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorDialog = null },
            title = { Text("登录失败") },
            text = { Text(msg, fontSize = 14.sp, color = Color(0xFF333333)) },
            confirmButton = {
                TextButton(onClick = { errorDialog = null }) {
                    Text("知道了", color = Color(0xFF65AEF7), fontWeight = FontWeight.Medium)
                }
            }
        )
    }

    // ⭐ 强制更新弹窗：不可关闭（无 dismissButton、onDismissRequest 空实现），只能去更新
    forceUpdate?.let { fu ->
        AlertDialog(
            onDismissRequest = { /* 强制更新，不可关闭 */ },
            title = { Text("发现新版本") },
            text = {
                Text("当前版本 ${fu.currentVersion} 已停止支持，请更新到 ${fu.minVersion} 及以上版本后继续使用。")
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(fu.downloadUrl)
                            )
                        )
                    } catch (e: Exception) {
                        println("jfh [强更] 打开下载地址失败: ${e.message}")
                    }
                }) {
                    Text("立即更新", color = Color(0xFF65AEF7), fontWeight = FontWeight.Medium)
                }
            }
        )
    }
}
