package com.fz.yqlandroid.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.fz.yqlandroid.R
import kotlinx.coroutines.delay

/**
 * 启动页
 * 与iOS SplashView 保持一致
 * 
 * 全屏背景图，1秒后跳转登录
 */
@Composable
fun SplashScreen(
    onNavigateToLogin: () -> Unit
) {
    // 1秒后跳转到登录页
    LaunchedEffect(Unit) {
        delay(1000)
        onNavigateToLogin()
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 全屏背景图
        Image(
            painter = painterResource(id = R.drawable.splash),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
