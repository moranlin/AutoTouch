package com.local.autotouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleTest {

    private fun cal(year: Int, month: Int, day: Int, hour: Int = 8, minute: Int = 0): Calendar =
        Calendar.getInstance().apply { set(year, month - 1, day, hour, minute, 0) }

    private fun fmt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(millis)

    private fun workflow(
        hour: Int = 7,
        minute: Int = 0,
        type: ScheduleType,
        mask: Int = Workflow.WEEKDAY_BITS
    ) = Workflow(hour = hour, minute = minute, scheduleType = type, weekdaysMask = mask)

    // ---------- 节假日判定 ----------

    @Test
    fun `national day is holiday`() {
        assertTrue(ChinaWorkCalendar.isHoliday(2026, 10, 1))
        assertTrue(ChinaWorkCalendar.isHoliday(2026, 10, 7))
    }

    @Test
    fun `makeup workday on weekend counts as workday`() {
        // 2026-09-20 是周日，但国务院安排调休补班
        assertTrue(ChinaWorkCalendar.isWorkday(2026, 9, 20))
    }

    @Test
    fun `spring festival makeup saturday counts as workday`() {
        assertTrue(ChinaWorkCalendar.isWorkday(2026, 2, 28))
        assertTrue(ChinaWorkCalendar.isHoliday(2026, 2, 17))
    }

    @Test
    fun `plain weekend and plain weekday`() {
        assertFalse(ChinaWorkCalendar.isWorkday(2026, 11, 28)) // 周六
        assertTrue(ChinaWorkCalendar.isWorkday(2026, 11, 25))  // 周三
    }

    @Test
    fun `year without data falls back to monday-to-friday`() {
        assertFalse(ChinaWorkCalendar.hasData(2030))
        assertTrue(ChinaWorkCalendar.isWorkday(2030, 6, 3))   // 周一
        assertFalse(ChinaWorkCalendar.isWorkday(2030, 6, 1))  // 周六
    }

    // ---------- 各种重复规则 ----------

    @Test
    fun `weekdays skips weekend but ignores makeup workday`() {
        val w = workflow(type = ScheduleType.WEEKDAYS)
        assertFalse(w.matchesDay(cal(2026, 9, 20)))  // 周日补班，周一至周五模式不执行
        assertTrue(w.matchesDay(cal(2026, 9, 21)))
        assertFalse(w.matchesDay(cal(2026, 9, 26)))
    }

    @Test
    fun `workdays follows holiday calendar`() {
        val w = workflow(type = ScheduleType.WORKDAYS)
        assertTrue(w.matchesDay(cal(2026, 9, 20)))   // 周日补班 → 要执行
        assertTrue(w.matchesDay(cal(2026, 10, 8)))   // 节后首个工作日
        assertFalse(w.matchesDay(cal(2026, 10, 1)))  // 国庆
        assertFalse(w.matchesDay(cal(2026, 10, 5)))
    }

    @Test
    fun `custom mask maps monday to bit0`() {
        val mondayOnly = workflow(type = ScheduleType.CUSTOM, mask = 0b0000001)
        assertTrue(mondayOnly.matchesDay(cal(2026, 11, 23)))   // 周一
        assertFalse(mondayOnly.matchesDay(cal(2026, 11, 24)))  // 周二
        assertFalse(mondayOnly.matchesDay(cal(2026, 11, 29)))  // 周日
    }

    @Test
    fun `custom mask sunday maps to bit6`() {
        val sundayOnly = workflow(type = ScheduleType.CUSTOM, mask = 0b1000000)
        assertTrue(sundayOnly.matchesDay(cal(2026, 11, 29)))
        assertFalse(sundayOnly.matchesDay(cal(2026, 11, 28)))
    }

    @Test
    fun `daily always matches`() {
        val w = workflow(type = ScheduleType.DAILY)
        assertTrue(w.matchesDay(cal(2026, 10, 1)))
        assertTrue(w.matchesDay(cal(2026, 11, 28)))
    }

    // ---------- 下一次触发时刻 ----------

    @Test
    fun `same day future time triggers today`() {
        val w = workflow(hour = 9, minute = 30, type = ScheduleType.DAILY)
        val next = w.nextTriggerAt(cal(2026, 11, 25, 8, 0).timeInMillis)!!
        assertEquals("2026-11-25 09:30", fmt(next))
    }

    @Test
    fun `past time rolls over to tomorrow`() {
        val w = workflow(hour = 7, minute = 0, type = ScheduleType.DAILY)
        val next = w.nextTriggerAt(cal(2026, 11, 25, 8, 0).timeInMillis)!!
        assertEquals("2026-11-26 07:00", fmt(next))
    }

    @Test
    fun `workdays jumps over national day holiday`() {
        val w = workflow(type = ScheduleType.WORKDAYS)
        // 2026-09-30 晚 → 10/1~10/7 放假，10/8 周四上班
        val next = w.nextTriggerAt(cal(2026, 9, 30, 20, 0).timeInMillis)!!
        assertEquals("2026-10-08 07:00", fmt(next))
    }

    @Test
    fun `workdays runs on makeup sunday`() {
        val w = workflow(type = ScheduleType.WORKDAYS)
        val next = w.nextTriggerAt(cal(2026, 9, 19, 20, 0).timeInMillis)!!
        assertEquals("2026-09-20 07:00", fmt(next))  // 周日补班
    }

    @Test
    fun `weekdays skips to monday`() {
        val w = workflow(type = ScheduleType.WEEKDAYS)
        val next = w.nextTriggerAt(cal(2026, 11, 27, 20, 0).timeInMillis)!!  // 周五晚
        assertEquals("2026-11-30 07:00", fmt(next))
    }

    @Test
    fun `empty custom mask returns null`() {
        val w = workflow(type = ScheduleType.CUSTOM, mask = 0)
        assertEquals(null, w.nextTriggerAt(cal(2026, 11, 25, 8, 0).timeInMillis))
    }

    @Test
    fun `describe schedule text`() {
        assertEquals("每天 07:05", workflow(hour = 7, minute = 5, type = ScheduleType.DAILY).describeSchedule())
        assertEquals("法定工作日 08:00", workflow(hour = 8, type = ScheduleType.WORKDAYS).describeSchedule())
        assertEquals(
            "周一、周三 09:00",
            workflow(hour = 9, type = ScheduleType.CUSTOM, mask = 0b0000101).describeSchedule()
        )
    }
}
