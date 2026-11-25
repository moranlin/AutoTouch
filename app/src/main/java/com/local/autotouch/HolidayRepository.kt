package com.local.autotouch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 自动获取中国法定节假日数据。
 *
 * 数据源：https://github.com/NateScarlet/holiday-cn （整理自国务院办公厅整改通知）
 * 每年 11 月前后国务院公布次年安排，之后自动拉取即可生效，无需升级 App。
 *
 * 拉取结果落盘缓存，断网时沿用旧数据；再不行还有内置表和农历推算兜底。
 */
object HolidayRepository {

    private const val FILE_NAME = "holiday_overrides.json"
    /**
     * 多镜像：GitHub raw 在国内经常连不通，jsDelivr 一般可用。
     * 按顺序尝试，第一个成功的立即采用。
     */
    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master",
        "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master",
        "https://gcore.jsdelivr.net/gh/NateScarlet/holiday-cn@master",
        "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master"
    )

    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 6000

    /** 整个刷新（多年份 × 多镜像）的总时限，防止网络半死时后台长时间占用。 */
    private const val TOTAL_DEADLINE_MS = 15_000L

    /** 上次成功的镜像下标，下次优先使用，避免每次都先等超时。 */
    @Volatile
    private var preferredMirror = 0

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var lastFetchAt: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var refreshing = false

    /** 数据更新完成后的通知，供界面刷新状态。 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    val isRefreshing: Boolean
        get() = refreshing

    /** 启动时同步读缓存，保证首次判定就有数据。 */
    fun loadCache(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            lastFetchAt = json.optLong("fetched_at")
            val days = json.optJSONObject("days") ?: return@runCatching
            val map = HashMap<String, Boolean>(days.length())
            days.keys().forEach { map[it] = days.getBoolean(it) }
            ChinaWorkCalendar.setRemoteData(map)
        }
    }

    fun needsRefresh(): Boolean =
        lastFetchAt == 0L || System.currentTimeMillis() - lastFetchAt > MAX_AGE_MS

    /**
     * 后台拉取当年及未来两年数据。完成后回调，参数表示数据是否有变化。
     */
    fun refreshAsync(context: Context, onDone: (Boolean) -> Unit = {}) {
        if (refreshing) return
        refreshing = true
        val app = context.applicationContext
        executor.execute {
            val changed = runCatching { doRefresh(app) }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                false
            }
            // 无论是否有新数据都用缓存重算一遍，保证与磁盘一致
            ChinaWorkCalendar.setRemoteData(readCachedDays(app))
            refreshing = false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onDone(changed)
                listeners.forEach { it(changed) }
            }
        }
    }

    private fun doRefresh(context: Context): Boolean {
        val currentYear = SimpleDateFormat("yyyy", Locale.US)
            .format(Date()).toInt()

        val before = readCachedDays(context)
        val merged = HashMap(before)
        var fetchedAny = false
        val deadline = System.currentTimeMillis() + TOTAL_DEADLINE_MS
        for (year in currentYear..currentYear + 2) {
            if (System.currentTimeMillis() > deadline) break
            val yearData = fetchYear(year) ?: continue
            fetchedAny = true
            merged.putAll(yearData)
        }
        if (!fetchedAny) {
            lastError = "网络不可用"
            return false
        }

        val changed = merged != before
        lastError = null
        lastFetchAt = System.currentTimeMillis()
        writeCache(context, merged)
        return changed
    }

    private sealed class FetchResult {
        class Ok(val data: Map<String, Boolean>) : FetchResult()
        /** 该年份还没发布，换镜像也没用，直接放弃。 */
        object NotFound : FetchResult()
        object Failed : FetchResult()
    }

    private fun fetchYear(year: Int): Map<String, Boolean>? {
        val order = MIRRORS.indices.sortedBy { if (it == preferredMirror) 0 else 1 }
        for (index in order) {
            when (val result = fetchFrom(MIRRORS[index], year)) {
                is FetchResult.Ok -> {
                    preferredMirror = index
                    return result.data
                }
                is FetchResult.NotFound -> return null
                is FetchResult.Failed -> continue
            }
        }
        return null
    }

    private fun fetchFrom(baseUrl: String, year: Int): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl/$year.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 404) return FetchResult.NotFound
            if (code != 200) {
                return FetchResult.Failed
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val days = JSONObject(text).optJSONArray("days") ?: return FetchResult.Failed
            val map = HashMap<String, Boolean>()
            for (i in 0 until days.length()) {
                val day = days.optJSONObject(i) ?: continue
                val date = day.optString("date")
                if (date.isEmpty()) continue
                // 数据源用 isOffDay；本地约定 true = 上班
                map[date] = !day.optBoolean("isOffDay", false)
            }
            FetchResult.Ok(map)
        } catch (e: Exception) {
            FetchResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCachedDays(context: Context): HashMap<String, Boolean> {
        val result = HashMap<String, Boolean>()
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return result
        runCatching {
            val days = JSONObject(file.readText()).optJSONObject("days") ?: return result
            days.keys().forEach { result[it] = days.getBoolean(it) }
        }
        return result
    }

    private fun writeCache(context: Context, days: Map<String, Boolean>) {
        val daysJson = JSONObject()
        days.forEach { (k, v) -> daysJson.put(k, v) }
        val root = JSONObject()
            .put("fetched_at", lastFetchAt)
            .put("days", daysJson)
        runCatching { File(context.filesDir, FILE_NAME).writeText(root.toString()) }
    }
}
