package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.manager.WebSocketManager
import com.fz.yqlandroid.navigation.AppViewModel
import com.fz.yqlandroid.network.NetworkService
import kotlinx.coroutines.launch

/**
 * 修改密码页面
 * 与iOS ChangePasswordView 保持一致：
 * - 3 个输入框（原绑定码、新登录密码、新绑定码），原登录密码从本地自动获取
 * - 调用 PUT /user/password/all（changeAllPasswords）
 * - 成功后完整登出到登录页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    appViewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
    // 原登录密码自动获取（与iOS AccountStorageManager 一致，Android 存于 login_prefs）
    val oldPassword = remember {
        context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE).getString("password", "") ?: ""
    }

    var oldSecondaryPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newSecondaryPassword by remember { mutableStateOf("") }

    var isChanging by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }

    fun showError(msg: String) {
        isSuccess = false
        resultTitle = "修改失败"
        resultMessage = msg
        showResult = true
    }

    fun submit() {
        // 校验（与iOS handleChangePassword 一致）
        // ⭐ §64（2026-08-15）：恢复「原绑定码」必填（2026-07-31 需求#2曾移除）——
        //   后端 /user/password/all 对非空原绑定码照旧强校验，传错拦截。
        when {
            oldPassword.isEmpty() -> { showError("无法获取原登录密码，请重新登录后再试"); return }
            oldSecondaryPassword.isEmpty() -> { showError("请输入原绑定码"); return }
            newPassword.isEmpty() -> { showError("请输入新登录密码"); return }
            newPassword.length !in 6..20 -> { showError("新登录密码长度必须在6-20位之间"); return }
            oldPassword == newPassword -> { showError("新登录密码不能与原登录密码相同"); return }
            newSecondaryPassword.isEmpty() -> { showError("请输入新绑定码"); return }
            newSecondaryPassword.length !in 6..20 -> { showError("新绑定码长度必须在6-20位之间"); return }
        }

        isChanging = true
        scope.launch {
            NetworkService.changeAllPasswords(
                oldPassword = oldPassword,
                oldSecondaryPassword = oldSecondaryPassword,
                newPassword = newPassword,
                newSecondaryPassword = newSecondaryPassword,
                jwtToken = jwtToken
            ).onSuccess { msg ->
                isChanging = false
                isSuccess = true
                resultTitle = "修改成功"
                resultMessage = msg
                showResult = true
            }.onFailure {
                isChanging = false
                showError(it.message ?: "修改密码失败，请重试")
            }
        }
    }

    // 结果对话框
    if (showResult) {
        AlertDialog(
            onDismissRequest = { if (!isSuccess) showResult = false },
            title = { Text(resultTitle) },
            text = { Text(resultMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showResult = false
                    if (isSuccess) {
                        // 🔥 修改成功后完整登出（对标 iOS handleLogoutAfterPasswordChange）
                        WebSocketManager.instance.disconnect()
                        tokenPrefs.edit().clear().apply()
                        context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                            .edit().remove("password").apply()
                        appViewModel.logout()
                        onLogout()
                    }
                }) { Text("确定") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改密码", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.Close, "关闭") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
                .verticalScroll(scrollState)
                .imePadding(),   // ⭐ 手表/小屏适配：键盘弹出时内容上移可滚，输入框不被盖住
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(30.dp))

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color(0xFF1A1A1A)
            )
            Spacer(Modifier.height(12.dp))
            Text("修改登录密码和绑定码", fontSize = 14.sp, color = Color(0xFF808080))

            Spacer(Modifier.height(30.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF4F4F8))
            ) {
                // ⭐ §64：恢复「原绑定码」输入行（必填，后端强校验）
                PasswordInputRow("原绑定码", "请输入原绑定码", oldSecondaryPassword, !isChanging) { oldSecondaryPassword = it }
                HorizontalDivider(modifier = Modifier.padding(start = 96.dp), color = Color(0xFFE5E5EA))
                PasswordInputRow("新登录密码", "请输入新登录密码（6-20位）", newPassword, !isChanging) { newPassword = it }
                HorizontalDivider(modifier = Modifier.padding(start = 96.dp), color = Color(0xFFE5E5EA))
                PasswordInputRow("新绑定码", "请输入新绑定码（6-20位）", newSecondaryPassword, !isChanging) { newSecondaryPassword = it }
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = { submit() },
                enabled = !isChanging,
                modifier = Modifier.size(width = 160.dp, height = 46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007AFF),
                    disabledContainerColor = Color(0xFFCCCCCC)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isChanging) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("确认修改", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFAFAFA))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 密码输入行（对标 iOS PasswordInputRow） */
@Composable
private fun PasswordInputRow(
    title: String,
    placeholder: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = Color(0xFF808080),
            modifier = Modifier.width(80.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 14.sp, color = Color(0xFFB0B0B0)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color(0xFF1A1A1A)),
            modifier = Modifier.weight(1f)
        )
    }
}
