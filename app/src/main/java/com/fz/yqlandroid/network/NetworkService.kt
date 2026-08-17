package com.fz.yqlandroid.network

import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网络服务
 * 与iOS APIService 保持一致
 * 
 * 🔥 所有接口返回扁平JSON（无code/data包装），与iOS一致
 */
object NetworkService {
    
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    // ========== 登录 ==========
    
    suspend fun login(request: LoginRequest): Result<LoginResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.LOGIN)

            // 🔐 与iOS完全一致：AES加密 "username,password" → Base64，请求体 {data, deviceId}
            val encrypted = AESUtils.encryptLoginData(request.username, request.password)
                ?: return@withContext Result.failure(Exception("加密失败"))
            val bodyMap = mapOf(
                "data" to encrypted,
                "deviceId" to request.deviceId,
                // §56.9 上报机型（总后台用户管理「查看机型」用；后端登录成功后存到用户）
                "deviceModel" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                // ⭐ §57.4：OTG 专版标识——账号未用 OTG 码开通/已过期时后端拒登
                //   （code=OTG_NOT_ACTIVATED，error 文案经 parseErrorMessage 原样显示在登录页）。
                //   主版 yql-android 不带此字段，行为不变。老后端未部署 §57.4 时忽略该字段，照常放行。
                "clientVariant" to "otg"
            )
            val json = gson.toJson(bodyMap)
            
            println("jfh [Login] 接口: ${APIConfig.Auth.LOGIN}")
            println("jfh [Login] URL=$url")
            println("jfh [Login] deviceId=${request.deviceId}")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                // 🔥 后端要求的 User-Agent（与iOS登录一致），header() 覆盖默认 UA 避免重复
                .header("Content-Type", "application/json")
                .header("User-Agent", "iPhone/iOS")
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [Login] Status=${response.code}")
            println("jfh [Login] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                val loginResponse = gson.fromJson(body, LoginResponse::class.java)
                if (!loginResponse.token.isNullOrEmpty()) {
                    println("jfh [Login] ✅ 成功: username=${loginResponse.username}, boundControlCount=${loginResponse.boundControlCount}, streamPushIp=${loginResponse.streamPushIp}")
                    Result.success(loginResponse)
                } else {
                    val msg = parseErrorMessage(body) ?: "登录失败"
                    println("jfh [Login] ❌ token为空: $msg")
                    Result.failure(Exception(msg))
                }
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [Login] ❌ HTTP${response.code}: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Login] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 注册 ==========
    
    suspend fun registerDevice(request: RegisterRequest): Result<RegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.REGISTER_DEVICE)
            val json = gson.toJson(request)
            
            println("jfh [Register] URL=$url")
            println("jfh [Register] 请求体=$json")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply { APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [Register] Status=${response.code}")
            println("jfh [Register] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                // 🔥 服务器直接返回扁平JSON（无code/data包装）
                val registerResponse = gson.fromJson(body, RegisterResponse::class.java)
                if (!registerResponse.username.isNullOrEmpty()) {
                    println("jfh [Register] ✅ 成功: username=${registerResponse.username}, deviceId=${registerResponse.deviceId}")
                    Result.success(registerResponse)
                } else {
                    val msg = parseErrorMessage(body) ?: "注册失败"
                    println("jfh [Register] ❌ 失败: $msg")
                    Result.failure(Exception(msg))
                }
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [Register] ❌ HTTP${response.code}: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Register] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 获取推流Token ==========
    
    /**
     * 获取推流Token（与iOS getStreamToken完全一致）
     * POST /api/auth/stream/token/simple
     * Body: { "username": "xxx", "streamName": "xxx" }
     */
    /**
     * 获取推流Token（与iOS getStreamToken完全一致）
     * POST /api/auth/stream/token/simple
     * Body: { "username": "xxx", "streamName": "xxx" }
     */
    suspend fun getStreamToken(jwtToken: String, streamName: String, username: String = ""): Result<StreamTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.STREAM_TOKEN)
            
            val bodyMap = mapOf(
                "username" to username,
                "streamName" to streamName
            )
            val json = gson.toJson(bodyMap)
            
            println("jfh [StreamToken] URL=$url")
            println("jfh [StreamToken] 请求体=$json")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))  // 🔥 POST不是GET
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [StreamToken] Status=${response.code}")
            println("jfh [StreamToken] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                val streamResponse = gson.fromJson(body, StreamTokenResponse::class.java)
                println("jfh [StreamToken] ✅ 成功: token=${streamResponse.token.take(10)}...")
                Result.success(streamResponse)
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [StreamToken] ❌ $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [StreamToken] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 创建绑定 ==========
    
    suspend fun createBinding(deviceUsername: String, controlUsername: String, jwtToken: String = ""): Result<CreateBindingResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Binding.CREATE)
            val body = mapOf("deviceUsername" to deviceUsername, "controlUsername" to controlUsername)
            val json = gson.toJson(body)
            
            println("jfh [Binding] URL=$url")
            println("jfh [Binding] 请求体=$json")
            println("jfh [Binding] jwtToken=${if (jwtToken.isNotEmpty()) "${jwtToken.take(20)}..." else "空"}")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            
            println("jfh [Binding] Status=${response.code}")
            println("jfh [Binding] 完整Body=$responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, CreateBindingResponse::class.java)
                println("jfh [Binding] ✅ 创建成功: bindingId=${result.bindingId}, deviceUsername=${result.deviceUsername}, controlUsername=${result.controlUsername}, status=${result.status}, message=${result.message}")
                Result.success(result)
            } else {
                val msg = parseErrorMessage(responseBody) ?: "创建绑定失败: ${response.code}"
                println("jfh [Binding] ❌ 失败: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Binding] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 验证设备绑定 ==========
    
    suspend fun verifyDeviceBinding(bindingId: Int, secondaryPassword: String, jwtToken: String): Result<VerifyDeviceResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Binding.VERIFY_DEVICE)
            val body = mapOf("bindingId" to bindingId, "secondaryPassword" to secondaryPassword)
            val json = gson.toJson(body)
            
            println("jfh [Verify] 验证绑定: bindingId=$bindingId")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            
            println("jfh [Verify] Status=${response.code}, Body=$responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, VerifyDeviceResponse::class.java)
                println("jfh [Verify] ✅ 验证成功: deviceVerified=${result.deviceVerified}")
                Result.success(result)
            } else {
                val msg = parseErrorMessage(responseBody) ?: "验证失败: ${response.code}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Verify] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 用户资料 ==========

    /**
     * 获取用户资料（与iOS getUserProfile一致）
     * GET /user/profile，Header: Authorization: Bearer {token}
     */
    suspend fun getUserProfile(jwtToken: String): Result<UserProfileResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.User.PROFILE)

            val httpRequest = Request.Builder()
                .url(url)
                .get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()

            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()

            println("jfh [Profile] Status=${response.code}, Body=$body")

            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, UserProfileResponse::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "获取用户资料失败: ${response.code}"))
            }
        } catch (e: Exception) {
            println("jfh [Profile] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }

    // ========== 修改密码（同时改登录密码 + 绑定码，与iOS changeAllPasswords一致） ==========

    /**
     * PUT /user/password/all
     * body: { oldPassword, oldSecondaryPassword, newPassword, newSecondaryPassword }
     */
    suspend fun changeAllPasswords(
        oldPassword: String,
        oldSecondaryPassword: String,
        newPassword: String,
        newSecondaryPassword: String,
        jwtToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.User.CHANGE_ALL_PASSWORDS)
            val body = mapOf(
                "oldPassword" to oldPassword,
                "oldSecondaryPassword" to oldSecondaryPassword,
                "newPassword" to newPassword,
                "newSecondaryPassword" to newSecondaryPassword
            )
            val json = gson.toJson(body)

            val httpRequest = Request.Builder()
                .url(url)
                .put(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            println("jfh [ChangePwd] Status=${response.code}, Body=$responseBody")

            if (response.isSuccessful && responseBody != null) {
                val msg = try {
                    gson.fromJson(responseBody, Map::class.java)["message"] as? String
                } catch (_: Exception) { null }
                Result.success(msg ?: "密码修改成功")
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody) ?: "修改密码失败: ${response.code}"))
            }
        } catch (e: Exception) {
            println("jfh [ChangePwd] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }

    // ========== 注销账号（与iOS deleteAccount一致：POST + 绑定码） ==========

    /**
     * POST /user/account/delete（用 POST 而非 DELETE，避免 body 被 CDN 丢弃）
     * body: { secondaryPassword }
     */
    suspend fun deleteAccount(secondaryPassword: String, jwtToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.User.DELETE_ACCOUNT)
            val body = mapOf("secondaryPassword" to secondaryPassword)
            val json = gson.toJson(body)

            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            println("jfh [DeleteAccount] Status=${response.code}, Body=$responseBody")

            if (response.isSuccessful && responseBody != null) {
                val msg = try {
                    gson.fromJson(responseBody, Map::class.java)["message"] as? String
                } catch (_: Exception) { null }
                Result.success(msg ?: "注销成功")
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody) ?: "注销失败: ${response.code}"))
            }
        } catch (e: Exception) {
            println("jfh [DeleteAccount] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }

    // ========== 已绑定列表 / 解绑（与iOS getBindingList / unbindDevice一致） ==========

    /**
     * GET /binding/list
     */
    suspend fun getBindingList(jwtToken: String): Result<BindingListResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Binding.LIST)

            val httpRequest = Request.Builder()
                .url(url)
                .get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()

            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()

            println("jfh [BindingList] Status=${response.code}, Body=$body")

            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, BindingListResponse::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "获取绑定列表失败: ${response.code}"))
            }
        } catch (e: Exception) {
            println("jfh [BindingList] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * DELETE /binding/unbind/{bindingId}（与iOS一致：bindingId 在路径，body 带绑定码）
     * body: { secondaryPassword }
     */
    suspend fun unbindDevice(bindingId: Int, secondaryPassword: String, jwtToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL("${APIConfig.Binding.UNBIND}/$bindingId")
            val body = mapOf("secondaryPassword" to secondaryPassword)
            val json = gson.toJson(body)

            val httpRequest = Request.Builder()
                .url(url)
                .delete(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            println("jfh [Unbind] Status=${response.code}, Body=$responseBody")

            if (response.isSuccessful && responseBody != null) {
                val msg = try {
                    gson.fromJson(responseBody, Map::class.java)["message"] as? String
                } catch (_: Exception) { null }
                Result.success(msg ?: "解绑成功")
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody) ?: "解绑失败: ${response.code}"))
            }
        } catch (e: Exception) {
            println("jfh [Unbind] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }

    // ========== 问题反馈（与iOS MessageView 一致） ==========

    /**
     * GET /message/config → { success, data: { maxLength }, message }
     * 返回 maxLength，失败回退默认 200
     */
    suspend fun getMessageConfig(jwtToken: String): Int = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Message.CONFIG)
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                val resp = gson.fromJson(body, MessageConfigResponse::class.java)
                resp.data?.maxLength ?: 200
            } else 200
        } catch (_: Exception) { 200 }
    }

    /**
     * GET /message/list?userId=&page=&size= → { success, data: {content,totalElements,totalPages,currentPage}, message }
     */
    suspend fun getMessageList(userId: Int, page: Int, size: Int, jwtToken: String): Result<MessageListData> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL("${APIConfig.Message.LIST}?userId=$userId&page=$page&size=$size")
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()

            println("jfh [MessageList] Status=${response.code}, Body=$body")

            if (response.isSuccessful && body != null) {
                val resp = gson.fromJson(body, MessageListResponse::class.java)
                Result.success(resp.data ?: MessageListData())
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "获取问题反馈失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST /message/submit  body: { userId, content } → { success, message, data }
     */
    suspend fun submitMessage(userId: Int, content: String, jwtToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Message.SUBMIT)
            val body = mapOf("userId" to userId, "content" to content)
            val json = gson.toJson(body)
            val httpRequest = Request.Builder()
                .url(url).post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            println("jfh [SubmitMessage] Status=${response.code}, Body=$responseBody")

            if (response.isSuccessful && responseBody != null) {
                val resp = gson.fromJson(responseBody, SubmitMessageResponse::class.java)
                if (resp.success) Result.success(resp.message ?: "提交成功")
                else Result.failure(Exception(resp.message ?: "提交失败"))
            } else {
                Result.failure(Exception(parseErrorMessage(responseBody) ?: "提交失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §56.11 获取未读回复（登录成功后调用，有未读则弹框）
     * GET /message/unread-replies?userId= → { success, total, data: [ {replyId,messageId,messageContent,content,adminName,createdAt} ] }
     */
    suspend fun getUnreadReplies(userId: Int, jwtToken: String): Result<List<UnreadReplyItem>> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL("${APIConfig.Message.UNREAD_REPLIES}?userId=$userId")
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()

            println("jfh [UnreadReplies] Status=${response.code}, Body=$body")

            if (response.isSuccessful && body != null) {
                val resp = gson.fromJson(body, UnreadRepliesResponse::class.java)
                Result.success(resp.data ?: emptyList())
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "获取未读回复失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §56.11 全部未读回复标记已读（弹框点"已读"后调用，之后登录不再弹）
     * POST /message/read  body: { userId }
     */
    suspend fun markRepliesRead(userId: Int, jwtToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Message.READ)
            val json = gson.toJson(mapOf("userId" to userId))
            val httpRequest = Request.Builder()
                .url(url).post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            println("jfh [MarkRepliesRead] Status=${response.code}")
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("标记已读失败: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §59 获取登录广告配置（公开接口，登录成功进推流页后调用）
     * GET /config/login-ad → { config: "<json string>" }，json = {enabled,title,content,version}
     * 客户端只用 enabled/title/version（内容由 WebView 直接加载 /config/login-ad/page）
     */
    suspend fun getLoginAd(): Result<LoginAdConfig> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Ad.LOGIN_AD)
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply { APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()

            println("jfh [LoginAd] Status=${response.code}, Body=$body")

            if (response.isSuccessful && body != null) {
                val envelope = gson.fromJson(body, LoginAdEnvelope::class.java)
                val cfg = gson.fromJson(envelope.config ?: "{}", LoginAdConfig::class.java)
                Result.success(cfg)
            } else {
                Result.failure(Exception("获取登录广告失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== §60 邀请活动 + PC 下载入口 ====================

    /**
     * §60 邀请活动状态（登录成功进推流页后调用，需 JWT）
     * GET /referral/status?variant= → {enabled, state, boundCount, successCount, tiers, remainingDays, ...}
     * state = TRIAL_CAN_BIND（试用未绑定→邀请人输入框）/ TRIAL_BOUND（已用过邀请）/ MEMBER（打卡+领取）
     */
    suspend fun getReferralStatus(jwtToken: String): Result<ReferralStatus> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Referral.STATUS) + "?variant=" + APIConfig.Referral.VARIANT
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            println("jfh [ReferralStatus] Status=${response.code}, Body=$body")
            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, ReferralStatus::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "获取邀请状态失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §60 试用用户填写邀请人（终身一次，绑定成功解锁体验）
     * POST /referral/bind  body: { inviter, deviceId, variant }
     */
    suspend fun referralBind(inviter: String, deviceId: String, jwtToken: String): Result<ReferralActionResult> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Referral.BIND)
            val json = gson.toJson(mapOf(
                "inviter" to inviter,
                "deviceId" to deviceId,
                "variant" to APIConfig.Referral.VARIANT
            ))
            val httpRequest = Request.Builder()
                .url(url).post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            println("jfh [ReferralBind] Status=${response.code}, Body=$body")
            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, ReferralActionResult::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "绑定失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §60 会员领取档位奖励（延长当前等级到期时间）
     * POST /referral/claim  body: { milestone, variant }
     */
    suspend fun referralClaim(milestone: Int, jwtToken: String): Result<ReferralActionResult> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Referral.CLAIM)
            val json = gson.toJson(mapOf(
                "milestone" to milestone,
                "variant" to APIConfig.Referral.VARIANT
            ))
            val httpRequest = Request.Builder()
                .url(url).post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            println("jfh [ReferralClaim] Status=${response.code}, Body=$body")
            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, ReferralActionResult::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "领取失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §62 使用日卡（「我的」页二级确认后调用，从确定那一刻起生效 trialHours 小时）
     * POST /referral/trial/use  body: { variant }
     */
    suspend fun referralTrialUse(jwtToken: String): Result<ReferralActionResult> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Referral.TRIAL_USE)
            val json = gson.toJson(mapOf("variant" to APIConfig.Referral.VARIANT))
            val httpRequest = Request.Builder()
                .url(url).post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            println("jfh [ReferralTrialUse] Status=${response.code}, Body=$body")
            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, ReferralActionResult::class.java))
            } else {
                Result.failure(Exception(parseErrorMessage(body) ?: "使用日卡失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §60 PC 端下载入口配置（公开接口，「我的」页入口用）
     * GET /config/pcdl?variant= → { enabled, url, content }
     */
    suspend fun getPcDownload(): Result<PcdlConfig> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Referral.PCDL) + "?variant=" + APIConfig.Referral.VARIANT
            val httpRequest = Request.Builder()
                .url(url).get()
                .apply { APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            println("jfh [Pcdl] Status=${response.code}, Body=$body")
            if (response.isSuccessful && body != null) {
                Result.success(gson.fromJson(body, PcdlConfig::class.java))
            } else {
                Result.failure(Exception("获取下载信息失败: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== 工具方法 ==========
    
    /**
     * 从错误响应body提取message或error字段
     */
    private fun parseErrorMessage(body: String?): String? {
        return try {
            body?.let {
                val json = gson.fromJson(it, Map::class.java)
                json["message"] as? String ?: json["error"] as? String
            }
        } catch (_: Exception) { null }
    }
}

// ========== 请求/响应数据类 ==========

/**
 * 登录请求
 */
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val userType: String = "device",
    // ⭐ §71 安装实例ID（首启随机 UUID）：后端按 deviceId 记录活跃安装，克隆机单活互踢
    val installId: String = "",
    // ⭐ §72 硬件密钥三件套（AndroidKeyStore/TEE 不可导出密钥）：
    //   hwPub=公钥(SPKI DER Base64)，hwSign=对 "deviceId|installId|hwTs" 的签名，hwTs=毫秒时间戳
    val hwPub: String = "",
    val hwSign: String = "",
    val hwTs: String = "",
    // ⭐ §75 私钥实际存放位置（tee/strongbox/software）：总后台「芯片密钥」列展示，用于发现静默降级
    val hwLevel: String = ""
)

/**
 * 登录响应（扁平结构，与iOS LoginResponse一致）
 * 服务器直接返回，无code/data包装
 */
data class LoginResponse(
    val token: String = "",
    val permanentToken: String = "",
    val username: String = "",
    val deviceId: String = "",
    val userType: String? = null,
    val userId: Int? = null,
    val nickname: String? = null,
    val streamPushIp: String? = null,
    val streamPushPort: Int? = null,
    val boundControlCount: Int? = null,
    val membershipType: String? = null,
    val status: String? = null,
    val message: String? = null,
    val banned: Int? = null,
    val trialInfo: TrialInfo? = null,
    // 🔥 连接方式（与iOS一致）："srs" | "p2p"，缺省按 srs 走
    val connectMode: String? = null,
    // 🔥 是否需要跳转扫码绑定：1=需要，0=不需要
    val scan: Int? = null,
    // ⭐ P2P 配置（与iOS一致，登录时下发）
    val iceServers: List<IceServer>? = null,   // STUN/TURN 列表
    val forceRelay: Boolean? = null,           // 强制 TURN 中继
    val maxP2PViewers: Int? = null,            // 最大 P2P 观看端数
    // ⭐ §53.4.4：编码默认值改由总后台配置（默认 h265，不支持时客户端自动回退 h264）
    val videoCodecP2p: String? = null,         // "h264" | "h265"
    val videoCodecSrs: String? = null,         // "h264" | "h265"
    // ⭐ §53.20.2：本机公网出口 IP（后端按请求来源回填）。与 PC 上报的 publicIp 比对，
    //   防 /24 网段号撞车（两地都是 192.168.1.x）误判同 WiFi。老后端缺省 → 跳过该校验。
    val clientIp: String? = null,
    // ⭐ 需求#13（2026-07-31）：三端最新版本号（总后台可配）。与本地版本比对，不一致提示更新（软提示）。
    val latestVersions: LatestVersions? = null
)

// ⭐ 需求#13：三端最新版本号
data class LatestVersions(
    val pc: String? = null,
    val ios: String? = null,
    val android: String? = null
)

/**
 * 试用/激活信息（与iOS TrialInfo完全一致）
 */
data class TrialInfo(
    val trialRequired: Boolean = false,          // 是否需要试用限制
    val activated: Boolean? = null,              // 是否已激活
    val activationLevel: Int? = null,            // 激活等级 (1=高清, 2=超清, 3=超高清, 4=超高帧；等级1对应超低网+高清两个档位)
    val activationLevelName: String? = null,     // 等级名称
    val activationExpireAt: String? = null,      // 激活到期时间
    val activationTime: String? = null,          // ⭐ §53.9 开通时间（「我的」页显示"<等级>会员 + 开通时间"）
    val qualityAccess: List<String>? = null,     // 可用画质列表
    // 日试用相关
    val isDailyTrial: Boolean? = null,           // 是否日试用码激活
    val activationRemainingSeconds: Int? = null,  // 剩余有效秒数
    // 未激活时的试用状态
    val trialEnded: Boolean? = null,             // 当天试用是否已全部结束
    val currentStage: Int? = null,               // 当前试用阶段 (1-6)
    val totalStages: Int? = null,                // 总阶段数
    val stageSeconds: Int? = null,               // 当前阶段总秒数
    val remainingSeconds: Int? = null,            // 当前阶段剩余秒数
    val usedSeconds: Int? = null,                // 当前阶段已用秒数
    val message: String? = null                  // 提示信息
)

/**
 * 注册请求（与iOS DeviceRegisterRequest一致）
 */
data class RegisterRequest(
    val username: String,
    val nickname: String,
    val deviceId: String,
    val password: String,
    val secondaryPassword: String,
    val securityQuestion1: String,
    val securityAnswer1: String,
    val securityQuestion2: String,
    val securityAnswer2: String,
    val securityQuestion3: String,
    val securityAnswer3: String,
    // §56.9 注册时上报机型（默认自动取，调用处无需改动）
    val deviceModel: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
)

/**
 * §56.11 未读回复（登录后弹框）
 */
data class UnreadRepliesResponse(
    val success: Boolean = false,
    val total: Int = 0,
    val data: List<UnreadReplyItem>? = null
)

data class UnreadReplyItem(
    val replyId: Long = 0,
    val messageId: Long = 0,
    val messageContent: String? = null,   // 我的留言原文
    val content: String? = null,          // 管理员回复内容
    val adminName: String? = null,
    val createdAt: String? = null
)

/**
 * §59 登录广告：外层 { config: "<json string>" }，内层 {enabled,title,content,version}
 * version=总后台每次保存写入的时间戳，本地记「已读version」判断是否重新弹
 */
data class LoginAdEnvelope(
    val config: String? = null
)

data class LoginAdConfig(
    val enabled: Boolean = false,
    val title: String? = null,
    val version: Long = 0
)

/**
 * §60 邀请活动状态（登录弹层用）
 */
data class ReferralTier(
    val count: Int = 0,               // 第几人档
    val months: Int = 0,              // 本档新增月数
    val cumulativeMonths: Int = 0,    // 累计月数
    val status: String = "LOCKED"     // LOCKED / ACHIEVED / CLAIMABLE / CLAIMED
)

data class ReferralStatus(
    val enabled: Boolean = false,
    val state: String? = null,        // MEMBER / TRIAL_CAN_BIND / TRIAL_BOUND
    val popupContent: String? = null, // 活动说明文案（总后台可配）
    val trialHours: Int = 24,
    val trialCardPending: Boolean = false,  // §62 日卡已领未用（「我的」页显示「日卡」行）
    val remainingDays: Long = 0,      // 当前等级剩余天数（个人中心显示）
    val level: Int? = null,
    val expireAt: String? = null,
    val referralTrialActive: Boolean = false,
    val boundCount: Int = 0,          // 已邀请 n 人
    val successCount: Int = 0,        // 成功解锁 m 人
    val tiers: List<ReferralTier>? = null
)

/**
 * §60 绑定/领取结果
 */
data class ReferralActionResult(
    val success: Boolean = false,
    val message: String? = null,
    val trialLevel: Int? = null,
    val trialExpireAt: String? = null,
    val months: Int? = null,
    val expireAt: String? = null,
    val remainingDays: Long? = null
)

/**
 * §60 PC 端下载入口配置
 */
data class PcdlConfig(
    val enabled: Boolean = false,
    val url: String? = null,
    val content: String? = null
)

/**
 * 注册响应（扁平结构，与iOS DeviceRegisterResponse一致）
 * 服务器返回: { "username", "deviceId", "message", "permanentToken", "userType" }
 */
data class RegisterResponse(
    val username: String? = null,
    val deviceId: String? = null,
    val message: String? = null,
    val permanentToken: String? = null,
    val userType: String? = null
)

/**
 * 推流Token响应
 */
data class StreamTokenResponse(
    val token: String = "",
    val streamKey: String? = null,
    val streamUrl: String? = null,
    val whipUrl: String? = null,
    val iceServers: List<IceServer>? = null
)

/**
 * ICE服务器配置
 */
data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

/**
 * 创建绑定响应（与iOS CreateBindingResponse一致）
 */
data class CreateBindingResponse(
    val success: Boolean = false,
    val bindingId: Int = 0,
    val deviceUsername: String = "",
    val controlUsername: String = "",
    val status: String = "",
    val deviceVerified: Boolean = false,
    val controlVerified: Boolean = false,
    val message: String = ""
)

/**
 * 验证设备绑定响应（与iOS VerifyDeviceResponse一致）
 */
data class VerifyDeviceResponse(
    val success: Boolean = false,
    val bindingId: Int = 0,
    val deviceVerified: Boolean = false,
    val controlVerified: Boolean = false,
    val status: String = "",
    val message: String = ""
)

/**
 * 用户资料响应（与iOS UserProfileResponse一致）
 */
data class UserProfileResponse(
    val username: String = "",
    val nickname: String? = null,
    val avatar: String? = null,
    val userType: String? = null,       // "采集端" / "控制端"
    val membershipType: String? = null, // "试用" / "永久" / "月付"
    val status: String? = null,         // "有效" / "已过期" / "已暂停"
    val createdAt: String? = null       // 创建时间
)

/**
 * 已绑定列表项（与iOS BindingItem一致）
 */
data class BindingItem(
    val bindingId: Int = 0,
    val controlUsername: String = "",
    val controlNickname: String? = null,
    val createdAt: String? = null
)

/**
 * 已绑定列表响应（与iOS BindingListResponse一致）
 */
data class BindingListResponse(
    val bindings: List<BindingItem> = emptyList(),
    val count: Int = 0
)

// ========== 问题反馈模型（与iOS MessageView 一致） ==========

data class MessageConfig(val maxLength: Int = 200)

data class MessageConfigResponse(
    val success: Boolean = false,
    val data: MessageConfig? = null,
    val message: String? = null
)

/**
 * 单条问题反馈（与iOS MessageItem一致）
 */
data class MessageItem(
    val id: Int = 0,
    val content: String = "",
    val status: Int = 0,          // 0待回复 1已回复 2已关闭
    val statusName: String = "",
    val replyContent: String? = null,
    val replyAdminName: String? = null,
    val replyAt: String? = null,
    val createdAt: String = ""
)

data class MessageListData(
    val content: List<MessageItem> = emptyList(),
    val totalElements: Int = 0,
    val totalPages: Int = 0,
    val currentPage: Int = 0
)

data class MessageListResponse(
    val success: Boolean = false,
    val data: MessageListData? = null,
    val message: String? = null
)

data class SubmitMessageResponse(
    val success: Boolean = false,
    val message: String? = null
)
