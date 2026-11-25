package com.local.autotouch

import java.util.Calendar
import java.util.Locale

/**
 * 中国法定工作日 / 节假日判定。
 *
 * 判定顺序（前者优先）：
 * 1. 手机自带日历里明确标注的日期（[PhoneCalendarSource]，离线可用，与用户所见一致）
 * 2. 网络自动拉取的官方数据（[HolidayRepository]，含节假日与调休补班）
 * 3. 内置官方数据（2024-2027，来源国务院通知 / holiday-cn）
 * 4. 按内置农历表推算的法定节假日（春节、端午、中秋、清明，覆盖 2000-2099）
 * 5. 兜底：周一至周五上班
 *
 * 「周几」直接用 java.util.Calendar 计算，无需任何数据。
 */
object ChinaWorkCalendar {

    /** 内置数据。key = yyyy-MM-dd，value = true 表示这天要上班（调休补班），false 表示放假。 */
    private val BUNDLED: Map<String, Boolean> = mapOf(

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

    /** 2000..2099 每年春节、端午、中秋的公历月日，位打包：
     *  bits 26-23 春节月 | 22-18 春节日 | 17-14 端午月 | 13-9 端午日 | 8-5 中秋月 | 4-0 中秋日 */
    private val LUNAR_TABLE = intArrayOf(
        0x1158D2C, 0x0E1B341, 0x1319F35, 0x105892B, 0x0D9AD3C, 0x1259732, 0x0F57F46, 0x149A739, 0x11D912E, 0x0E97943,
        0x139A136, 0x10D8D2C, 0x0DDAF3E, 0x1299933, 0x0FD8528, 0x14DA93B, 0x121932F, 0x0F17D44, 0x141A538, 0x1158F2D,
        0x0E5B341, 0x1319D35, 0x105872A, 0x0D9AD3D, 0x1299531, 0x0F57F46, 0x145A739, 0x119932F, 0x0E97943, 0x135A136,
        0x10D8B2C, 0x0DDB141, 0x12D9933, 0x0FD8328, 0x14DA93B, 0x1219530, 0x0F17D44, 0x13DA538, 0x1118F2D, 0x0E17742,
        0x1319D34, 0x105872A, 0x0D9AD3C, 0x1299731, 0x0F97F45, 0x145A739, 0x119912F, 0x0E97B44, 0x1399F36, 0x109892B,
        0x0DDAF3E, 0x12D9B33, 0x1058327, 0x14DA93A, 0x1219530, 0x0F17D45, 0x13DA338, 0x1118D2D, 0x0E1B342, 0x1319D35,
        0x1098729, 0x0D5AD3C, 0x1259731, 0x0F58346, 0x145A739, 0x115912F, 0x0E97943, 0x139A137, 0x10D892B, 0x0DDAF3D,
        0x12D9B33, 0x0FD8528, 0x14DA93A, 0x11D9530, 0x0ED7D45, 0x13DA338, 0x1158D2C, 0x0E1B141, 0x1319D34, 0x109892A,
        0x0D9AD3C, 0x1259731, 0x0F58346, 0x145A73A, 0x1198F2E, 0x0E97743, 0x1399F36, 0x10D8B2B, 0x0E1AF3D, 0x1299B32,
        0x0F98528, 0x149AB3B, 0x11D9330, 0x0ED7B45, 0x13DA338, 0x1158D2D, 0x0E5B13E, 0x1319D34, 0x1058929, 0x0D5AF3D,
    )

    private const val FIRST_YEAR = 2000

    /** 内置数据覆盖的年份。 */
    val bundledYears: IntRange = 2024..2027

    /** 网络拉取的覆盖项，由 [HolidayRepository] 注入。 */
    @Volatile
    private var remote: Map<String, Boolean> = emptyMap()

    @Volatile
    private var remoteYears: Set<Int> = emptySet()

    /** 手机日历读到的覆盖项。 */
    @Volatile
    private var phone: Map<String, Boolean> = emptyMap()

    /** 推算出的法定节假日，按年缓存；nextTriggerAt 会循环上百次，不能每次重算。 */
    private val computedHolidays = HashMap<Int, Set<String>>()

    fun setPhoneCalendarData(overrides: Map<String, Boolean>) {
        phone = overrides
        computedHolidays.clear()
    }

    fun setRemoteData(overrides: Map<String, Boolean>) {
        remote = overrides
        remoteYears = overrides.keys.mapNotNull { it.take(4).toIntOrNull() }.toSet()
        computedHolidays.clear()
    }

    /** 当前可用的数据年份（网络 ∪ 内置），供界面展示。 */
    fun dataYears(): Set<Int> = remoteYears + bundledYears.toSet()

    /** 该年是否已有官方数据（有的话就无需再按农历推算）。 */
    fun hasData(year: Int): Boolean = year in bundledYears || year in remoteYears

    fun isWorkday(year: Int, month: Int, day: Int): Boolean {
        val key = key(year, month, day)
        phone[key]?.let { return it }
        remote[key]?.let { return it }
        BUNDLED[key]?.let { return it }
        if (hasData(year)) return isWeekday(year, month, day)
        if (computedHolidays(year).contains(key)) return false
        return isWeekday(year, month, day)
    }

    fun isHoliday(year: Int, month: Int, day: Int): Boolean = !isWorkday(year, month, day)

    /** 无官方数据时的推算结果，仅用于兜底，不含调休安排。 */
    fun computedHolidays(year: Int): Set<String> = synchronized(computedHolidays) {
        computedHolidays.getOrPut(year) {
            runCatching { computeStatutory(year) }.getOrDefault(emptySet())
        }
    }

    private fun isWeekday(year: Int, month: Int, day: Int): Boolean {
        val cal = Calendar.getInstance().apply { set(year, month - 1, day) }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    private fun key(year: Int, month: Int, day: Int) =
        String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

    // ---------------- 无官方数据时按农历推算 ----------------

    private fun computeStatutory(year: Int): Set<String> {
        val out = HashSet<String>()

        // 元旦、劳动节、国庆为固定公历日期
        out += key(year, 1, 1)
        out += key(year, 5, 1)
        out += key(year, 5, 2)
        out += key(year, 10, 1)
        out += key(year, 10, 2)
        out += key(year, 10, 3)

        // 清明按节气公式
        out += key(year, 4, qingmingDay(year))

        // 农历日期查表：春节（含除夕至初三）、端午、中秋
        val index = year - FIRST_YEAR
        if (index in LUNAR_TABLE.indices) {
            val v = LUNAR_TABLE[index]
            val spring = Triple((v shr 23) and 0xF, (v shr 18) and 0x1F, 0)
            val duanwu = Pair((v shr 14) and 0xF, (v shr 9) and 0x1F)
            val midAutumn = Pair((v shr 5) and 0xF, v and 0x1F)

            for (offset in -1..2) {
                val c = Calendar.getInstance().apply {
                    clear()
                    set(year, spring.first - 1, spring.second, 12, 0, 0)
                    add(Calendar.DAY_OF_YEAR, offset)
                }
                out += key(
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH)
                )
            }
            out += key(year, duanwu.first, duanwu.second)
            out += key(year, midAutumn.first, midAutumn.second)
        }

        return out
    }

    /** 清明所在公历日：21 世纪适用 y*0.2422+4.81 再减去闰年修正。 */
    private fun qingmingDay(year: Int): Int {
        val y = year % 100
        if (year !in 2000..2099) return 5
        val day = ((y * 0.2422) + 4.81).toInt() - (y / 4)
        return day.coerceIn(4, 6)
    }
}
