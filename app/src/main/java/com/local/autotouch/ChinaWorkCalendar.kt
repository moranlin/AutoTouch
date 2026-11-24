package com.local.autotouch

import java.util.Calendar
import java.util.Locale

/**
 * 中国法定工作日 / 节假日判定。
 *
 * 数据来源：国务院办公厅每年发布的放假安排通知，
 * 整理自 https://github.com/NateScarlet/holiday-cn
 * 只记录与「周一至周五上班、周末休息」默认规则不一致的日期，所以表很小。
 * 国务院一般在每年 11 月公布次年安排，届时补上对应年份的条目即可。
 */
object ChinaWorkCalendar {

    /** key = yyyy-MM-dd，value = true 表示这天要上班（调休补班），false 表示放假。 */
    private val OVERRIDES: Map<String, Boolean> = mapOf(

        // ===== 2024 年 =====
        // https://www.gov.cn/zhengce/zhengceku/202310/content_6911528.htm
        "2024-01-01" to false, // 元旦·放假
        "2024-02-04" to true, // 春节·调休补班
        "2024-02-12" to false, // 春节·放假
        "2024-02-13" to false, // 春节·放假
        "2024-02-14" to false, // 春节·放假
        "2024-02-15" to false, // 春节·放假
        "2024-02-16" to false, // 春节·放假
        "2024-02-18" to true, // 春节·调休补班
        "2024-04-04" to false, // 清明节·放假
        "2024-04-05" to false, // 清明节·放假
        "2024-04-07" to true, // 清明节·调休补班
        "2024-04-28" to true, // 劳动节·调休补班
        "2024-05-01" to false, // 劳动节·放假
        "2024-05-02" to false, // 劳动节·放假
        "2024-05-03" to false, // 劳动节·放假
        "2024-05-11" to true, // 劳动节·调休补班
        "2024-06-10" to false, // 端午节·放假
        "2024-09-14" to true, // 中秋节·调休补班
        "2024-09-16" to false, // 中秋节·放假
        "2024-09-17" to false, // 中秋节·放假
        "2024-09-29" to true, // 国庆节·调休补班
        "2024-10-01" to false, // 国庆节·放假
        "2024-10-02" to false, // 国庆节·放假
        "2024-10-03" to false, // 国庆节·放假
        "2024-10-04" to false, // 国庆节·放假
        "2024-10-07" to false, // 国庆节·放假
        "2024-10-12" to true, // 国庆节·调休补班
        // ===== 2025 年 =====
        // https://www.gov.cn/zhengce/zhengceku/202411/content_6986383.htm
        "2025-01-01" to false, // 元旦·放假
        "2025-01-26" to true, // 春节·调休补班
        "2025-01-28" to false, // 春节·放假
        "2025-01-29" to false, // 春节·放假
        "2025-01-30" to false, // 春节·放假
        "2025-01-31" to false, // 春节·放假
        "2025-02-03" to false, // 春节·放假
        "2025-02-04" to false, // 春节·放假
        "2025-02-08" to true, // 春节·调休补班
        "2025-04-04" to false, // 清明节·放假
        "2025-04-27" to true, // 劳动节·调休补班
        "2025-05-01" to false, // 劳动节·放假
        "2025-05-02" to false, // 劳动节·放假
        "2025-05-05" to false, // 劳动节·放假
        "2025-06-02" to false, // 端午节·放假
        "2025-09-28" to true, // 国庆节、中秋节·调休补班
        "2025-10-01" to false, // 国庆节、中秋节·放假
        "2025-10-02" to false, // 国庆节、中秋节·放假
        "2025-10-03" to false, // 国庆节、中秋节·放假
        "2025-10-06" to false, // 国庆节、中秋节·放假
        "2025-10-07" to false, // 国庆节、中秋节·放假
        "2025-10-08" to false, // 国庆节、中秋节·放假
        "2025-10-11" to true, // 国庆节、中秋节·调休补班
        // ===== 2026 年 =====
        // https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
        "2026-01-01" to false, // 元旦·放假
        "2026-01-02" to false, // 元旦·放假
        "2026-01-04" to true, // 元旦·调休补班
        "2026-02-14" to true, // 春节·调休补班
        "2026-02-16" to false, // 春节·放假
        "2026-02-17" to false, // 春节·放假
        "2026-02-18" to false, // 春节·放假
        "2026-02-19" to false, // 春节·放假
        "2026-02-20" to false, // 春节·放假
        "2026-02-23" to false, // 春节·放假
        "2026-02-28" to true, // 春节·调休补班
        "2026-04-06" to false, // 清明节·放假
        "2026-05-01" to false, // 劳动节·放假
        "2026-05-04" to false, // 劳动节·放假
        "2026-05-05" to false, // 劳动节·放假
        "2026-05-09" to true, // 劳动节·调休补班
        "2026-06-19" to false, // 端午节·放假
        "2026-09-20" to true, // 国庆节·调休补班
        "2026-09-25" to false, // 中秋节·放假
        "2026-10-01" to false, // 国庆节·放假
        "2026-10-02" to false, // 国庆节·放假
        "2026-10-05" to false, // 国庆节·放假
        "2026-10-06" to false, // 国庆节·放假
        "2026-10-07" to false, // 国庆节·放假
        "2026-10-10" to true, // 国庆节·调休补班
        // ===== 2027 年 =====
        // https://www.gov.cn/zhengce/zhengceku/202611/content_7085102.htm
        "2027-01-01" to false, // 元旦·放假
        "2027-01-31" to true, // 春节·调休补班
        "2027-02-05" to false, // 春节·放假
        "2027-02-08" to false, // 春节·放假
        "2027-02-09" to false, // 春节·放假
        "2027-02-10" to false, // 春节·放假
        "2027-02-11" to false, // 春节·放假
        "2027-02-12" to false, // 春节·放假
        "2027-02-14" to true, // 春节·调休补班
        "2027-04-05" to false, // 清明节·放假
        "2027-05-03" to false, // 劳动节·放假
        "2027-05-04" to false, // 劳动节·放假
        "2027-05-05" to false, // 劳动节·放假
        "2027-05-08" to true, // 劳动节·调休补班
        "2027-06-09" to false, // 端午节·放假
        "2027-09-15" to false, // 中秋节·放假
        "2027-09-26" to true, // 国庆节·调休补班
        "2027-10-01" to false, // 国庆节·放假
        "2027-10-04" to false, // 国庆节·放假
        "2027-10-05" to false, // 国庆节·放假
        "2027-10-06" to false, // 国庆节·放假
        "2027-10-07" to false, // 国庆节·放假
        "2027-10-09" to true, // 国庆节·调休补班
    )

    /** 已录入官方数据的年份；超出范围时按「周一至周五上班」估算。 */
    val coveredYears: IntRange = 2024..2027

    fun hasData(year: Int): Boolean = year in coveredYears

    fun isWorkday(year: Int, month: Int, day: Int): Boolean {
        val key = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        OVERRIDES[key]?.let { return it }
        val cal = Calendar.getInstance().apply { set(year, month - 1, day) }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    fun isHoliday(year: Int, month: Int, day: Int): Boolean = !isWorkday(year, month, day)
}

