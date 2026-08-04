package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 设备配置管理器
 * 与iOS ConfigManager 保持一致
 * 
 * 功能：
 * 1. 登录后获取设备初始配置（GET /api/thin-config/{deviceId}）
 * 2. 缓存到本地SharedPreferences
 * 3. 提供给WebRTCManager作为推流初始参数
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "device_config"
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // 当前配置（内存缓存）
    var currentConfig: ThinRemoteConfig? = null
    
    /**
     * 获取设备初始配置（与iOS getThinRemoteConfig一致）
     * GET /api/thin-config/{deviceId}
     */
    suspend fun fetchThinConfig(deviceId: String, jwtToken: String): ThinRemoteConfig? = withContext(Dispatchers.IO) {
        try {
            val url = "${APIConfig.BASE_URL}/api/thin-config/$deviceId"
            
            println("jfh [Config] URL=$url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $jwtToken")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            println("jfh [Config] Status=${response.code}")
            println("jfh [Config] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                // 服务器返回 { "success": true, "data": {...}, "message": "..." }
                val configResponse = gson.fromJson(body, ThinConfigResponse::class.java)
                if (configResponse.success && configResponse.data != null) {
                    currentConfig = configResponse.data
                    println("jfh [Config] ✅ 获取成功: type=${configResponse.data.type}, direction=${configResponse.data.direction}, zoom=${configResponse.data.zoom}, fps=${configResponse.data.fps}, cjfps=${configResponse.data.cjfps}, bitrate=${configResponse.data.bitrate}, focus=${configResponse.data.focus}")
                    configResponse.data
                } else {
                    println("jfh [Config] ❌ success=false: ${configResponse.message}")
                    null
                }
            } else {
                println("jfh [Config] ❌ HTTP${response.code}")
                null
            }
        } catch (e: Exception) {
            println("jfh [Config] ❌ 异常: ${e.message}")
            null
        }
    }
    
    /**
     * 缓存配置到本地
     */
    fun cacheConfig(context: Context, config: ThinRemoteConfig) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("thin_config", gson.toJson(config)).apply()
            currentConfig = config
            Log.d(TAG, "✅ 配置已缓存")
        } catch (e: Exception) {
            Log.e(TAG, "缓存失败: ${e.message}")
        }
    }
    
    /**
     * 从本地加载缓存配置
     */
    fun loadCachedConfig(context: Context): ThinRemoteConfig? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("thin_config", null)
            if (json != null) {
                val config = gson.fromJson(json, ThinRemoteConfig::class.java)
                currentConfig = config
                Log.d(TAG, "✅ 从本地加载配置: type=${config.type}")
                config
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "加载缓存失败: ${e.message}")
            null
        }
    }
    
    /**
     * 获取默认配置
     */
    fun getDefaultConfig(): ThinRemoteConfig {
        return ThinRemoteConfig(
            type = "standard",
            zoom = 1.0f,
            ptype = "standard",
            direction = "-1",    // 默认后置
            fps = 120,           // 默认FPS（后端值，/4=30fps推送）
            cjfps = 240,         // 默认快门
            bitrate = 100,       // 默认码率百分比
            focus = 0.6f,        // 默认对焦
            brightness = 0f
        )
    }
}

// ========== 数据类 ==========

/**
 * thin-config API响应包装
 */
data class ThinConfigResponse(
    val success: Boolean = false,
    val data: ThinRemoteConfig? = null,
    val message: String? = null
)

/**
 * 设备配置（与iOS ThinRemoteConfig一致）
 */
data class ThinRemoteConfig(
    @SerializedName("device_id") val deviceId: String? = null,
    val type: String = "standard",         // 档位: standard/high/ultra/p4k
    val zoom: Float = 1.0f,               // 变焦: 1.0~3.0
    val ptype: String = "standard",       // 参数类型
    val direction: String = "-1",          // 摄像头: "-1"后置, "1"前置
    val exposureBias: Float? = null,       // 曝光补偿
    val fps: Int? = null,                  // 后端FPS（推送FPS = fps/4）
    val cjfps: Int? = null,               // 快门速度 60~600
    val bitrate: Int? = null,             // 码率百分比 0~100
    val angle: Int? = null,               // 角度
    val focus: Float? = null,             // 对焦距离 0.0~1.0
    val brightness: Float? = null,        // 亮度
    val saturation: Float? = null,        // 饱和度
    val contrast: Float? = null,          // 对比度
    @SerializedName("last_updated") val lastUpdated: String? = null,
    @SerializedName("updated_by") val updatedBy: String? = null
)
