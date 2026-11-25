package com.local.autotouch

import android.content.Context

/**
 * 「取点」过程中的临时状态。放在 SharedPreferences 里是为了：
 * 1. 取点时用户切到别的应用，本界面会走 onStop/onResume，实例状态不可靠；
 * 2. 即使进程被回收，取回的坐标也不会丢。
 */
class PickStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("autotouch_pick", Context.MODE_PRIVATE)

    var workflowId: Long
        get() = sp.getLong(KEY_WF, -1L)
        private set(value) = sp.edit().putLong(KEY_WF, value).apply()

    var stepIndex: Int
        get() = sp.getInt(KEY_STEP, -1)
        private set(value) = sp.edit().putInt(KEY_STEP, value).apply()

    var status: Int
        get() = sp.getInt(KEY_STATUS, STATUS_NONE)
        private set(value) = sp.edit().putInt(KEY_STATUS, value).apply()

    var x: Int
        get() = sp.getInt(KEY_X, 0)
        private set(value) = sp.edit().putInt(KEY_X, value).apply()

    var y: Int
        get() = sp.getInt(KEY_Y, 0)
        private set(value) = sp.edit().putInt(KEY_Y, value).apply()

    fun begin(workflowId: Long, stepIndex: Int) {
        sp.edit()
            .putLong(KEY_WF, workflowId)
            .putInt(KEY_STEP, stepIndex)
            .putInt(KEY_STATUS, STATUS_PENDING)
            .apply()
    }

    fun complete(x: Int, y: Int) {
        sp.edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .putInt(KEY_STATUS, STATUS_DONE)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()

    companion object {
        const val STATUS_NONE = 0
        const val STATUS_PENDING = 1
        const val STATUS_DONE = 2

        private const val KEY_WF = "wf"
        private const val KEY_STEP = "step"
        private const val KEY_STATUS = "status"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}
