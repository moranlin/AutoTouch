package com.local.autotouch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

class WorkflowEditActivity : Activity() {

    private lateinit var store: WorkflowStore
    private var workflow = Workflow()
    private var isNew = true

    private lateinit var etName: EditText
    private lateinit var tvApp: TextView
    private lateinit var timePicker: TimePicker
    private lateinit var rgSchedule: RadioGroup
    private lateinit var llWeekdays: LinearLayout
    private lateinit var llSteps: LinearLayout
    private lateinit var etLaunchDelay: EditText
    private lateinit var tvHolidayHint: TextView
    private lateinit var weekdayBoxes: List<CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workflow_edit)
        store = WorkflowStore(this)

        val id = intent.getLongExtra(Scheduler.EXTRA_WORKFLOW_ID, -1L)
        store.find(id)?.let {
            workflow = it
            isNew = false
        }
        if (isNew) {
            workflow.id = System.currentTimeMillis()
        }
        title = if (isNew) "新建工作流" else "编辑工作流"

        bindViews()
        bindActions()
        fillFromWorkflow()
    }

    private fun bindViews() {
        etName = findViewById(R.id.etName)
        tvApp = findViewById(R.id.tvApp)
        timePicker = findViewById(R.id.timePicker)
        rgSchedule = findViewById(R.id.rgSchedule)
        llWeekdays = findViewById(R.id.llWeekdays)
        llSteps = findViewById(R.id.llSteps)
        etLaunchDelay = findViewById(R.id.etLaunchDelay)
        tvHolidayHint = findViewById(R.id.tvHolidayHint)
        weekdayBoxes = listOf(
            findViewById(R.id.cbMon),
            findViewById(R.id.cbTue),
            findViewById(R.id.cbWed),
            findViewById(R.id.cbThu),
            findViewById(R.id.cbFri),
            findViewById(R.id.cbSat),
            findViewById(R.id.cbSun)
        )
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnPickApp).setOnClickListener { showAppPicker() }
        findViewById<Button>(R.id.btnAddStep).setOnClickListener {
            addStepRow(null)
            renumberSteps()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(exitAfter = true) }
        findViewById<Button>(R.id.btnRun).setOnClickListener { save(exitAfter = false) }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        rgSchedule.setOnCheckedChangeListener { _, checkedId ->
            llWeekdays.visibility =
                if (checkedId == R.id.rbCustom) View.VISIBLE else View.GONE
            updateHolidayHint()
        }
        tvHolidayHint.visibility = View.GONE
    }

    private fun fillFromWorkflow() {
        etName.setText(workflow.name)
        tvApp.text = if (workflow.targetPackage.isBlank()) "未选择"
        else "${appLabel(workflow.targetPackage)}（${workflow.targetPackage}）"

        timePicker.setIs24HourView(true)
        timePicker.hour = workflow.hour
        timePicker.minute = workflow.minute

        rgSchedule.check(
            when (workflow.scheduleType) {
                ScheduleType.DAILY -> R.id.rbDaily
                ScheduleType.WEEKDAYS -> R.id.rbWeekdays
                ScheduleType.WORKDAYS -> R.id.rbWorkdays
                ScheduleType.CUSTOM -> R.id.rbCustom
            }
        )
        llWeekdays.visibility =
            if (workflow.scheduleType == ScheduleType.CUSTOM) View.VISIBLE else View.GONE
        weekdayBoxes.forEachIndexed { index, box ->
            box.isChecked = (workflow.weekdaysMask shr index) and 1 == 1
        }

        etLaunchDelay.setText(workflow.launchDelayMs.toString())

        llSteps.removeAllViews()
        if (workflow.steps.isEmpty()) addStepRow(null)
        else workflow.steps.forEach { addStepRow(it) }
        renumberSteps()

        findViewById<Button>(R.id.btnDelete).visibility =
            if (isNew) View.GONE else View.VISIBLE

        updateHolidayHint()
    }

    private fun updateHolidayHint() {
        if (rgSchedule.checkedRadioButtonId != R.id.rbWorkdays) {
            tvHolidayHint.visibility = View.GONE
            return
        }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        if (ChinaWorkCalendar.hasData(year)) {
            tvHolidayHint.visibility = View.GONE
        } else {
            tvHolidayHint.text =
                "注意：${ChinaWorkCalendar.coveredYears.first}-${ChinaWorkCalendar.coveredYears.last} " +
                    "年以外的节假日数据未内置，将按周一至周五粗略判断。"
            tvHolidayHint.visibility = View.VISIBLE
        }
    }

    private fun addStepRow(step: Step?) {
        val row = layoutInflater.inflate(R.layout.item_step, llSteps, false)
        row.findViewById<EditText>(R.id.etX).setText(step?.x?.toString() ?: "")
        row.findViewById<EditText>(R.id.etY).setText(step?.y?.toString() ?: "")
        row.findViewById<EditText>(R.id.etDelay).setText(step?.delayAfterMs?.toString() ?: "800")
        row.findViewById<Button>(R.id.btnRemove).setOnClickListener {
            llSteps.removeView(row)
            renumberSteps()
        }
        llSteps.addView(row)
    }

    private fun renumberSteps() {
        for (i in 0 until llSteps.childCount) {
            llSteps.getChildAt(i).findViewById<TextView>(R.id.tvIndex).text = "${i + 1}."
        }
    }

    private fun collectSteps(): List<Step>? {
        val result = mutableListOf<Step>()
        for (i in 0 until llSteps.childCount) {
            val row = llSteps.getChildAt(i)
            val x = row.findViewById<EditText>(R.id.etX).text.toString().trim().toIntOrNull()
            val y = row.findViewById<EditText>(R.id.etY).text.toString().trim().toIntOrNull()
            val delay = row.findViewById<EditText>(R.id.etDelay).text.toString().trim()
                .toLongOrNull() ?: 800L
            if (x == null || y == null) {
                toast("第 ${i + 1} 步的 X / Y 必须填整数")
                return null
            }
            result.add(Step(x, y, delay))
        }
        return result
    }

    private fun save(exitAfter: Boolean): Boolean {
        val steps = collectSteps() ?: return false
        if (steps.isEmpty()) {
            toast("至少需要 1 个点击步骤")
            return false
        }
        if (workflow.targetPackage.isBlank()) {
            toast("请先选择目标应用")
            return false
        }
        val launchDelay = etLaunchDelay.text.toString().trim().toLongOrNull()
        if (launchDelay == null || launchDelay < 0) {
            toast("「打开应用后等待」必须是非负整数毫秒")
            return false
        }

        var mask = 0
        weekdayBoxes.forEachIndexed { index, box ->
            if (box.isChecked) mask = mask or (1 shl index)
        }
        val type = when (rgSchedule.checkedRadioButtonId) {
            R.id.rbDaily -> ScheduleType.DAILY
            R.id.rbWeekdays -> ScheduleType.WEEKDAYS
            R.id.rbWorkdays -> ScheduleType.WORKDAYS
            else -> ScheduleType.CUSTOM
        }
        if (type == ScheduleType.CUSTOM && mask == 0) {
            toast("自定义模式下至少要选一天")
            return false
        }

        workflow.name = etName.text.toString().trim()
        workflow.hour = timePicker.hour
        workflow.minute = timePicker.minute
        workflow.scheduleType = type
        workflow.weekdaysMask = if (mask == 0) Workflow.WEEKDAY_BITS else mask
        workflow.launchDelayMs = launchDelay
        workflow.steps = steps.toMutableList()
        if (isNew) workflow.enabled = true

        store.upsert(workflow)
        if (workflow.enabled) Scheduler.schedule(this, workflow) else Scheduler.cancel(this, workflow)

        if (exitAfter) {
            toast("已保存，下次执行：${formatNext()}")
            finish()
        } else {
            WakeService.start(this, workflow.id)
            toast("已保存，正在执行一次")
            finish()
        }
        return true
    }

    private fun formatNext(): String {
        val at = workflow.nextTriggerAt() ?: return "无"
        return java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(java.util.Date(at))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除工作流")
            .setMessage("删除后不可恢复，确定？")
            .setPositiveButton("删除") { _, _ ->
                Scheduler.cancel(this, workflow)
                store.delete(workflow.id)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }

        if (apps.isEmpty()) {
            toast("未找到可启动的应用")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which ->
                workflow.targetPackage = apps[which].second
                tvApp.text = "${apps[which].first}（${apps[which].second}）"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
