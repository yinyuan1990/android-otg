package com.fz.yqlandroid.manager

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * 设备ID管理器（持久化，卸载重装不变）
 * 
 * ✅ 使用 ANDROID_ID 作为基础：
 * - 卸载应用后不会变化
 * - 只有恢复出厂设置才会变化
 * - 不需要额外权限
 * 
 * 与 iOS Keychain 效果一致：卸载重装后设备ID保持不变
 */
object DeviceIDManager {
    private const val TAG = "DeviceIDManager"
    
    // 缓存设备ID，避免重复计算
    @Volatile
    private var cachedDeviceID: String? = null
    
    /**
     * 获取持久化设备ID
     * 
     * 🔥 核心原理：
     * ANDROID_ID 是设备级别的标识符，存储在系统数据库中
     * 卸载应用不会影响它，只有恢复出厂设置才会重置
     * 
     * 我们基于 ANDROID_ID + 包名 生成 SHA256 哈希
     * 确保：
     * 1. 同一设备同一应用 = 相同的设备ID
     * 2. 卸载重装后 = 相同的设备ID
     * 3. 不同应用 = 不同的设备ID（隐私保护）
     */
    @Synchronized
    fun getDeviceID(context: Context): String {
        // 如果有缓存，直接返回
        cachedDeviceID?.let { 
            return it 
        }
        
        val deviceId = generatePersistentID(context)
        cachedDeviceID = deviceId
        Log.d(TAG, "📱 设备ID: ${deviceId.take(8)}...")
        return deviceId
    }
    
    /**
     * 生成持久化设备ID
     * 
     * 公式: SHA256(ANDROID_ID + 包名 + 固定盐值)
     * 
     * - ANDROID_ID: 系统级，卸载不变
     * - 包名: 区分不同应用
     * - 盐值: 增加安全性
     */
    private const val PLATFORM_PREFIX = "android" // 🔥 平台前缀：与iOS共用同一套接口，靠此前缀区分平台
    // ⭐ §57.6：OTG 专版后缀——总后台按「android 前缀 + otg 后缀」把用户类型判成 Android-OTG，
    //   与 PC OTG 版设备号 `_OTG` 后缀（§56.22b）同思路。存量 OTG 测试号设备号会变（用户拍板不做迁移）。
    private const val OTG_SUFFIX = "otg"

    private fun generatePersistentID(context: Context): String {
        val androidId = getAndroidID(context)
        val packageName = context.packageName
        val salt = "HuoFengHuang_2024_DeviceID" // 固定盐值
        
        // 组合: ANDROID_ID-包名-盐值
        val combined = "$androidId-$packageName-$salt"
        
        // SHA256 哈希，取前32位（与iOS一致），加 android 前缀区分平台 + otg 后缀区分专版
        return PLATFORM_PREFIX + sha256(combined).take(32).uppercase() + OTG_SUFFIX
    }
    
    /**
     * 获取 ANDROID_ID
     * 
     * 特性：
     * - 每台设备唯一
     * - 卸载应用不会变化 ✅
     * - 恢复出厂设置后会变化
     * - 不需要任何权限
     */
    private fun getAndroidID(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"
        } catch (e: Exception) {
            Log.e(TAG, "获取 ANDROID_ID 失败", e)
            "unknown_device"
        }
    }
    
    /**
     * SHA256 哈希
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 获取原始 ANDROID_ID（调试用）
     */
    fun getRawAndroidID(context: Context): String {
        return getAndroidID(context)
    }
    
    /**
     * 清除缓存（仅清除内存缓存，设备ID本身不会变）
     */
    fun clearCache() {
        cachedDeviceID = null
        Log.d(TAG, "🗑️ 已清除内存缓存")
    }
}

