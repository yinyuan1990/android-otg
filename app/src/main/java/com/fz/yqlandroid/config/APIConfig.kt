package com.fz.yqlandroid.config

/**
 * API配置管理类
 * 与iOS APIConfig.swift 保持一致
 */
object APIConfig {
    
    // MARK: - 服务器配置
    
    // 基础URL
    const val BASE_URL = "https://api.147258yql.cn"
    
    // WebSocket URL
    const val BASE_WS_URL = "wss://ws.147258yql.cn/native-ws"
    const val BASE_STOMP_WS_URL = "wss://ws.147258yql.cn/ws"
    
    // API版本
    private const val API_VERSION = "/api"
    
    // 请求超时时间（秒）
    const val REQUEST_TIMEOUT = 30L
    
    // MARK: - API端点
    
    object Auth {
        // 🔥 与iOS完全一致：不再区分android，走同一套 /device 接口，靠 deviceId 的 android 前缀区分平台
        const val REGISTER_DEVICE = "/auth/register/device"      // 设备端注册（与iOS一致）
        const val LOGIN = "/auth/login/device"                   // 一机一码登录（与iOS一致）
        const val CHECK_DEVICE = "/auth/check-device"            // 注册前检查设备是否已注册
        const val VERIFY_TOKEN = "/auth/verify-token"            // 验证Token
        const val REFRESH_TOKEN = "/auth/refresh-token"          // 刷新Token
        const val STREAM_TOKEN = "/auth/stream/token/simple"     // 获取推流Token
        const val SECURITY_QUESTION_1 = "/config/security_question_1"
        const val SECURITY_QUESTION_2 = "/config/security_question_2"
        const val SECURITY_QUESTION_3 = "/config/security_question_3"
    }
    
    object Binding {
        const val CREATE = "/binding/create"                     // 创建绑定记录
        const val VERIFY_DEVICE = "/binding/verify-device"       // 设备端验证管理密码
        const val LIST = "/binding/list"                         // 获取已绑定列表
        const val UNBIND = "/binding/unbind"                     // 解绑
    }
    
    object User {
        const val PROFILE = "/user/profile"                      // 获取用户资料
        const val UPDATE_PROFILE = "/user/profile"               // 更新用户资料
        const val CHANGE_PASSWORD = "/user/password"             // 修改密码
        const val CHANGE_ALL_PASSWORDS = "/user/password/all"    // 同时修改登录密码和二级密码
        const val DELETE_ACCOUNT = "/user/account/delete"        // 注销账号（POST，与iOS一致）
    }
    
    object Membership {
        const val UPGRADE = "/membership/upgrade"
        const val STATUS = "/membership/status"
        const val HISTORY = "/membership/history"
    }
    
    object Device {
        const val LIST = "/device/list"
        const val BIND = "/device/bind"
        const val UNBIND = "/device/unbind"
        const val STATUS = "/device/status"
    }
    
    object Activation {
        const val ACTIVATE = "/activation/activate"              // 激活会员
        const val STATUS = "/activation/status"                  // 获取激活状态
    }
    
    object Message {
        const val CONFIG = "/message/config"                     // 获取问题反馈配置
        const val SUBMIT = "/message/submit"                     // 提交问题反馈
        const val LIST = "/message/list"                         // 获取问题反馈列表
        const val DETAIL = "/message/detail"                     // 获取问题反馈详情
        const val UNREAD_REPLIES = "/message/unread-replies"     // §56.11 未读回复（登录后弹框）
        const val READ = "/message/read"                         // §56.11 全部标记已读（点"已读"后不再弹）
    }

    object Ad {
        const val LOGIN_AD = "/config/login-ad"                  // §59 登录广告配置（公开接口）
        const val LOGIN_AD_PAGE = "/config/login-ad/page"        // §59 广告 HTML 页（WebView 直接加载）
    }

    object Referral {
        const val VARIANT = "otg"                                // §60 变体：OTG 版="otg"（主版为 ""），后端两套配置独立
        const val STATUS = "/referral/status"                    // §60 登录弹层三态+打卡+档位（需 JWT）
        const val BIND = "/referral/bind"                        // §60 试用用户填写邀请人（终身一次）
        const val CLAIM = "/referral/claim"                      // §60 会员领取档位奖励
        const val PCDL = "/config/pcdl"                          // §60 PC 端下载入口配置（公开接口，OTG 版下发 PC-OTG 专版直链）
    }
    
    // MARK: - 完整URL生成方法
    
    /**
     * 生成完整的API URL
     */
    fun fullURL(endpoint: String): String {
        return "$BASE_URL$API_VERSION$endpoint"
    }
    
    /**
     * 获取设备配置URL
     */
    fun getDeviceConfigURL(deviceId: String): String {
        return "$BASE_URL$API_VERSION/config/$deviceId"
    }
    
    // MARK: - 默认请求头
    
    val defaultHeaders: Map<String, String>
        get() = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "Android-App/1.0"
        )
}
