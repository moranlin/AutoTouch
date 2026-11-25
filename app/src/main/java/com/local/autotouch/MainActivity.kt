package com.local.autotouch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: WorkflowStore
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvHoliday: TextView
    private lateinit var tvCalendar: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = WorkflowStore(this)

        container = findViewById(R.id.llWorkflows)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvAccess = findViewById(R.id.tvAccess)
        tvOverlay = findViewById(R.id.tvOverlay)
        tvBattery = findViewById(R.id.tvBattery)
        tvHoliday = findViewById(R.id.tvHoliday)
        tvCalendar = findViewById(R.id.tvCalendar)
        // 用真实屏幕尺寸而不是 App 窗口尺寸，否则高度会少掉状态栏/导航栏
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WindowManager::class.java)).defaultDisplay.getRealMetrics(real)
        findViewById<TextView>(R.id.tvScreen).text =
            "屏幕分辨率：${real.widthPixels} x ${real.heightPixels}"

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.btnRefreshHoliday).setOnClickListener { refreshHolidayData() }
        findViewById<Button>(R.id.btnCalendar).setOnClickListener { requestCalendar() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            startActivity(Intent(this, WorkflowEditActivity::class.java))
        }
    }

    private val holidayListener: (Boolean) -> Unit = {
        refreshHolidayStatus()
        renderWorkflows()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshHolidayStatus()
        refreshCalendarStatus()
        renderWorkflows()
        HolidayRepository.addListener(holidayListener)
    }

    override fun onPause() {
        HolidayRepository.removeListener(holidayListener)
        super.onPause()
    }

    private fun refreshPermissions() {
        val accessOk = isAccessibilityEnabled()
        tvAccess.text = if (accessOk) "✓ 无障碍服务已开启" else "✗ 无障碍服务未开启（必须开启）"
        tvAccess.setTextColor(if (accessOk) GREEN else RED)

        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlay.text = if (overlayOk) "✓ 悬浮窗权限已授予" else "✗ 悬浮窗权限未授予（安卓 10+ 需要）"
        tvOverlay.setTextColor(if (overlayOk) GREEN else RED)

        val batteryOk = isIgnoringBattery()
        tvBattery.text = if (batteryOk) "✓ 已忽略电池优化" else "✗ 未忽略电池优化（可能延迟触发）"
        tvBattery.setTextColor(if (batteryOk) GREEN else RED)
    }

    private fun refreshHolidayStatus() {
        val years = ChinaWorkCalendar.dataYears()
        val range = if (years.isEmpty()) "无" else "${years.min()}-${years.max()}"
        val fetched = HolidayRepository.lastFetchAt
        val error = HolidayRepository.lastError

        tvHoliday.text = when {
            fetched > 0L -> "节假日数据：已自动更新 ${
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(fetched))
            }，覆盖 $range 年"

            error != null -> "节假日数据：网络获取失败（$error），使用内置 $range 年 + 农历推算"
            HolidayRepository.isRefreshing -> "节假日数据：内置 $range 年，正在后台自动更新…"
            else -> "节假日数据：内置 $range 年（点下方按钮可联网更新）"
        }
        tvHoliday.setTextColor(if (fetched > 0L) GREEN else 0xFFB26A00.toInt())
    }

    private fun refreshCalendarStatus() {
        if (!PhoneCalendarSource.hasPermission(this)) {
            tvCalendar.text = "手机日历：未授权。授权后可离线读取节假日，作为网络数据的补充"
            tvCalendar.setTextColor(0xFFB26A00.toInt())
            return
        }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val result = PhoneCalendarSource.read(this, year..year + 1)
        tvCalendar.text = when {
            !result.found ->
                "手机日历：未找到节假日日历（小米/华为/OPPO 一般自带；原生安卓通常没有）"
            result.overrides.isEmpty() ->
                "手机日历：找到「${result.calendars.joinToString("、")}」，但没有可识别的全天事件"
            else ->
                "手机日历：已读取「${result.calendars.joinToString("、")}」共 " +
                    "${result.overrides.size} 天（含调休补班）"
        }
        tvCalendar.setTextColor(if (result.found && result.overrides.isNotEmpty()) GREEN else 0xFFB26A00.toInt())
    }

    private fun requestCalendar() {
        if (PhoneCalendarSource.hasPermission(this)) {
            reloadPhoneCalendar()
            toast("已重新读取手机日历")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
    }

    private fun reloadPhoneCalendar() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val result = PhoneCalendarSource.read(this, year..year + 1)
        ChinaWorkCalendar.setPhoneCalendarData(result.overrides)
        refreshCalendarStatus()
        renderWorkflows()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            reloadPhoneCalendar()
        } else {
            refreshCalendarStatus()
            toast("未授权日历权限，将继续使用网络/内置数据")
        }
    }

    private fun refreshHolidayData() {
        tvHoliday.text = "节假日数据：正在获取…"
        HolidayRepository.refreshAsync(this)
    }

    private fun renderWorkflows() {
        container.removeAllViews()
        // 按执行时间排序，方便一眼看清当天顺序
        val workflows = store.load().sortedWith(compareBy({ it.hour }, { it.minute }))
        tvEmpty.visibility = if (workflows.isEmpty()) View.VISIBLE else View.GONE

        workflows.forEach { workflow ->
            val row = layoutInflater.inflate(R.layout.item_workflow, container, false)
            row.findViewById<TextView>(R.id.tvName).text =
                workflow.name.ifBlank { "未命名工作流" }
            row.findViewById<TextView>(R.id.tvSchedule).text =
                "${workflow.describeSchedule()} · ${workflow.steps.size} 步 · ${appLabel(workflow.targetPackage)}"

            val next = workflow.nextTriggerAt()
            row.findViewById<TextView>(R.id.tvNext).text = when {
                !workflow.enabled -> "已关闭"
                next == null -> "无可执行日期"
                else -> "下次执行：${formatTime(next)}"
            }

            val toggle = row.findViewById<Switch>(R.id.swEnabled)
            toggle.isChecked = workflow.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                workflow.enabled = checked
                store.upsert(workflow)
                if (checked) Scheduler.schedule(this, workflow) else Scheduler.cancel(this, workflow)
                renderWorkflows()
            }

            row.setOnClickListener {
                startActivity(
                    Intent(this, WorkflowEditActivity::class.java)
                        .putExtra(Scheduler.EXTRA_WORKFLOW_ID, workflow.id)
                )
            }
            row.setOnLongClickListener {
                confirmDelete(workflow)
                true
            }
            container.addView(row)
        }
    }

    private fun confirmDelete(workflow: Workflow) {
        AlertDialog.Builder(this)
            .setTitle("删除工作流")
            .setMessage("确定删除「${workflow.name.ifBlank { "未命名工作流" }}」？")
            .setPositiveButton("删除") { _, _ ->
                Scheduler.cancel(this, workflow)
                store.delete(workflow.id)
                renderWorkflows()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "未选择应用"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AutoAccessibilityService.isRunning) return true
        val expected = "$packageName/${AutoAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (isIgnoringBattery()) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(timeMillis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val GREEN = 0xFF1B5E20.toInt()
        const val RED = 0xFFB71C1C.toInt()
        const val REQ_CALENDAR = 100
    }
}
