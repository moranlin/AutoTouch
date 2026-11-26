package com.local.autotouch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Spinner
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

    override fun onResume() {
        super.onResume()
        consumePickedCoordinate()
    }

    /** 从取点服务回来后，把坐标替回当初按下「取点」的那一行。 */
    private fun consumePickedCoordinate() {
        val pick = PickStore(this)
        when (pick.status) {
            PickStore.STATUS_DONE -> {
                val sameWorkflow = pick.workflowId == workflow.id
                val index = pick.stepIndex
                val x = pick.x
                val y = pick.y
                pick.clear()
                if (sameWorkflow && index in 0 until llSteps.childCount) {
                    val row = llSteps.getChildAt(index)
                    row.findViewById<EditText>(R.id.etX).setText(x.toString())
                    row.findViewById<EditText>(R.id.etY).setText(y.toString())
                    toast("已填入坐标 ($x, $y)")
                }
            }
            PickStore.STATUS_PENDING -> {
                // 用户回到本页但没取点，说明放弃了，收掉悬浮球
                stopService(Intent(this, CoordPickerService::class.java))
                pick.clear()
            }
        }
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
            val years = ChinaWorkCalendar.dataYears()
            val range = if (years.isEmpty()) "无" else "${years.min()}-${years.max()}"
            tvHolidayHint.text =
                "注意：$year 年的官方放假安排尚未获取（现有数据覆盖 $range 年），" +
                    "将按农历推算的法定节假日近似判断，调休补班可能不准。"
            tvHolidayHint.visibility = View.VISIBLE
        }
    }

    private fun addStepRow(step: Step?) {
        val row = layoutInflater.inflate(R.layout.item_step, llSteps, false)
        row.findViewById<EditText>(R.id.etX).setText(step?.x?.toString() ?: "")
        row.findViewById<EditText>(R.id.etY).setText(step?.y?.toString() ?: "")
        row.findViewById<EditText>(R.id.etDelay).setText(step?.delayAfterMs?.toString() ?: "800")

        val spinner = row.findViewById<Spinner>(R.id.spType)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("点击坐标", "退出应用")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(if (step?.type == StepType.EXIT_APP) 1 else 0)

        val tapFields = row.findViewById<View>(R.id.llTapFields)
        val pickButton = row.findViewById<Button>(R.id.btnPick)
        val killBox = row.findViewById<CheckBox>(R.id.cbKillBg)
        killBox.isChecked = step?.killBackground ?: true
        fun syncFields() {
            val isTap = spinner.selectedItemPosition == 0
            tapFields.visibility = if (isTap) View.VISIBLE else View.GONE
            pickButton.visibility = if (isTap) View.VISIBLE else View.GONE
            killBox.visibility = if (isTap) View.GONE else View.VISIBLE
        }
        syncFields()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) =
                syncFields()

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        row.findViewById<Button>(R.id.btnRemove).setOnClickListener {
            llSteps.removeView(row)
            renumberSteps()
        }
        row.findViewById<Button>(R.id.btnPick).setOnClickListener {
            startPick(llSteps.indexOfChild(row))
        }
        if (step?.type == StepType.EXIT_APP) renumberSteps()
        llSteps.addView(row)
    }

    private fun startPick(index: Int) {
        if (index < 0) return
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("取点功能要在其他应用上层显示悬浮球。请在接下来的设置页里允许「显示在其他应用上层」。")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 先落库，保证取点回来时能对上工作流
        PickStore(this).begin(workflow.id, index)
        CoordPickerService.start(this, taskId)
        moveTaskToBack(true)
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
            val type = if (row.findViewById<Spinner>(R.id.spType).selectedItemPosition == 1) {
                StepType.EXIT_APP
            } else {
                StepType.TAP
            }
            val delay = row.findViewById<EditText>(R.id.etDelay).text.toString().trim()
                .toLongOrNull() ?: 800L

            if (type == StepType.EXIT_APP) {
                val kill = row.findViewById<CheckBox>(R.id.cbKillBg).isChecked
                result.add(Step(type = type, delayAfterMs = delay, killBackground = kill))
                continue
            }

            val x = row.findViewById<EditText>(R.id.etX).text.toString().trim().toIntOrNull()
            val y = row.findViewById<EditText>(R.id.etY).text.toString().trim().toIntOrNull()
            if (x == null || y == null) {
                toast("第 ${i + 1} 步的 X / Y 必须填整数（或把类型改为「退出应用」）")
                return null
            }
            result.add(Step(StepType.TAP, x, y, delay))
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
