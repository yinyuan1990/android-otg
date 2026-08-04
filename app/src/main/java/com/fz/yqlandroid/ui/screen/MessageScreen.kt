package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.network.MessageItem
import com.fz.yqlandroid.network.NetworkService
import kotlinx.coroutines.launch

/**
 * 问题反馈页面
 * 与iOS MessageView 保持一致：列表 + 发起 + 详情
 * API：GET /message/config、GET /message/list、POST /message/submit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
    val userId = tokenPrefs.getInt("user_id", 0)

    var messages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var maxLength by remember { mutableIntStateOf(200) }

    var showCompose by remember { mutableStateOf(false) }
    var detailMessage by remember { mutableStateOf<MessageItem?>(null) }

    fun reload() {
        if (userId <= 0) {
            errorMessage = "用户信息无效，请重新登录"
            isLoading = false
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            NetworkService.getMessageList(userId, 0, 20, jwtToken)
                .onSuccess { messages = it.content; isLoading = false }
                .onFailure { errorMessage = it.message; isLoading = false }
        }
    }

    LaunchedEffect(Unit) {
        maxLength = NetworkService.getMessageConfig(jwtToken)
        reload()
    }

    // 发起问题反馈弹窗
    if (showCompose) {
        MessageComposeDialog(
            maxLength = maxLength,
            onDismiss = { showCompose = false },
            onSubmit = { content, onResult ->
                scope.launch {
                    NetworkService.submitMessage(userId, content, jwtToken)
                        .onSuccess { onResult(true, it); showCompose = false; reload() }
                        .onFailure { onResult(false, it.message ?: "提交失败") }
                }
            }
        )
    }

    // 详情弹窗
    detailMessage?.let { msg ->
        MessageDetailDialog(message = msg, onDismiss = { detailMessage = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的问题反馈", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.Close, "关闭") }
                },
                actions = {
                    IconButton(onClick = { showCompose = true }) {
                        Icon(Icons.Default.Add, "发起", tint = Color(0xFF007AFF))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF2F2F7))
        ) {
            when {
                isLoading && messages.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                messages.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(errorMessage ?: "暂无问题反馈", color = Color(0xFF999999), fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showCompose = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                            shape = RoundedCornerShape(22.dp)
                        ) { Text("发起问题反馈", color = Color.White) }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { msg ->
                            MessageRow(msg) { detailMessage = msg }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: MessageItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusTag(message.status, message.statusName)
            Spacer(Modifier.weight(1f))
            Text(formatMsgDate(message.createdAt), fontSize = 12.sp, color = Color(0xFF999999))
        }
        Spacer(Modifier.height(10.dp))
        Text(message.content, fontSize = 15.sp, color = Color(0xFF1A1A1A), maxLines = 2)
        if (!message.replyContent.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("↩ ${message.replyContent}", fontSize = 13.sp, color = Color(0xFF34C759), maxLines = 1)
        }
    }
}

@Composable
private fun StatusTag(status: Int, statusName: String) {
    val color = when (status) {
        1 -> Color(0xFF34C759)   // 已回复
        2 -> Color(0xFF999999)   // 已关闭
        else -> Color(0xFFFF9500) // 待回复
    }
    Text(
        text = statusName.ifEmpty { when (status) { 1 -> "已回复"; 2 -> "已关闭"; else -> "待回复" } },
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun MessageComposeDialog(
    maxLength: Int,
    onDismiss: () -> Unit,
    onSubmit: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("发起问题反馈") },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= maxLength) content = it },
                    placeholder = { Text("请输入您的问题反馈内容，我们会尽快回复...") },
                    enabled = !isSubmitting,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("${content.length}/$maxLength", fontSize = 12.sp, color = Color(0xFF999999), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                if (error != null) {
                    Text(error!!, color = Color.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = {
                    if (content.isBlank()) { error = "问题反馈内容不能为空"; return@TextButton }
                    isSubmitting = true
                    error = null
                    onSubmit(content) { success, msg ->
                        isSubmitting = false
                        if (!success) error = msg ?: "提交失败，请重试"
                    }
                }
            ) { Text(if (isSubmitting) "提交中..." else "提交") }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MessageDetailDialog(message: MessageItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("问题反馈详情") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的问题反馈", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    StatusTag(message.status, message.statusName)
                }
                Spacer(Modifier.height(8.dp))
                Text(message.content, fontSize = 15.sp, color = Color(0xFF1A1A1A))
                Spacer(Modifier.height(4.dp))
                Text(formatMsgFullDate(message.createdAt), fontSize = 12.sp, color = Color(0xFF999999))

                Spacer(Modifier.height(16.dp))
                if (!message.replyContent.isNullOrEmpty()) {
                    Text("系统回复", fontWeight = FontWeight.SemiBold, color = Color(0xFF007AFF))
                    Spacer(Modifier.height(6.dp))
                    Text(message.replyContent, fontSize = 15.sp, color = Color(0xFF1A1A1A))
                    if (!message.replyAt.isNullOrEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(formatMsgFullDate(message.replyAt), fontSize = 12.sp, color = Color(0xFF999999))
                    }
                } else {
                    Text("客服正在处理中，请耐心等待...", fontSize = 14.sp, color = Color(0xFFFF9500))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun formatMsgDate(dateString: String): String {
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        val date = parser.parse(dateString) ?: return dateString
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(date)
    } catch (_: Exception) { dateString }
}

private fun formatMsgFullDate(dateString: String): String {
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        val date = parser.parse(dateString) ?: return dateString
        java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm", java.util.Locale.getDefault()).format(date)
    } catch (_: Exception) { dateString }
}
