package com.fz.yqlandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 🔊 推流前台服务（camera 类型）—— 后台推流断流的根治方案。
 *
 * 背景：Android 9(P) 起系统硬性规定「后台应用不允许访问摄像头」，App 切后台约 1 分钟内
 * CameraDevice 会被系统直接断开（KeepAliveManager 的无声音频 + WakeLock 只能保 CPU/网络，
 * 保不住相机——iOS 的音频保活方案在 Android 对相机无效）。
 * 唯一正解：推流期间挂一个 foregroundServiceType="camera" 的前台服务，
 * 系统即视为「使用中」，后台/息屏均可继续采集推流。
 *
 * 生命周期：由 KeepAliveManager.start()/stop() 联动启停（推流开始→启动；停流/退出→停止）。
 * ⚠️ Android 11+ 限制：camera 类型前台服务必须在 App 处于前台时启动才有相机使用权——
 *    本项目推流一定是用户在前台点出来的，天然满足。
 */
class StreamingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+：显式声明 camera|microphone 类型（34+ 强校验与 Manifest 声明一致）
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                // API 24~29：普通前台服务即可让 App 保持「非后台」状态，相机不被断
                startForeground(NOTIF_ID, notification)
            }
            Log.d(TAG, "✅ 前台服务已启动（camera 保活）")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Log.d(TAG, "🔴 前台服务已停止")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "推流保活",
                        NotificationManager.IMPORTANCE_LOW  // 无声、状态栏低调常驻
                    ).apply { description = "推流期间保持摄像头后台可用" }
                )
            }
            return Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("推流中")
                .setContentText("视频推流运行中，请勿关闭")
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("推流中")
            .setContentText("视频推流运行中，请勿关闭")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "StreamingFgService"
        private const val CHANNEL_ID = "streaming_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start 失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StreamingForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop 失败: ${e.message}")
            }
        }
    }
}
