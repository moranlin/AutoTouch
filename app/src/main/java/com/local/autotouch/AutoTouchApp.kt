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
        if (HolidayRepository.needsRefresh()) {
            HolidayRepository.refreshAsync(this) { changed ->
                if (changed) Scheduler.rescheduleAll(this)
            }
        }
    }
}
