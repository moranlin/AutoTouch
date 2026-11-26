package com.local.autotouch

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 只做一件事：向系统注入手势（上滑、点击）。
 * 需要在「设置 - 无障碍」中手动开启。
 */
class AutoAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 屏幕底部向上滑，用于无密码锁屏解锁。 */
    fun swipeUp(durationMs: Long = 300): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, h * 0.88f)
            lineTo(w / 2f, h * 0.28f)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        )
    }

    /** 在指定屏幕坐标点击一次。 */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
        )
    }

    /**
     * 退出目标应用：先回桌面，再结束它的后台进程，让下次启动是冷启动。
     * 结束进程需要 KILL_BACKGROUND_PROCESSES 权限（普通权限，安装即授予）。
     */
    fun exitApp(packageName: String, killBackground: Boolean = true) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (!killBackground || packageName.isBlank()) return

        // 刚回桌面时目标应用仍算「可见」，系统会忽略这次清理；
        // 部分 ROM（如 MIUI）还会自己保活。所以按递增间隔多发几次，
        // 只要有一轮在它真正沉到后台后命中就够了。
        KILL_RETRY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({ killOnce(packageName) }, delay)
        }
    }

    private fun killOnce(packageName: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // 标准接口：普通权限即可，能结束后台进程
        runCatching { am.killBackgroundProcesses(packageName) }
        // 更彻底但需要系统权限，普通应用会抛 SecurityException，被静默忽略；
        // 某些定制 ROM 对已授权应用放行，所以顺手试一下并无副作用。
        runCatching {
            ActivityManager::class.java
                .getMethod("forceStopPackage", String::class.java)
                .invoke(am, packageName)
        }
    }

    private fun dispatch(gesture: GestureDescription): Boolean =
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = Unit
            override fun onCancelled(gestureDescription: GestureDescription?) = Unit
        }, null)

    companion object {
        private val KILL_RETRY_DELAYS_MS =
            longArrayOf(400L, 900L, 1600L, 2600L, 4000L, 6000L, 9000L, 12000L, 15000L)

        @Volatile
        var instance: AutoAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }
}
