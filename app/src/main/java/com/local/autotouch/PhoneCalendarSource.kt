package com.local.autotouch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 从手机自带的日历里读取中国法定节假日，作为离线可用的一层数据。
 *
 * 只认两类日历：名称含「节假日 / 假期 / 法定 / 假日 / Holiday / 中国」的，
 * 且只看全天事件，避免把用户自己的日程当成放假。
 *
 * 事件标题的判定：
 * - 含「班」→ 调休补班（上班）
 * - 含「休 / 放假 / 假期」或是法定节日名（元旦、春节…国庆）→ 放假
 * - 其它一律忽略
 */
object PhoneCalendarSource {

    private val CALENDAR_NAME_HINTS = listOf(
        "节假日", "假期", "法定", "假日", "节日",
        "Holiday", "holiday", "HOLIDAY", "中国", "China", "china"
    )

    private val FESTIVALS = listOf(
        "元旦", "春节", "除夕", "清明", "劳动节", "五一",
        "端午", "中秋", "国庆", "重阳", "腊八", "小年"
    )

    data class Result(
        /** 是否找到了节假日日历。 */
        val found: Boolean,
        /** 命中的日历名称，便于界面展示。 */
        val calendars: List<String>,
        /** key = yyyy-MM-dd，value = true 表示上班（补班），false 表示放假。 */
        val overrides: Map<String, Boolean>
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun read(context: Context, years: IntRange): Result {
        if (!hasPermission(context)) {
            return Result(found = false, calendars = emptyList(), overrides = emptyMap())
        }

        val holidayCalendars = findHolidayCalendars(context)
        if (holidayCalendars.isEmpty()) {
            return Result(found = false, calendars = emptyList(), overrides = emptyMap())
        }

        val overrides = readAllDayEvents(context, holidayCalendars.keys.toList(), years)
        return Result(
            found = true,
            calendars = holidayCalendars.values.toList(),
            overrides = overrides
        )
    }

    private fun findHolidayCalendars(context: Context): Map<Long, String> {
        val result = LinkedHashMap<Long, String>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (CALENDAR_NAME_HINTS.any { name.contains(it, ignoreCase = true) }) {
                        result[cursor.getLong(idIndex)] = name
                    }
                }
            }
        }
        return result
    }

    private fun readAllDayEvents(
        context: Context,
        calendarIds: List<Long>,
        years: IntRange
    ): Map<String, Boolean> {
        val utc = TimeZone.getTimeZone("UTC")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val dayMs = 24L * 60 * 60 * 1000

        val startMillis = utcMillis(years.first, 1, 1, utc)
        val endMillis = utcMillis(years.last + 1, 1, 1, utc)

        // 用 Instances 而不是 Events：节假日日历里常有「每年重复」的事件，只有 Instances 会展开
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val selection = calendarIds.joinToString(" OR ") {
            "${CalendarContract.Instances.CALENDAR_ID} = $it"
        } + " AND ${CalendarContract.Instances.ALL_DAY} = 1"

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        val overrides = HashMap<String, Boolean>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)

                while (cursor.moveToNext()) {
                    val workday = classifyTitle(cursor.getString(titleIndex)) ?: continue
                    var day = cursor.getLong(beginIndex)
                    val end = cursor.getLong(endIndex)
                    var guard = 0
                    while (day < end && guard++ < 400) {
                        overrides[dateFormat.format(Date(day))] = workday
                        day += dayMs
                    }
                }
            }
        }
        return overrides
    }

    /** 返回 null 表示这条事件与本功能无关，忽略。 */
    internal fun classifyTitle(title: String?): Boolean? {
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 「班」优先：像「国庆节补班」这种同时含节日名的补班事件必须判为上班
        if (text.contains("班")) return true
        if (text.contains("休") || text.contains("放假")) return false
        if (FESTIVALS.any { text.contains(it) }) return false
        return null
    }

    private fun utcMillis(year: Int, month: Int, day: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
}
