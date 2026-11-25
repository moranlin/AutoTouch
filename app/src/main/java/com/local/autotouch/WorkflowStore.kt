package com.local.autotouch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WorkflowStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("autotouch_workflows", Context.MODE_PRIVATE)

    fun load(): MutableList<Workflow> {
        val raw = sp.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { toWorkflow(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<Workflow>) {
        val arr = JSONArray()
        list.forEach { arr.put(fromWorkflow(it)) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun find(id: Long): Workflow? = load().firstOrNull { it.id == id }

    fun upsert(workflow: Workflow) {
        val list = load()
        val index = list.indexOfFirst { it.id == workflow.id }
        if (index >= 0) list[index] = workflow else list.add(workflow)
        save(list)
    }

    fun delete(id: Long) {
        save(load().filterNot { it.id == id })
    }

    private fun fromWorkflow(w: Workflow) = JSONObject().apply {
        put("id", w.id)
        put("name", w.name)
        put("enabled", w.enabled)
        put("pkg", w.targetPackage)
        put("hour", w.hour)
        put("minute", w.minute)
        put("schedule", w.scheduleType.name)
        put("mask", w.weekdaysMask)
        put("launchDelay", w.launchDelayMs)
        put("steps", JSONArray().apply {
            w.steps.forEach { s ->
                put(JSONObject().apply {
                    put("type", s.type.name)
                    put("x", s.x)
                    put("y", s.y)
                    put("delay", s.delayAfterMs)
                })
            }
        })
    }

    private fun toWorkflow(o: JSONObject): Workflow {
        val steps = mutableListOf<Step>()
        o.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                steps.add(
                    Step(
                        type = runCatching {
                            StepType.valueOf(s.optString("type", StepType.TAP.name))
                        }.getOrDefault(StepType.TAP),
                        x = s.optInt("x"),
                        y = s.optInt("y"),
                        delayAfterMs = s.optLong("delay", 800L)
                    )
                )
            }
        }
        return Workflow(
            id = o.optLong("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled"),
            targetPackage = o.optString("pkg"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            scheduleType = runCatching {
                ScheduleType.valueOf(o.optString("schedule"))
            }.getOrDefault(ScheduleType.WORKDAYS),
            weekdaysMask = o.optInt("mask", Workflow.WEEKDAY_BITS),
            launchDelayMs = o.optLong("launchDelay", 3000L),
            steps = steps
        )
    }

    private companion object {
        const val KEY = "list"
    }
}
