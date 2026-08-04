package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.network.BindingItem
import com.fz.yqlandroid.network.NetworkService
import kotlinx.coroutines.launch

/**
 * 已绑定控制端列表页面
 * 与 iOS BindingListView + UnbindView 保持一致
 *
 * 功能：
 * 1. 加载已绑定列表（getBindingList）
 * 2. 加载中 / 错误 / 空 / 列表 四种状态
 * 3. 顶部刷新
 * 4. 点击解绑 → 输入绑定码 → unbindDevice(bindingId, 绑定码)
 * 5. 账号脱敏、绑定时间格式化（对标 iOS BindingRowView）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindingListScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""

    var bindings by remember { mutableStateOf<List<BindingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 解绑弹窗状态（对标 iOS UnbindView）
    var unbindTarget by remember { mutableStateOf<BindingItem?>(null) }

    // 加载绑定列表
    suspend fun loadBindings() {
        isLoading = true
        errorMessage = null
        NetworkService.getBindingList(jwtToken)
            .onSuccess {
                bindings = it.bindings
                isLoading = false
            }
            .onFailure {
                errorMessage = it.message ?: "加载失败"
                isLoading = false
            }
    }

    LaunchedEffect(Unit) { loadBindings() }

    // 标题含数量（对标 iOS titleText）
    val titleText = if (bindings.isEmpty()) "已绑定列表" else "已绑定列表（${bindings.size}）"

    // ===== 解绑弹窗 =====
    unbindTarget?.let { binding ->
        UnbindDialog(
            binding = binding,
            jwtToken = jwtToken,
            onDismiss = { unbindTarget = null },
            onUnbindSuccess = {
                bindings = bindings.filter { it.bindingId != binding.bindingId }
                unbindTarget = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(titleText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, "关闭", tint = Color(0xFF1A1A1A))
                    }
                },
                actions = {
                    if (!isLoading) {
                        IconButton(onClick = { scope.launch { loadBindings() } }) {
                            Icon(Icons.Default.Refresh, "刷新", tint = Color(0xFF1A1A1A))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("加载中...", fontSize = 14.sp, color = Color(0xFF808080))
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(50.dp)
                        )
                        Text("加载失败", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            errorMessage!!,
                            fontSize = 14.sp,
                            color = Color(0xFF808080),
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { scope.launch { loadBindings() } }) {
                            Text("重试")
                        }
                    }
                }

                bindings.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.LinkOff, null,
                            tint = Color(0xFFBFBFBF),
                            modifier = Modifier.size(60.dp)
                        )
                        Text("暂无绑定", fontSize = 17.sp, color = Color(0xFF808080))
                        Text(
                            "请先扫描控制端二维码进行绑定",
                            fontSize = 14.sp,
                            color = Color(0xFF808080)
                        )
                    }
                }

                else -> {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        bindings.forEach { binding ->
                            BindingRow(
                                binding = binding,
                                onUnbind = { unbindTarget = binding }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp),
                                color = Color(0xFFF0F0F0)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 绑定行（对标 iOS BindingRowView）
 */
@Composable
private fun BindingRow(
    binding: BindingItem,
    onUnbind: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DesktopWindows,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF1A1A1A)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            // 优先昵称，否则脱敏账号（对标 iOS）
            Text(
                text = binding.controlNickname?.ifEmpty { null }
                    ?: maskUsername(binding.controlUsername),
                fontSize = 16.sp,
                color = Color(0xFF1A1A1A)
            )
            if (!binding.createdAt.isNullOrEmpty()) {
                Text(
                    text = "绑定时间: ${formatBindingTime(binding.createdAt)}",
                    fontSize = 14.sp,
                    color = Color(0xFF808080)
                )
            }
        }

        Text(
            text = "解绑",
            fontSize = 14.sp,
            color = Color(0xFF808080),
            modifier = Modifier
                .clickable(onClick = onUnbind)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF999999)
        )
    }
}

/**
 * 解绑确认弹窗（对标 iOS UnbindView）
 * 输入绑定码 → NetworkService.unbindDevice(bindingId, 绑定码)
 */
@Composable
private fun UnbindDialog(
    binding: BindingItem,
    jwtToken: String,
    onDismiss: () -> Unit,
    onUnbindSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var secondaryPassword by remember { mutableStateOf("") }
    var isUnbinding by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val displayName = binding.controlNickname?.ifEmpty { null }
        ?: maskUsername(binding.controlUsername)

    AlertDialog(
        onDismissRequest = { if (!isUnbinding) onDismiss() },
        title = { Text("解绑设备") },
        text = {
            Column {
                Text(
                    displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A)
                )
                if (!binding.createdAt.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "绑定时间: ${formatBindingTime(binding.createdAt)}",
                        fontSize = 13.sp,
                        color = Color(0xFF808080)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF4F4F8), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning, null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "解绑后该控制端将无法远程控制此设备",
                        fontSize = 13.sp,
                        color = Color(0xFF808080)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = secondaryPassword,
                    onValueChange = { secondaryPassword = it },
                    label = { Text("绑定码") },
                    placeholder = { Text("请输入绑定码") },
                    singleLine = true,
                    enabled = !isUnbinding,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = Color.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isUnbinding && secondaryPassword.trim().isNotEmpty(),
                onClick = {
                    val pwd = secondaryPassword.trim()
                    if (pwd.isEmpty()) {
                        errorMessage = "请输入绑定码"
                        return@TextButton
                    }
                    isUnbinding = true
                    errorMessage = null
                    scope.launch {
                        NetworkService.unbindDevice(binding.bindingId, pwd, jwtToken)
                            .onSuccess {
                                isUnbinding = false
                                onUnbindSuccess()
                            }
                            .onFailure {
                                isUnbinding = false
                                errorMessage = it.message ?: "解绑失败"
                            }
                    }
                }
            ) {
                Text(if (isUnbinding) "解绑中..." else "确认解绑", color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(enabled = !isUnbinding, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 账号脱敏：前2位 + ** + 后2位，<=4位直接显示（对标 iOS maskUsername）
 */
private fun maskUsername(username: String): String {
    if (username.length <= 4) return username
    val prefix = username.take(2)
    val suffix = username.takeLast(2)
    return "$prefix**$suffix"
}

/**
 * 格式化绑定时间：ISO8601（可能带毫秒/微秒）→ "yyyy-MM-dd HH:mm"（对标 iOS formatTime）
 */
private fun formatBindingTime(isoTime: String?): String {
    if (isoTime.isNullOrEmpty()) return "—"
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )
    for (p in patterns) {
        try {
            val parser = java.text.SimpleDateFormat(p, java.util.Locale.getDefault())
            val date = parser.parse(isoTime) ?: continue
            val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return out.format(date)
        } catch (_: Exception) { /* try next */ }
    }
    return isoTime
}
