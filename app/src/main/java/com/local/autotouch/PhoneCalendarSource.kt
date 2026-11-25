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
 * 各家 ROM 的节假日日历名称差别很大，所以先用名称识别，识别不到再按内容嗅探：
 * - 名称识别：显示名 / 账户名 / 账户类型 含「节假日 / 假期 / 法定 / Holiday / 中国 / MIUI…」
 * - 内容嗅探：该日历在一年内有 >=5 条可识别的整日事件，且跨 >=3 个不同月份
 *   （个人日程很难同时满足，避免把用户自己的安排误判成节假日表）
 *
 * 事件标题判定：
 * - 含「班」→ 调休补班（上班）
 * - 含「休 / 放假 / 假期」或是法定节日名（元旦、春节…国庆）→ 放假
 * - 其它一律忽略
 */
object PhoneCalendarSource {

    /** 名称里出现这些词就当作节假日日历。 */
    private val NAME_HINTS = listOf(
        "节假日", "假期", "法定", "假日", "节日", "调休", "放假",
        "Holiday", "Festival", "中国",
        // 小米 / MIUI / HyperOS
        "MIUI", "小米", "Xiaomi", "HyperOS",
        // 其它常见 ROM
        "EMUI", "HarmonyOS", "ColorOS", "Flyme", "OriginOS"
    )

    private val FESTIVALS = listOf(
        "元旦", "春节", "除夕", "清明", "劳动节", "五一",
        "端午", "中秋", "国庆", "重阳", "腊八", "小年"
    )

    private const val SNIFF_MIN_EVENTS = 5
    private const val SNIFF_MIN_MONTHS = 3

    /** 单个日历的扫描结果，用于界面诊断。 */
    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val visible: Boolean,
        val dayEvents: Int,
        val matchedEvents: Int,
        val matchedMonths: Int,
        val nameMatched: Boolean,
        val sniffMatched: Boolean
    ) {
        val isHolidayCalendar: Boolean get() = nameMatched || sniffMatched

        fun describe(): String {
            val flag = when {
                nameMatched -> "名称命中"
                sniffMatched -> "内容嗅探命中"
                else -> "跳过"
            }
            return "$displayName｜账户:$accountName/$accountType｜整日:$dayEvents 识别:$matchedEvents 跨月:$matchedMonths｜$flag"
        }
    }

    data class Result(
        /** 是否找到了节假日日历。 */
        val found: Boolean,
        /** 是否为用户手动指定。 */
        val manual: Boolean = false,
        /** 命中的日历名称，便于界面展示。 */
        val calendars: List<String>,
        /** key = yyyy-MM-dd，value = true 表示上班（补班），false 表示放假。 */
        val overrides: Map<String, Boolean>,
        /** 全部日历，用于诊断展示。 */
        val allCalendars: List<CalendarInfo> = emptyList()
    )

    private const val PREF_KEY = "manual_calendar_ids"

    /** 用户手动指定的日历 id 集合；空表示自动识别。 */
    fun manualIds(context: Context): Set<Long> {
        val raw = context.getSharedPreferences("autotouch_calendar", Context.MODE_PRIVATE)
            .getString(PREF_KEY, null) ?: return emptySet()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun setManualIds(context: Context, ids: Set<Long>) {
        invalidate()
        context.getSharedPreferences("autotouch_calendar", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, ids.joinToString(","))
            .apply()
    }

    @Volatile
    private var cachedResult: Result? = null

    /** 上次读取的结果；未读或已失效时返回 null。 */
    fun cached(): Result? = cachedResult

    fun invalidate() {
        cachedResult = null
    }

    /**
     * 带缓存读取：查询内容提供器较重，避免每次 onResume 都扫一遍。
     */
    fun readCached(context: Context, years: IntRange): Result {
        cachedResult?.let { return it }
        return read(context, years).also { cachedResult = it }
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun read(context: Context, years: IntRange): Result {
        if (!hasPermission(context)) {
            return Result(found = false, calendars = emptyList(), overrides = emptyMap())
        }

        val calendars = listCalendars(context)
        if (calendars.isEmpty()) {
            return Result(found = false, calendars = emptyList(), overrides = emptyMap())
        }

        val utc = TimeZone.getTimeZone("UTC")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val dayMs = 24L * 60 * 60 * 1000

        val stats = HashMap<Long, MutableStat>()
        calendars.forEach { stats[it.id] = MutableStat() }

        val pending = ArrayList<PendingEvent>()
        queryEvents(context, years, calendars.map { it.id }) { calendarId, title, begin, end, allDay ->
            val stat = stats[calendarId] ?: return@queryEvents
            // 有些 ROM 把节假日存成跨零点的定时事件，所以按「时长接近整日」也算
            val isWholeDay = allDay || end - begin >= 20 * 60 * 60 * 1000L
            if (!isWholeDay) return@queryEvents
            stat.dayEvents++

            val workday = classifyTitle(title) ?: return@queryEvents
            stat.matchedEvents++
            stat.months.add(utcMonth(begin, utc))
            pending.add(PendingEvent(calendarId, workday, begin, end))
        }

        val infos = calendars.map { cal ->
            val stat = stats[cal.id] ?: MutableStat()
            val nameMatched = matchesName(cal.displayName, cal.accountName, cal.accountType)
            val sniffMatched = !nameMatched &&
                stat.matchedEvents >= SNIFF_MIN_EVENTS &&
                stat.months.size >= SNIFF_MIN_MONTHS
            CalendarInfo(
                id = cal.id,
                displayName = cal.displayName,
                accountName = cal.accountName,
                accountType = cal.accountType,
                visible = cal.visible,
                dayEvents = stat.dayEvents,
                matchedEvents = stat.matchedEvents,
                matchedMonths = stat.months.size,
                nameMatched = nameMatched,
                sniffMatched = sniffMatched
            )
        }

        // 手动指定优先：用户明确选了哪个日历，就别再猜
        val manual = manualIds(context)
        val holidayIds = if (manual.isNotEmpty()) {
            infos.filter { it.id in manual }.map { it.id }.toSet().ifEmpty {
                infos.filter { it.isHolidayCalendar }.map { it.id }.toSet()
            }
        } else {
            infos.filter { it.isHolidayCalendar }.map { it.id }.toSet()
        }
        val overrides = HashMap<String, Boolean>()
        if (holidayIds.isNotEmpty()) {
            pending.filter { it.calendarId in holidayIds }.forEach { event ->
                var day = event.begin
                var guard = 0
                while (day < event.end && guard++ < 400) {
                    overrides[dateFormat.format(Date(day))] = event.workday
                    day += dayMs
                }
            }
        }

        val result = Result(
            found = holidayIds.isNotEmpty(),
            calendars = infos.filter { it.id in holidayIds }.map { it.displayName },
            overrides = overrides,
            allCalendars = infos,
            manual = if (manual.isNotEmpty()) holidayIds.isNotEmpty() else false
        )
        cachedResult = result
        return result
    }

    /** 诊断用：返回全部日历及其统计，便于定位机型差异。 */
    fun diagnose(context: Context, years: IntRange): List<CalendarInfo> =
        read(context, years).allCalendars

    private data class RawCalendar(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val visible: Boolean
    )

    private class MutableStat {
        var dayEvents = 0
        var matchedEvents = 0
        val months = HashSet<Int>()
    }

    private class PendingEvent(
        val calendarId: Long,
        val workday: Boolean,
        val begin: Long,
        val end: Long
    )

    private fun listCalendars(context: Context): List<RawCalendar> {
        val result = ArrayList<RawCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.DELETED} = 0",
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                val visibleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
                while (cursor.moveToNext()) {
                    result.add(
                        RawCalendar(
                            id = cursor.getLong(idIndex),
                            displayName = cursor.getString(nameIndex).orEmpty(),
                            accountName = cursor.getString(accountIndex).orEmpty(),
                            accountType = cursor.getString(typeIndex).orEmpty(),
                            visible = cursor.getInt(visibleIndex) == 1
                        )
                    )
                }
            }
        }
        return result
    }

    private fun queryEvents(
        context: Context,
        years: IntRange,
        calendarIds: List<Long>,
        onEvent: (calendarId: Long, title: String?, begin: Long, end: Long, allDay: Boolean) -> Unit
    ) {
        val utc = TimeZone.getTimeZone("UTC")
        val startMillis = utcMillis(years.first, 1, 1, utc)
        val endMillis = utcMillis(years.last + 1, 1, 1, utc)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val selection = calendarIds.joinToString(" OR ") {
            "${CalendarContract.Instances.CALENDAR_ID} = $it"
        }
        val projection = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        runCatching {
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val calIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    onEvent(
                        cursor.getLong(calIndex),
                        cursor.getString(titleIndex),
                        cursor.getLong(beginIndex),
                        cursor.getLong(endIndex),
                        cursor.getInt(allDayIndex) == 1
                    )
                }
            }
        }
    }

    private fun matchesName(
        displayName: String,
        accountName: String,
        accountType: String
    ): Boolean {
        val text = "$displayName $accountName $accountType"
        return NAME_HINTS.any { text.contains(it, ignoreCase = true) }
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

    private fun utcMonth(millis: Long, utc: TimeZone): Int =
        Calendar.getInstance(utc).apply { timeInMillis = millis }.get(Calendar.MONTH)

    private fun utcMillis(year: Int, month: Int, day: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
}
