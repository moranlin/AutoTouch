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

    @Test
    fun `year without data still knows fixed-date holidays`() {
        assertFalse(ChinaWorkCalendar.isWorkday(2030, 1, 1))    // 元旦（周二）
        assertFalse(ChinaWorkCalendar.isWorkday(2030, 5, 1))    // 劳动节（周三）
        assertFalse(ChinaWorkCalendar.isWorkday(2030, 10, 1))   // 国庆（周二）
        assertTrue(ChinaWorkCalendar.isWorkday(2030, 11, 5))    // 普通周二
    }

    @Test
    fun `computed spring festival follows chinese calendar`() {
        // 官方数据：2027 春节 2/6（新月 23:56，含朔之日为初一）
        val h2027 = ChinaWorkCalendar.computedHolidays(2027)
        assertTrue("2027 除夕" , "2027-02-05" in h2027)
        assertTrue("2027 初一" , "2027-02-06" in h2027)
        assertTrue("2027 初二" , "2027-02-07" in h2027)
        assertTrue("2027 初三" , "2027-02-08" in h2027)
        assertFalse("2027-02-09" in h2027)
    }

    @Test
    fun `computed dates match known years`() {
        assertTrue("2024-02-10" in ChinaWorkCalendar.computedHolidays(2024))  // 春节
        assertTrue("2024-06-10" in ChinaWorkCalendar.computedHolidays(2024))  // 端午
        assertTrue("2024-09-17" in ChinaWorkCalendar.computedHolidays(2024))  // 中秋
        assertTrue("2028-01-26" in ChinaWorkCalendar.computedHolidays(2028))  // 春节
        assertTrue("2028-05-28" in ChinaWorkCalendar.computedHolidays(2028))  // 端午
        assertTrue("2028-10-03" in ChinaWorkCalendar.computedHolidays(2028))  // 中秋
    }

    @Test
    fun `computed festival falls back when no official data`() {
        // 2029 无官方数据 → 春节不执行
        assertFalse(ChinaWorkCalendar.hasData(2029))
        assertFalse(ChinaWorkCalendar.isWorkday(2029, 2, 13))  // 2029 春节
        assertTrue(ChinaWorkCalendar.isWorkday(2029, 3, 1))
    }

    @Test
    fun `qingming is computed for every year`() {
        assertEquals("2024-04-04", "2024-04-04")
        assertTrue("2024-04-04" in ChinaWorkCalendar.computedHolidays(2024))
        assertTrue("2025-04-04" in ChinaWorkCalendar.computedHolidays(2025))
        assertTrue("2026-04-05" in ChinaWorkCalendar.computedHolidays(2026))
        assertTrue("2027-04-05" in ChinaWorkCalendar.computedHolidays(2027))
    }

    // ---------- 手机日历标题解析 ----------

    @Test
    fun `calendar title with ban means makeup workday`() {
        assertEquals(true, PhoneCalendarSource.classifyTitle("国庆节补班"))
        assertEquals(true, PhoneCalendarSource.classifyTitle("10月2日上班"))
        assertEquals(true, PhoneCalendarSource.classifyTitle("春节调休上班"))
    }

    @Test
    fun `calendar title with festival or xiu means holiday`() {
        assertEquals(false, PhoneCalendarSource.classifyTitle("春节"))
        assertEquals(false, PhoneCalendarSource.classifyTitle("国庆节"))
        assertEquals(false, PhoneCalendarSource.classifyTitle("放假"))
        assertEquals(false, PhoneCalendarSource.classifyTitle("中秋节休息"))
        assertEquals(false, PhoneCalendarSource.classifyTitle("元旦"))
    }

    @Test
    fun `unrelated calendar titles are ignored`() {
        assertEquals(null, PhoneCalendarSource.classifyTitle("项目评审"))
        assertEquals(null, PhoneCalendarSource.classifyTitle(""))
        assertEquals(null, PhoneCalendarSource.classifyTitle(null))
    }

    @Test
    fun `phone calendar data takes precedence over remote`() {
        ChinaWorkCalendar.setRemoteData(mapOf("2026-10-02" to false))
        ChinaWorkCalendar.setPhoneCalendarData(mapOf("2026-10-02" to true))
        try {
            assertTrue(ChinaWorkCalendar.isWorkday(2026, 10, 2))
        } finally {
            ChinaWorkCalendar.setPhoneCalendarData(emptyMap())
            ChinaWorkCalendar.setRemoteData(emptyMap())
        }
        assertTrue(ChinaWorkCalendar.isHoliday(2026, 10, 2))
    }

    @Test
    fun `remote data overrides bundled table`() {
        ChinaWorkCalendar.setRemoteData(mapOf("2026-10-01" to true, "2026-10-10" to false))
        try {
            assertTrue(ChinaWorkCalendar.isWorkday(2026, 10, 1))    // 被改成上班
            assertTrue(ChinaWorkCalendar.isHoliday(2026, 10, 10))   // 被改成放假
            assertTrue(ChinaWorkCalendar.dataYears().contains(2026))
        } finally {
            ChinaWorkCalendar.setRemoteData(emptyMap())
        }
        // 恢复内置表后应为原值
        assertTrue(ChinaWorkCalendar.isHoliday(2026, 10, 1))
    }

    @Test
    fun `remote data enables years outside bundled range`() {
        ChinaWorkCalendar.setRemoteData(mapOf("2028-02-01" to false))
        try {
            assertTrue(ChinaWorkCalendar.hasData(2028))
            assertTrue(ChinaWorkCalendar.isHoliday(2028, 2, 1))
        } finally {
            ChinaWorkCalendar.setRemoteData(emptyMap())
        }
        assertFalse(ChinaWorkCalendar.hasData(2028))
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
