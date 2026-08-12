package com.fz.yqlandroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.manager.DeviceIDManager
import com.fz.yqlandroid.network.NetworkService
import com.fz.yqlandroid.network.RegisterRequest
import kotlinx.coroutines.launch

/**
 * 注册页面
 * 与iOS RegisterView 保持一致
 * 
 * 简化字段：
 * - 账号（9-12位）
 * - 登录密码
 * - 管理密码
 * 
 * 隐藏字段（使用默认值）：
 * - 昵称（自动生成：账号前8位）
 * - 密保问题（服务器默认）
 * - 密保答案（默认1、2、3）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    onRegisterSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 用户输入
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secondaryPassword by remember { mutableStateOf("") }
    
    // 密码可见性
    var isPasswordVisible by remember { mutableStateOf(false) }
    
    // 状态
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 获取设备ID
    val deviceId = remember { DeviceIDManager.getDeviceID(context) }
    
    // 默认密保问题和答案（与iOS一致）
    val securityQuestion1 = "您的出生年月日是？"
    val securityAnswer1 = "1"
    val securityQuestion2 = "您的老家是哪里？"
    val securityAnswer2 = "2"
    val securityQuestion3 = "您最喜欢干的事是？"
    val securityAnswer3 = "3"
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部安全区域
            Spacer(modifier = Modifier.height(25.dp))
            
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                Text(
                    text = "监控注册",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A)
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 占位，保持标题居中
                Spacer(modifier = Modifier.width(24.dp))
            }
            
            // 分隔线
            HorizontalDivider(color = Color(0xFFF0F0F0))
            
            // 输入表单（⭐ 手表/小屏适配：整表单可滚动 + 键盘弹出时内容上移，否则小屏滑不动）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 22.dp)
            ) {
                Spacer(modifier = Modifier.height(30.dp))
                
                // 账号输入（9-12位）
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
                            modifier = Modifier.size(10.dp),
                            tint = Color(0xFF1A1A1A)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(6.dp))
                    
                    TextField(
                        value = username,
                        onValueChange = { newValue ->
                            // 限制只能输入字母和数字，最多12位
                            val filtered = newValue.filter { it.isLetterOrDigit() }
                            username = if (filtered.length > 12) filtered.take(12) else filtered
                        },
                        placeholder = { 
                            Text(
                                "请输入账号(9-12位)", 
                                color = Color(0xFFA3A3A3),
                                fontSize = 16.sp
                            ) 
                        },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp)
                    )
                    
                    // 显示当前输入长度
                    Text(
                        text = "${username.length}/12",
                        fontSize = 12.sp,
                        color = if (username.length >= 9) Color(0xFF4CAF50) else Color(0xFFA3A3A3)
                    )
                }
                
                HorizontalDivider(color = Color(0xFFF0F0F0))
                
                // 登录密码
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = Color(0xFF1A1A1A)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(6.dp))
                    
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = { 
                            Text(
                                "请输入登录密码", 
                                color = Color(0xFFA3A3A3),
                                fontSize = 16.sp
                            ) 
                        },
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
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp)
                    )
                    
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) 
                                Icons.Outlined.Visibility 
                            else 
                                Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = Color(0xFFA3A3A3),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                
                HorizontalDivider(color = Color(0xFFF0F0F0))
                
                // 管理密码
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = Color(0xFF1A1A1A)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(6.dp))
                    
                    TextField(
                        value = secondaryPassword,
                        onValueChange = { secondaryPassword = it },
                        placeholder = { 
                            // ⭐ 需求#6（2026-07-31）：「管理密码」改叫「绑定密码」，语义=绑定 PC 端用的密码
                            Text(
                                "请输入绑定密码", 
                                color = Color(0xFFA3A3A3),
                                fontSize = 16.sp
                            ) 
                        },
                        modifier = Modifier.weight(1f),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp)
                    )
                }
                
                HorizontalDivider(color = Color(0xFFF0F0F0))

                // ⭐ 需求#6：绑定密码说明（醒目提示）
                Text(
                    text = "💡 用于绑定PC端的密码，可以和登录密码一样",
                    color = Color(0xFFFF7A00),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                
                // 错误提示
                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = Color.Red,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                
                // 立即注册按钮
                Button(
                    onClick = {
                        // 验证
                        when {
                            username.length < 9 || username.length > 12 -> {
                                errorMessage = "账号必须是9-12位字母或数字"
                                return@Button
                            }
                            password.length < 6 || password.length > 20 -> {
                                errorMessage = "登录密码长度必须在6到20位之间"
                                return@Button
                            }
                            secondaryPassword.length < 6 || secondaryPassword.length > 20 -> {
                                errorMessage = "绑定密码长度必须在6到20位之间"
                                return@Button
                            }
                        }
                        
                        isLoading = true
                        errorMessage = null
                        
                        // 自动生成昵称（账号前8位）
                        val nickname = username.take(8)
                        
                        scope.launch {
                            try {
                                val result = NetworkService.registerDevice(
                                    RegisterRequest(
                                        username = username,
                                        nickname = nickname,
                                        deviceId = deviceId,
                                        password = password,
                                        secondaryPassword = secondaryPassword,
                                        securityQuestion1 = securityQuestion1,
                                        securityAnswer1 = securityAnswer1,
                                        securityQuestion2 = securityQuestion2,
                                        securityAnswer2 = securityAnswer2,
                                        securityQuestion3 = securityQuestion3,
                                        securityAnswer3 = securityAnswer3
                                    )
                                )
                                
                                result.fold(
                                    onSuccess = {
                                        isLoading = false
                                        // 保存账号信息到本地，用于登录页自动填充
                                        val prefs = context.getSharedPreferences("login_prefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().apply {
                                            putString("username", username)
                                            putString("password", password)
                                            putBoolean("remember", true)
                                            apply()
                                        }
                                        // 直接返回登录页
                                        onRegisterSuccess()
                                    },
                                    onFailure = { error ->
                                        isLoading = false
                                        errorMessage = error.message ?: "注册失败"
                                    }
                                )
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = e.message ?: "网络错误"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = if (isLoading) {
                                    Brush.linearGradient(listOf(Color.Gray, Color.Gray))
                                } else {
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFB7F4FC),
                                            Color(0xFF93D6F9),
                                            Color(0xFF65AEF7)
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "注册中...",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Text(
                                text = "立即注册",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
                
                // 底部留白：小屏滚动到底时按钮不贴屏幕边
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
