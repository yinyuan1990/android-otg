package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.manager.DeviceIDManager
import com.fz.yqlandroid.manager.WebSocketManager
import com.fz.yqlandroid.navigation.AppViewModel
import kotlinx.coroutines.launch

/**
 * 个人中心页面
 * 与iOS ProfileView 保持一致
 *
 * 功能：
 * 1. 用户头像 + 昵称 + 等级
 * 2. 注册时间
 * 3. 扫一扫（绑定设备）
 * 4. 修改密码
 * 5. 注销账号
 * 6. 版本号
 * 7. 关于我们
 * 8. 问题反馈
 * 9. 退出登录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    appViewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToChangePassword: () -> Unit = {},
    onNavigateToAboutUs: () -> Unit = {},
    onNavigateToMessage: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToBindingList: () -> Unit = {},
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    // 从SharedPreferences读取用户信息
    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val username = tokenPrefs.getString("username", "") ?: ""
    val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
    val deviceId = remember { DeviceIDManager.getDeviceID(context) }
    
    // 🔥 用户资料（对标 iOS ProfileViewModel.loadUserProfile）
    var userProfile by remember { mutableStateOf<com.fz.yqlandroid.network.UserProfileResponse?>(null) }
    
    // ⭐ §60：邀请活动状态（当前等级剩余天数）+ PC 端下载入口配置
    var referralInfo by remember { mutableStateOf<com.fz.yqlandroid.network.ReferralStatus?>(null) }
    var pcdlConfig by remember { mutableStateOf<com.fz.yqlandroid.network.PcdlConfig?>(null) }
    var showPcdlDialog by remember { mutableStateOf(false) }
    
    // ⭐ §62：日卡（邀请奖励，已领未用时显示；点确定二级确认后生效，从确定那一刻起算）
    var showTrialCardDialog by remember { mutableStateOf(false) }
    var trialCardBusy by remember { mutableStateOf(false) }
    var trialCardResult by remember { mutableStateOf<String?>(null) }
    
    // 对话框状态
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    
    // 版本号（动态读取，对标 iOS appVersionText）
    val appVersionText = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "${pInfo.versionName} ($versionCode)"
        } catch (_: Exception) { "1.0.0" }
    }
    
    // 🔥 加载用户资料
    LaunchedEffect(Unit) {
        if (jwtToken.isNotEmpty()) {
            com.fz.yqlandroid.network.NetworkService.getUserProfile(jwtToken)
                .onSuccess { userProfile = it }
        }
    }
    
    // ⭐ §60：加载邀请状态（剩余天数）+ PC 下载入口配置（失败均静默，不影响页面）
    LaunchedEffect(Unit) {
        if (jwtToken.isNotEmpty()) {
            com.fz.yqlandroid.network.NetworkService.getReferralStatus(jwtToken)
                .onSuccess { referralInfo = it }
        }
        com.fz.yqlandroid.network.NetworkService.getPcDownload()
            .onSuccess { pcdlConfig = it }
    }
    
    // 🔥 等级显示（对标 iOS levelDisplayText：activated + activation_level）
    // ⭐⭐ 2026-08-01 用户拍板【故意对调，勿"修复"】：等级4 对外显示「超高清」、等级3 对外显示「超高帧」。
    //   与总后台/后端的内部命名（3=超高清 4=超高帧）刻意不同——产品对外展示口径：顶级档（等级4）对客户叫"超高清"。
    val activated = tokenPrefs.getBoolean("activated", false)
    val activationLevel = tokenPrefs.getInt("activation_level", 0)
    val levelText = if (!activated) "试用用户" else when (activationLevel) {
        4 -> "超高清"; 3 -> "超高帧"; 2 -> "超清"; 1 -> "高清"; else -> "试用用户"
    }
    val levelColor = if (!activated) Color(0xFF808080) else when (activationLevel) {
        4 -> Color(0xFFFF6B00); 3 -> Color(0xFFFFD700); 2 -> Color(0xFF007AFF); 1 -> Color(0xFF34C759); else -> Color(0xFF808080)
    }
    
    // 退出登录确认
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出登录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    // 清理
                    WebSocketManager.instance.disconnect()
                    tokenPrefs.edit().clear().apply()
                    context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                        .edit().remove("password").apply()
                    appViewModel.logout()
                    onLogout()
                }) {
                    Text("确定", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    // 注销账号确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("注销账号") },
            text = {
                Column {
                    Text("注销后账号将无法恢复，所有数据将被删除。请输入绑定码确认注销。")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("绑定码") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        enabled = !isDeleting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (deleteError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(deleteError!!, color = Color.Red, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        if (deletePassword.isEmpty()) {
                            deleteError = "请输入绑定码"
                            return@TextButton
                        }
                        isDeleting = true
                        deleteError = null
                        val pwd = deletePassword
                        scope.launch {
                            com.fz.yqlandroid.network.NetworkService.deleteAccount(pwd, jwtToken)
                                .onSuccess {
                                    // 🔥 完整登出流程（对标 iOS performDeleteAccount）
                                    WebSocketManager.instance.disconnect()
                                    tokenPrefs.edit().clear().apply()
                                    context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                                        .edit().remove("password").apply()
                                    isDeleting = false
                                    showDeleteDialog = false
                                    deletePassword = ""
                                    appViewModel.logout()
                                    onLogout()
                                }
                                .onFailure {
                                    isDeleting = false
                                    deleteError = it.message ?: "注销失败，请重试"
                                }
                        }
                    }
                ) {
                    Text(if (isDeleting) "注销中..." else "确认注销", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = {
                    showDeleteDialog = false
                    deletePassword = ""
                    deleteError = null
                }) {
                    Text("取消")
                }
            }
        )
    }
    
    // ⭐ §60：PC 端下载弹框（主推复制直链；「浏览器打开」为次选）
    if (showPcdlDialog) {
        val pcdlUrl = pcdlConfig?.url ?: ""
        AlertDialog(
            onDismissRequest = { showPcdlDialog = false },
            title = { Text("电脑版下载") },
            text = {
                Column {
                    Text(
                        pcdlConfig?.content?.ifBlank { null }
                            ?: "复制下载地址后，粘贴到电脑浏览器地址栏，即可直接下载安装程序。",
                        fontSize = 14.sp, color = Color(0xFF1A1A1A)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        pcdlUrl,
                        fontSize = 12.sp,
                        color = Color(0xFF007AFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF2F2F7), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pcdl", pcdlUrl))
                        android.widget.Toast.makeText(context, "下载地址已复制，请到电脑浏览器粘贴下载", android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Exception) { /* 剪贴板异常静默 */ }
                }) { Text("复制下载地址", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pcdlUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { /* 无浏览器可用时静默 */ }
                    }) { Text("浏览器打开", color = Color(0xFF666666)) }
                    TextButton(onClick = { showPcdlDialog = false }) { Text("关闭", color = Color.Gray) }
                }
            }
        )
    }
    
    // ⭐ §62：日卡二级确认弹框（确定那一刻起生效；自行开通会员则以开通为准）
    if (showTrialCardDialog) {
        AlertDialog(
            onDismissRequest = { if (!trialCardBusy) showTrialCardDialog = false },
            title = { Text("使用日卡") },
            text = {
                Text(
                    "确定后立即生效，自确定那一刻起可体验全部功能 ${referralInfo?.trialHours ?: 24} 小时。\n\n" +
                    "若您已自行开通会员，以开通等级为准，未使用完的日使用将被直接覆盖。",
                    fontSize = 14.sp, color = Color(0xFF1A1A1A), lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !trialCardBusy,
                    onClick = {
                        trialCardBusy = true
                        scope.launch {
                            com.fz.yqlandroid.network.NetworkService.referralTrialUse(jwtToken)
                                .onSuccess { r ->
                                    trialCardBusy = false
                                    showTrialCardDialog = false
                                    trialCardResult = r.message ?: "日卡已生效！"
                                    // 刷新邀请状态（隐藏日卡行、剩余天数更新）
                                    com.fz.yqlandroid.network.NetworkService.getReferralStatus(jwtToken)
                                        .onSuccess { referralInfo = it }
                                }
                                .onFailure { e ->
                                    trialCardBusy = false
                                    showTrialCardDialog = false
                                    trialCardResult = e.message ?: "使用日卡失败，请重试"
                                    com.fz.yqlandroid.network.NetworkService.getReferralStatus(jwtToken)
                                        .onSuccess { referralInfo = it }
                                }
                        }
                    }
                ) { Text(if (trialCardBusy) "生效中..." else "确定使用", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(enabled = !trialCardBusy, onClick = { showTrialCardDialog = false }) {
                    Text("暂不使用", color = Color.Gray)
                }
            }
        )
    }
    
    // ⭐ §62：日卡结果提示
    if (trialCardResult != null) {
        AlertDialog(
            onDismissRequest = { trialCardResult = null },
            title = { Text("日卡") },
            text = { Text(trialCardResult!!, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { trialCardResult = null }) { Text("确定") }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "个人中心",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .background(Color(0xFFF2F2F7))
        ) {
            // ===== 头部：头像 + 昵称 + 等级 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color.Gray
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    // 昵称（优先 nickname，回退 username）
                    Text(
                        text = userProfile?.nickname?.ifEmpty { null }
                            ?: userProfile?.username?.ifEmpty { null }
                            ?: username.ifEmpty { "--" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    // 等级标签（对标 iOS levelDisplayText / levelColor）
                    Row(
                        modifier = Modifier
                            .background(
                                levelColor.copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = levelColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = levelText,
                            fontSize = 12.sp,
                            color = Color(0xFF1A1A1A)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // ===== 设置项第一组 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                // ⭐ §53.9 / §53.15：开通会员后这一行显示「<等级> + 注册成功时间」，未开通显示「注册时间」。
                //   两点口径（与 iOS 一致）：
                //   ① 等级名**不带"会员"二字**（超高清会员 → 超高清），命名同 levelText
                //      （总后台会员管理：1=高清 2=超清 3=超高清 4=超高帧），不用后端 activationLevelName。
                //   ② **不显示开通时间**，只显示账号注册时间——应用商店审核对"开通/付费"类信息敏感，
                //      不暴露任何与购买/激活时点相关的内容。
                val registeredAt = formatProfileDate(userProfile?.createdAt)
                // ⭐ §62：剩余天数并入等级行（数据=活动接口 remainingDays，领取奖励后刷新即对应），原 §60 独立行删除
                val remainingDays = referralInfo?.remainingDays ?: 0
                ProfileRow(
                    icon = Icons.Default.DateRange,
                    title = if (activated) levelText else "注册时间",
                    subtitle = when {
                        activated && remainingDays > 0 -> "剩余 $remainingDays 天 · 注册成功时间 $registeredAt"
                        activated -> "注册成功时间 $registeredAt"
                        else -> registeredAt
                    },
                    onClick = { }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                // ⭐ §62：日卡（邀请奖励，已领未用才显示；点击弹二级确认，确定那一刻起生效）
                if (referralInfo?.trialCardPending == true) {
                    ProfileRow(
                        icon = Icons.Default.CardGiftcard,
                        title = "日卡（邀请奖励）",
                        subtitle = "未使用，点击立即使用（生效 ${referralInfo?.trialHours ?: 24} 小时）",
                        titleColor = Color(0xFFE6432D),
                        onClick = { showTrialCardDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = Color(0xFFF0F0F0)
                    )
                }
                
                ProfileRow(
                    icon = Icons.Default.Search,
                    title = "扫一扫",
                    subtitle = "扫描控制端二维码进行绑定",
                    onClick = { onNavigateToScan() }
                )
                
                // §61（2026-08-14）：「已绑定控制端」入口按需求移除（BindingListScreen 及导航保留，入口不可达）
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Lock,
                    title = "修改密码",
                    onClick = { onNavigateToChangePassword() }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.PersonRemove,
                    title = "注销账号",
                    onClick = { showDeleteDialog = true }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // ===== 设置项第二组 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                ProfileRow(
                    icon = Icons.Default.Info,
                    title = "版本号",
                    subtitle = appVersionText,
                    onClick = { }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Info,
                    title = "关于我们",
                    onClick = { onNavigateToAboutUs() }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Email,
                    title = "问题反馈",
                    titleColor = Color(0xFFE6432D),   // §62 标红
                    onClick = { onNavigateToMessage() }
                )
                
                // ⭐ §60：PC 端下载入口（总后台开关+直链可配；所有用户可见，主推「复制下载地址→电脑浏览器粘贴」）
                if (pcdlConfig?.enabled == true && !pcdlConfig?.url.isNullOrBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    ProfileRow(
                        icon = Icons.Default.Computer,
                        title = "电脑版下载",
                        subtitle = "复制下载地址，电脑浏览器粘贴即可下载",
                        titleColor = Color(0xFFE6432D),   // §62 标红
                        onClick = { showPcdlDialog = true }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(30.dp))
            
            // ===== 退出按钮 =====
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .width(160.dp)
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCCCCCC)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "退出",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFAFAFA)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 设备ID
            Text(
                text = "设备ID: ${deviceId.take(8)}...",
                fontSize = 11.sp,
                color = Color(0xFF999999),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * 设置行组件
 * 与iOS ProfileRowView 保持一致
 */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color(0xFF1A1A1A),   // §62：支持标红（问题反馈/电脑版下载/日卡）
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF1A1A1A)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 标题 + 副标题
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = titleColor
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        
        // 箭头
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF999999)
        )
    }
}

/**
 * 格式化注册时间（对标 iOS ProfileView.formatDate）
 * 输入 ISO8601（可能带毫秒/微秒），输出 "yyyy年MM月dd日 HH:mm:ss"
 */
private fun formatProfileDate(dateString: String?): String {
    if (dateString.isNullOrEmpty()) return "—"
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )
    for (p in patterns) {
        try {
            val parser = java.text.SimpleDateFormat(p, java.util.Locale.getDefault())
            val date = parser.parse(dateString) ?: continue
            val out = java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", java.util.Locale.getDefault())
            return out.format(date)
        } catch (_: Exception) { /* try next */ }
    }
    return dateString
}
