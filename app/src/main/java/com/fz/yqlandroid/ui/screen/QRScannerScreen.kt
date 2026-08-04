package com.fz.yqlandroid.ui.screen

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fz.yqlandroid.navigation.AppViewModel
import com.fz.yqlandroid.network.NetworkService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

/**
 * 扫码绑定页面
 * 与iOS DeviceBindingQRScannerView 完全一致
 * 
 * 流程：
 * 1. 扫码界面 → 扫到二维码（控制端用户名）
 * 2. 确认绑定界面 → 输入管理密码
 * 3. 创建绑定 → 验证设备 → 绑定成功
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen(
    appViewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onBindSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态
    var scannedCode by remember { mutableStateOf("") }         // 扫到的控制端用户名
    var showConfirm by remember { mutableStateOf(false) }      // 是否显示确认界面
    var secondaryPassword by remember { mutableStateOf("") }   // 管理密码
    var isBinding by remember { mutableStateOf(false) }        // 绑定中
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    
    // 获取当前用户名
    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val deviceUsername = tokenPrefs.getString("username", "") ?: ""
    val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
    
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showConfirm) "确认绑定" else "扫一扫",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showConfirm) {
                            // 返回扫码界面
                            showConfirm = false
                            scannedCode = ""
                            secondaryPassword = ""
                            errorMessage = null
                            isScanning = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (showConfirm) Color.White else Color.Black.copy(alpha = 0.6f),
                    titleContentColor = if (showConfirm) Color(0xFF1A1A1A) else Color.White,
                    navigationIconContentColor = if (showConfirm) Color(0xFF1A1A1A) else Color.White
                )
            )
        }
    ) { paddingValues ->
        
        if (showConfirm) {
            // ===== 确认绑定界面（与iOS confirmView一致） =====
            ConfirmBindingView(
                deviceUsername = deviceUsername,
                controlUsername = scannedCode,
                secondaryPassword = secondaryPassword,
                onPasswordChange = { secondaryPassword = it },
                isBinding = isBinding,
                errorMessage = errorMessage,
                onConfirm = {
                    // 验证管理密码
                    if (secondaryPassword.trim().isEmpty()) {
                        errorMessage = "请输入管理密码"
                        return@ConfirmBindingView
                    }
                    
                    isBinding = true
                    errorMessage = null
                    
                    scope.launch {
                        try {
                            // Step 1: 创建绑定记录
                            val createResult = NetworkService.createBinding(deviceUsername, scannedCode, jwtToken)
                            createResult.fold(
                                onSuccess = { createResponse ->
                                    Log.d("QRScanner", "✅ 创建绑定成功: bindingId=${createResponse.bindingId}")
                                    
                                    // Step 2: 验证设备端管理密码
                                    val verifyResult = NetworkService.verifyDeviceBinding(
                                        bindingId = createResponse.bindingId,
                                        secondaryPassword = secondaryPassword.trim(),
                                        jwtToken = jwtToken
                                    )
                                    verifyResult.fold(
                                        onSuccess = { verifyResponse ->
                                            Log.d("QRScanner", "✅ 验证成功: deviceVerified=${verifyResponse.deviceVerified}")
                                            isBinding = false
                                            appViewModel.updateBoundControlCount(1)
                                            onBindSuccess()
                                        },
                                        onFailure = { error ->
                                            isBinding = false
                                            errorMessage = error.message ?: "验证失败"
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    isBinding = false
                                    errorMessage = error.message ?: "创建绑定失败"
                                }
                            )
                        } catch (e: Exception) {
                            isBinding = false
                            errorMessage = e.message ?: "网络错误"
                        }
                    }
                },
                onRescan = {
                    showConfirm = false
                    scannedCode = ""
                    secondaryPassword = ""
                    errorMessage = null
                    isScanning = true
                },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            // ===== 扫码界面 =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (cameraPermission.status.isGranted) {
                    // 相机预览 + ML Kit扫码
                    CameraPreviewWithScanner(
                        isScanning = isScanning,
                        onBarcodeDetected = { barcode ->
                            if (isScanning && scannedCode.isEmpty()) {
                                isScanning = false
                                scannedCode = barcode
                                Log.d("QRScanner", "📷 扫码结果: $barcode")
                                // 显示确认界面
                                showConfirm = true
                            }
                        }
                    )
                    
                    // 扫码框叠加层
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .border(3.dp, Color(0xFF2ECC71), RoundedCornerShape(12.dp))
                        )
                    }
                    
                    // 底部提示
                    Text(
                        text = "将二维码放入框内，即可自动扫描",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 120.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("需要相机权限才能扫码", fontSize = 16.sp, color = Color(0xFF666666))
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                                Text("授权相机")
                            }
                        }
                    }
                }
                
                // 跳过绑定（测试用）
                TextButton(
                    onClick = {
                        appViewModel.updateBoundControlCount(1)
                        onBindSuccess()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                ) {
                    Text("跳过绑定（测试）", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ===== 确认绑定界面（与iOS confirmView一致） =====
@Composable
private fun ConfirmBindingView(
    deviceUsername: String,
    controlUsername: String,
    secondaryPassword: String,
    onPasswordChange: (String) -> Unit,
    isBinding: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        // 图标 + 设备信息
        Text("🔗", fontSize = 40.sp)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = deviceUsername,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1A1A1A)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "绑定到 $controlUsername",
            fontSize = 14.sp,
            color = Color(0xFF808080)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
        
        // 警告提示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF4F4F8))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("绑定后，控制端可远程管理此设备", fontSize = 14.sp, color = Color(0xFF808080))
        }
        
        // 管理密码输入
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text("请输入管理密码", fontSize = 14.sp, color = Color(0xFF808080))
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = secondaryPassword,
                onValueChange = onPasswordChange,
                placeholder = { Text("管理密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isBinding,
                shape = RoundedCornerShape(10.dp)
            )
        }
        
        // 错误提示
        errorMessage?.let { msg ->
            Text(
                text = msg,
                color = Color.Red,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 按钮区域
        Column(
            modifier = Modifier.padding(bottom = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 确认绑定
            Button(
                onClick = onConfirm,
                modifier = Modifier.width(160.dp).height(46.dp),
                enabled = !isBinding && secondaryPassword.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (secondaryPassword.trim().isNotEmpty() && !isBinding) 
                        Color(0xFF007AFF) else Color(0xFFCCCCCC)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isBinding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("确认绑定", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            
            // 重新扫码
            TextButton(onClick = onRescan, enabled = !isBinding) {
                Text("重新扫码", fontSize = 16.sp, color = Color(0xFF808080))
            }
        }
    }
}

// ===== CameraX + ML Kit 扫码 =====
@Composable
private fun CameraPreviewWithScanner(
    isScanning: Boolean,
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                val scanner = BarcodeScanning.getClient()
                
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    @androidx.annotation.OptIn(ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    barcode.rawValue?.let { value ->
                                        if (value.isNotEmpty()) {
                                            onBarcodeDetected(value)
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QRScanner", "相机绑定失败: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
