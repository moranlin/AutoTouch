package com.local.autotouch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Scheduler {

    const val EXTRA_WORKFLOW_ID = "workflow_id"
    private const val ACTION_FIRE = "com.local.autotouch.ACTION_FIRE"

    /** 按当前存储内容为所有工作流重排闹钟（关闭的则取消）。 */
    fun rescheduleAll(context: Context) {
        WorkflowStore(context).load().forEach { workflow ->
            if (workflow.enabled) schedule(context, workflow) else cancel(context, workflow)
        }
    }

    fun schedule(context: Context, workflow: Workflow) {
        val triggerAt = workflow.nextTriggerAt() ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode(workflow.id),
            Intent(context, MainActivity::class.java),
            flags()
        )
        // setAlarmClock 可以精确穿透 Doze，且不需要额外权限，适合打卡类定时
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent),
            operation(context, workflow.id)
        )
    }

    fun cancel(context: Context, workflow: Workflow) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context, workflow.id))
    }

    private fun operation(context: Context, workflowId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(workflowId),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_WORKFLOW_ID, workflowId),
            flags()
        )

    /** 由工作流 id 稳定换算成 int 请求码，保证同一工作流多次排程互相覆盖而不是堆叠。 */
    private fun requestCode(workflowId: Long): Int =
        (workflowId xor (workflowId ushr 32)).toInt()

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
