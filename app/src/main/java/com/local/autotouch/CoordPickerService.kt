package com.local.autotouch

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 坐标拾取：屏幕角落显示一个可拖动的「取点」悬浮球。
 *
 * 点悬浮球 → 全屏半透明取点层 → 点屏幕任意位置 → 记录坐标并回到本应用。
 * 长按取点层可取消。需要悬浮窗权限（本应用已在配置页引导授予）。
 */
class CoordPickerService : Service() {

    private lateinit var wm: WindowManager
    private var handle: TextView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var capture: CaptureView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var taskId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_TASK_ID, -1)?.let { if (it > 0) taskId = it }
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        if (handle == null) addHandle()
        toast("已进入取点模式：切到目标应用后点「取点」按钮")
        return START_NOT_STICKY
    }

    // ---------------- 悬浮球 ----------------

    private fun addHandle() {
        val view = TextView(this).apply {
            text = "取点"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(28, 14, 28, 14)
            setBackgroundColor(0xE6D32F2F.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) enterCapture()
                }
            }
            true
        }

        runCatching { wm.addView(view, params) }
        handle = view
        handleParams = params
    }

    // ---------------- 全屏取点层 ----------------

    private fun enterCapture() {
        if (capture != null) return
        val view = CaptureView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { wm.addView(view, params) }
            .onSuccess { capture = view }
            .onFailure { toast("无法添加取点层：${it.message}") }
    }

    private inner class CaptureView(context: Context) : View(context) {

        private val dimPaint = Paint().apply { color = 0x55000000 }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF5252.toInt()
            strokeWidth = 2f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val boxPaint = Paint().apply { color = 0xCC000000.toInt() }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
        }

        private val location = IntArray(2)
        private var screenX = 0f
        private var screenY = 0f
        private var cancelled = false

        private val cancelRunnable = Runnable {
            cancelled = true
            toast("已取消取点")
            removeCapture()
            removeHandle()
            stopSelf()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            getLocationOnScreen(location)
            val lx = screenX - location[0]
            val ly = screenY - location[1]

            canvas.drawLine(0f, ly, width.toFloat(), ly, crossPaint)
            canvas.drawLine(lx, 0f, lx, height.toFloat(), crossPaint)

            val label = "${screenX.toInt()}, ${screenY.toInt()}"
            val textWidth = textPaint.measureText(label)
            val boxLeft = (lx + 24f).coerceAtMost(width - textWidth - 40f).coerceAtLeast(8f)
            val boxTop = (ly + 24f).coerceAtMost(height - 90f).coerceAtLeast(8f)
            canvas.drawRect(
                boxLeft, boxTop,
                boxLeft + textWidth + 28f, boxTop + 56f,
                boxPaint
            )
            canvas.drawText(label, boxLeft + 14f, boxTop + 42f, textPaint)

            canvas.drawText("点击确定位置 · 长按取消", 40f, 90f, hintPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelled = false
                    screenX = event.rawX
                    screenY = event.rawY
                    handler.postDelayed(cancelRunnable, 800)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    screenX = event.rawX
                    screenY = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(cancelRunnable)
                    if (!cancelled) finish(screenX.toInt(), screenY.toInt())
                    return true
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(cancelRunnable)
            }
            return super.onTouchEvent(event)
        }
    }

    private fun finish(x: Int, y: Int) {
        PickStore(this).complete(x, y)
        toast("已记录坐标 ($x, $y)")
        // 悬浮窗还在时调用 startActivity 才能拿到「可见窗口」豁免，
        // 否则会被 Android 10+ 的后台启动限制静默丢掉。
        returnToApp()
        handler.postDelayed({ removeCapture() }, 500)
        handler.postDelayed({ removeHandle() }, 600)
        handler.postDelayed({ stopSelf() }, 900)
    }

    private fun removeCapture() {
        capture?.let { runCatching { wm.removeView(it) } }
        capture = null
    }

    private fun removeHandle() {
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
    }

    /**
     * 把编辑页所在的任务整体拉回前台。
     * 用 moveTaskToFront 而不是 startActivity：后者会新建一个 MainActivity 压在上面，
     * 而且编辑页里未保存的内容会丢。
     * REORDER_TASKS 是普通权限，安装即授予。
     */
    private fun returnToApp() {
        if (taskId <= 0) return
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeCapture()
        removeHandle()
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "坐标拾取", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("AutoTouch 取点中")
            .setContentText("点悬浮球选择屏幕位置，长按取消")
            .setOngoing(true)
            .build()
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CHANNEL_ID = "pick"
        private const val NOTIF_ID = 2

        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, taskId: Int) {
            context.startService(
                Intent(context, CoordPickerService::class.java)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }
    }
}
