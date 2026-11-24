package com.local.autotouch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_WORKFLOW_ID, -1L)
        val workflow = WorkflowStore(context).find(id)

        if (workflow == null || !workflow.enabled) {
            // 工作流已被删除或关闭，清掉残留闹钟
            if (workflow != null) Scheduler.cancel(context, workflow)
            return
        }

        // 先排下一次，避免执行耗时导致错过周期
        Scheduler.schedule(context, workflow)
        WakeService.start(context, workflow.id)
    }
}
