package com.local.autotouch

import java.util.Calendar

enum class ScheduleType { DAILY, WEEKDAYS, WORKDAYS, CUSTOM }

/** 一个点击步骤：在 (x, y) 点一下，然后等 [delayAfterMs] 毫秒再执行下一步。 */
data class Step(
    var x: Int = 0,
    var y: Int = 0,
    var delayAfterMs: Long = 800L
)

data class Workflow(
    var id: Long = 0L,
    var name: String = "",
    var enabled: Boolean = false,
    var targetPackage: String = "",
    var hour: Int = 7,
    var minute: Int = 0,
    var scheduleType: ScheduleType = ScheduleType.WORKDAYS,
    /** 仅 CUSTOM 使用：bit0 = 周一 … bit6 = 周日 */
    var weekdaysMask: Int = 0b0011111,
    /** 打开目标 App 后等待多久再执行第一个步骤。 */
    var launchDelayMs: Long = 3000L,
    var steps: MutableList<Step> = mutableListOf()
) {

    fun matchesDay(cal: Calendar): Boolean {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return when (scheduleType) {
            ScheduleType.DAILY -> true
            ScheduleType.WEEKDAYS -> {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
            }
            ScheduleType.WORKDAYS -> ChinaWorkCalendar.isWorkday(year, month, day)
            ScheduleType.CUSTOM -> {
                // Calendar: 周日=1 … 周六=7，转成 周一=0 … 周日=6
                val index = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                (weekdaysMask shr index) and 1 == 1
            }
        }
    }

    /**
     * 从 [fromMillis] 起，找出下一次应当触发的时刻（当天该时间已过则从明天算起）。
     * 最多向后找 400 天，一年内没有任何匹配日期时返回 null。
     */
    fun nextTriggerAt(fromMillis: Long = System.currentTimeMillis()): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= fromMillis) cal.add(Calendar.DAY_OF_YEAR, 1)

        repeat(400) {
            if (matchesDay(cal)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun describeSchedule(): String {
        val days = when (scheduleType) {
            ScheduleType.DAILY -> "每天"
            ScheduleType.WEEKDAYS -> "周一至周五"
            ScheduleType.WORKDAYS -> "法定工作日"
            ScheduleType.CUSTOM -> {
                val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
                val picked = (0..6).filter { (weekdaysMask shr it) and 1 == 1 }
                if (picked.isEmpty()) "未选日期"
                else picked.joinToString("、") { "周" + names[it] }
            }
        }
        return String.format("%s %02d:%02d", days, hour, minute)
    }

    companion object {
        val WEEKDAY_BITS = 0b0011111
        const val MASK_ALL = 0b1111111
    }
}
