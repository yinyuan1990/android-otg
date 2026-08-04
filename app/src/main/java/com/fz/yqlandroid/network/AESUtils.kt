package com.fz.yqlandroid.network

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES加密工具
 * 与iOS AESUtils.swift 和后端 NewAesUtils.java 保持一致
 * 
 * 算法: AES/ECB/PKCS5Padding (PKCS5 = PKCS7 在AES block size下等价)
 * 密钥: 16字节 (AES-128)
 */
object AESUtils {
    
    // 🔑 AES密钥（与iOS和后端一致）
    private const val AES_KEY = "7793rfdf-3datt9d"
    
    /**
     * AES加密 + Base64编码
     * @param data 明文字符串
     * @return Base64编码的密文，失败返回null
     */
    fun encrypt(data: String): String? {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            val result = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            println("jfh [AES] 加密成功: ${data.take(10)}... → ${result.take(20)}...")
            result
        } catch (e: Exception) {
            println("jfh [AES] ❌ 加密失败: ${e.message}")
            null
        }
    }
    
    /**
     * Base64解码 + AES解密
     * @param data Base64编码的密文
     * @return 明文字符串，失败返回null
     */
    fun decrypt(data: String): String? {
        return try {
            if (data.isEmpty()) return ""
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decoded = Base64.decode(data, Base64.NO_WRAP)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            println("jfh [AES] ❌ 解密失败: ${e.message}")
            null
        }
    }
    
    /**
     * 加密登录数据（与iOS encryptLoginData一致）
     * 格式: "username,password" → AES加密 → Base64
     */
    fun encryptLoginData(username: String, password: String): String? {
        return encrypt("$username,$password")
    }
}
