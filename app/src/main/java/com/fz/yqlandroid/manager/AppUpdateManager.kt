package com.fz.yqlandroid.manager

import android.content.Context
import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * App 强制更新检查（配置在总后台「App更新配置」，与 iOS 同一接口同一语义）。
 *
 * 公共接口 GET /api/config/app-update（后端 /api/config 段整体 permitAll，无需登录），
 * ⚠️ 注释里别写 "/api/config/星星" 这种带 斜杠+星 的路径通配——Kotlin 块注释可嵌套，会把整个文件吞成注释。
 * 返回 { "config": "{\"android\":{\"enabled\",\"minVersion\",\"downloadUrl\"},\"ios\":{...}}" }。
 * 本地 versionName < minVersion 且 enabled=true → 登录页弹「不可关闭」的强更弹窗跳 downloadUrl。
 * 网络失败/解析失败一律放行（不能因为接口抖动把用户锁在门外）。
 */
object AppUpdateManager {

    data class ForceUpdate(
        val minVersion: String,
        val downloadUrl: String,
        val currentVersion: String
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 拉取 app.update.config 的整份 JSON（外层 {config:"<json string>"} 壳已剥），失败 null */
    private fun fetchUpdateConfig(): JsonObject? {
        val req = Request.Builder()
            .url("${APIConfig.BASE_URL}/api/config/app-update")
            .get()
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string()
        if (!resp.isSuccessful || body.isNullOrEmpty()) return null
        val outer = gson.fromJson(body, JsonObject::class.java)
        val cfgStr = outer.get("config")?.asString ?: return null
        return gson.fromJson(cfgStr, JsonObject::class.java)
    }

    /** ⭐ 2026-08-04 OTG 专版下载地址（总后台「App更新配置」otg 块）——主版"外接OTG"弹框用 */
    suspend fun fetchOtgDownloadUrl(): String? = withContext(Dispatchers.IO) {
        try {
            fetchUpdateConfig()?.getAsJsonObject("otg")?.get("downloadUrl")?.asString
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("jfh [OTG下载地址] 获取失败: ${e.message}")
            null
        }
    }

    /** 启动时调用：需要强更返回 ForceUpdate，否则 null */
    suspend fun checkForceUpdate(context: Context): ForceUpdate? = withContext(Dispatchers.IO) {
        try {
            val cfg = fetchUpdateConfig() ?: return@withContext null
            // ⭐ 2026-08-04 版本独立：OTG 专版（applicationId 以 .otg 结尾）读 otg 块，
            //   主版读 android 块。两仓库代码同构，按包名自动分流。
            val key = if (context.packageName.endsWith(".otg")) "otg" else "android"
            val android = cfg.getAsJsonObject(key) ?: return@withContext null

            val enabled = android.get("enabled")?.asBoolean ?: false
            val minVersion = android.get("minVersion")?.asString ?: ""
            val downloadUrl = android.get("downloadUrl")?.asString ?: ""
            if (!enabled || minVersion.isBlank() || downloadUrl.isBlank()) return@withContext null

            val local = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            } catch (_: Exception) { "0" }

            if (compareVersion(local, minVersion) < 0) {
                println("jfh [强更] 本地=$local < 最低=$minVersion → 强制更新 $downloadUrl")
                ForceUpdate(minVersion, downloadUrl, local)
            } else {
                println("jfh [强更] 本地=$local ≥ 最低=$minVersion → 放行")
                null
            }
        } catch (e: Exception) {
            println("jfh [强更] 检查失败(放行): ${e.message}")
            null
        }
    }

    /** 语义化版本比较（"1.0" vs "1.2.3" 逐段数字比），返回 <0 / 0 / >0 */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().split(".").map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.trim().split(".").map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
