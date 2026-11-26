package com.local.autotouch

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast

/**
 * 透明界面：解锁 → 打开目标 App → 按顺序执行所有点击步骤 → 退出。
 */
class AutomationActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var keyguard: KeyguardManager
    private var workflow: Workflow? = null
    private var stepIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!loadWorkflow(intent)) {
            finish()
            return
        }

        showOverLockScreen()
        startRun()
    }

    /**
     * 本 Activity 是 singleInstance：另一个工作流在本次执行期间触发时会走到这里，
     * 此时重新开始执行新工作流（同一时刻并行点击本来就会互相干扰）。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        handler.removeCallbacksAndMessages(null)
        stepIndex = 0
        if (!loadWorkflow(intent)) {
            finishAndCleanup()
            return
        }
        startRun()
    }

    private fun loadWorkflow(intent: Intent): Boolean {
        val id = intent.getLongExtra(Scheduler.EXTRA_WORKFLOW_ID, -1L)
        workflow = WorkflowStore(this).find(id)
        return workflow != null
    }

    private fun startRun() {
        // 兜底：任何异常卡住都不要一直停在透明界面上
        handler.postDelayed({ finishAndCleanup() }, 180_000)
        handler.postDelayed({ unlock() }, 300)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    private fun unlock() {
        if (!keyguard.isKeyguardLocked) {
            launchTarget()
            return
        }

        // 仅无密码锁屏有效：模拟一次底部上滑
        AutoAccessibilityService.instance?.swipeUp(280)

        handler.postDelayed({
            // 无密码锁屏也可以直接请求系统收起
            if (keyguard.isKeyguardLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguard.requestDismissKeyguard(this, null)
            }
            handler.postDelayed({ launchTarget() }, 700)
        }, 900)
    }

    private fun launchTarget() {
        val wf = workflow ?: return
        if (wf.targetPackage.isBlank()) {
            toast("未设置目标应用")
            finishAndCleanup()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(wf.targetPackage)
        if (launchIntent == null) {
            toast("找不到应用：${wf.targetPackage}")
            finishAndCleanup()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            toast("无法启动应用：${e.message}")
            finishAndCleanup()
            return
        }

        stepIndex = 0
        handler.postDelayed({ runNextStep() }, wf.launchDelayMs)
    }

    private fun runNextStep() {
        val wf = workflow ?: return
        if (stepIndex >= wf.steps.size) {
            finishAndCleanup()
            return
        }

        val service = AutoAccessibilityService.instance
        if (service == null) {
            toast("无障碍服务未开启，无法模拟点击")
            finishAndCleanup()
            return
        }

        val step = wf.steps[stepIndex]
        when (step.type) {
            StepType.TAP -> service.tap(step.x.toFloat(), step.y.toFloat())
            StepType.EXIT_APP -> service.exitApp(wf.targetPackage, step.killBackground)
        }
        stepIndex++
        handler.postDelayed({ runNextStep() }, step.delayAfterMs.coerceAtLeast(100L))
    }

    private fun finishAndCleanup() {
        handler.removeCallbacksAndMessages(null)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
