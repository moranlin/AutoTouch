package com.local.autotouch

import android.app.Application

/**
 * 启动时载入节假日缓存，并在数据过期时后台自动更新。
 * 更新成功后重排所有闹钟，让工作日判定立即生效。
 */
class AutoTouchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        HolidayRepository.loadCache(this)
        loadPhoneCalendar()

        if (HolidayRepository.needsRefresh()) {
            HolidayRepository.refreshAsync(this) { changed ->
                if (changed) Scheduler.rescheduleAll(this)
            }
        }
    }

    /** 读手机日历作为离线数据源；没有权限或没有节假日日历就静默跳过。 */
    private fun loadPhoneCalendar() {
        if (!PhoneCalendarSource.hasPermission(this)) return
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val result = PhoneCalendarSource.read(this, year..year + 1)
        ChinaWorkCalendar.setPhoneCalendarData(result.overrides)
    }
}
