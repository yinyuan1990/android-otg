package com.fz.yqlandroid.manager

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * ⭐ §72 硬件级设备指纹（方案三·治本）。
 *
 * 在 AndroidKeyStore（TEE 硬件安全区）生成**不可导出**的 EC P-256 密钥对：
 * - 私钥永远锁在芯片里，root/整机克隆都复制不走（§71 的 installId 存 SharedPreferences，root 能抄）；
 * - 登录时用私钥对 "deviceId|installId|时间戳" 签名，后端用注册过的公钥验签——
 *   克隆机拿不到私钥，签不出有效签名。
 * 灰度：后端开关 device.hwkey.required 默认关（客户端静默注册公钥）；铺开后打开强制。
 * 任何异常均返回 null（登录降级为不带签名，由后端开关决定拦不拦），绝不因签名问题卡住登录流程。
 */
object HwKeyManager {
    private const val TAG = "HwKeyManager"
    private const val ALIAS = "yql_hw_device_key"

    private fun ensureEntry(): KeyStore.PrivateKeyEntry? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry) ?: run {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                )
                kpg.generateKeyPair()
                Log.d(TAG, "🔑 已在 AndroidKeyStore 生成硬件密钥对")
                ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            }
        } catch (e: Exception) {
            Log.e(TAG, "硬件密钥获取/生成失败", e)
            null
        }
    }

    /** 公钥（X.509/SPKI DER 的 Base64），失败 null */
    fun getPublicKeyB64(): String? = try {
        ensureEntry()?.certificate?.publicKey?.encoded?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Log.e(TAG, "导出公钥失败", e); null
    }

    /** 对 payload 做 SHA256withECDSA 签名（Base64），失败 null */
    fun sign(payload: String): String? = try {
        ensureEntry()?.privateKey?.let { pk ->
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(pk)
            s.update(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(s.sign(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Log.e(TAG, "签名失败", e); null
    }

    /**
     * ⭐ §75 私钥到底有没有落在硬件安全区（总后台「芯片密钥」列展示用）。
     * AndroidKeyStore 在没有 TEE 的机器上会**静默降级**成软件密钥——不查就分不出来，
     * 后台只看到「注册了公钥」，会误以为防克隆已生效。
     * @return strongbox / tee / software，查不到返回 null（老系统异常，不上报）
     */
    fun securityLevel(): String? = try {
        ensureEntry()?.privateKey?.let { pk ->
            val info = KeyFactory.getInstance(pk.algorithm, "AndroidKeyStore")
                .getKeySpec(pk, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    // SECURITY_LEVEL_UNKNOWN_SECURE：确定在安全硬件里但分不清哪一级
                    else -> if (info.securityLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE) "tee" else null
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "tee" else "software"
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "读取密钥安全等级失败", e); null
    }
}
