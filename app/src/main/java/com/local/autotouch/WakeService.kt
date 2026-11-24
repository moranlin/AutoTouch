package com.local.autotouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * 唤醒屏幕并拉起透明的 AutomationActivity。
 * 用前台服务保证闹钟触发瞬间进程不被回收。
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workflowId = intent?.getLongExtra(Scheduler.EXTRA_WORKFLOW_ID, -1L) ?: -1L
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        wakeScreen()

        handler.postDelayed({ launchAutomation(workflowId) }, 1000)
        handler.postDelayed({ stopSelf() }, WAKE_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "AutoTouch:screen"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun launchAutomation(workflowId: Long) {
        try {
            startActivity(
                Intent(this, AutomationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Scheduler.EXTRA_WORKFLOW_ID, workflowId)
            )
        } catch (e: Exception) {
            // 部分 ROM 需在系统设置里允许「后台弹出界面」
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动唤醒", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AutoTouch")
            .setContentText("正在唤醒屏幕并执行自动化")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wake"
        private const val NOTIF_ID = 1

        /** 与 AutomationActivity 的兜底超时保持一致，避免步骤较多时屏幕被提前熄灭。 */
        private const val WAKE_TIMEOUT_MS = 180_000L

        fun start(context: Context, workflowId: Long) {
            val intent = Intent(context, WakeService::class.java)
                .putExtra(Scheduler.EXTRA_WORKFLOW_ID, workflowId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
