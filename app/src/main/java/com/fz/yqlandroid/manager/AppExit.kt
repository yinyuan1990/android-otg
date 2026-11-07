package com.fz.yqlandroid.manager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fz.yqlandroid.service.StreamingForegroundService

/**
 * 总后台用户管理 / PC 相机设定下发 EXIT_APP：不提示，停前台服务、关界面并移出最近任务后结束进程。
 * 前台服务是 START_STICKY，必须先 stop 再杀进程，否则系统会把它重新拉起来。
 */
object AppExit {
    fun exitNow(context: Context?) {
        Log.w("AppExit", "🚪 收到退出程序指令，结束进程")
        val main = Handler(Looper.getMainLooper())
        main.post {
            try { context?.applicationContext?.let { StreamingForegroundService.stop(it) } } catch (_: Exception) {}
            try { context?.findActivity()?.finishAndRemoveTask() } catch (_: Exception) {}
            main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
