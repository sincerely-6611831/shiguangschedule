// WorkManagerHelper.kt
package com.xingheyuzhuan.shiguangschedule.widget

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 负责调度和取消小组件相关定期任务的通用帮助类。
 */
object WorkManagerHelper {

    private const val UI_UPDATE_WORK_NAME = "WidgetUiUpdateWorker_Periodic"
    private const val FULL_DATA_SYNC_WORK_NAME = "FullDataSyncWorker_Periodic"
    private const val UNLOCK_REFRESH_WORK_NAME = "WidgetUiUpdateWorker_Unlock"

    fun enqueueUnlockRefresh(context: Context) {
        // 连续解锁时复用尚未完成的刷新，避免任务堆积。
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNLOCK_REFRESH_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetUiUpdateWorker>().build()
        )
    }

    fun schedulePeriodicWork(context: Context) {
        Log.d("WidgetWorkManager", "正在调度小组件定期任务...")

        // 调度小组件UI更新任务 (每15分钟)
        val uiUpdateWorkRequest = PeriodicWorkRequestBuilder<WidgetUiUpdateWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UI_UPDATE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            uiUpdateWorkRequest
        )

        // 调度完整数据同步任务 (每天一次)
        val fullDataSyncWorkRequest = PeriodicWorkRequestBuilder<FullDataSyncWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FULL_DATA_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            fullDataSyncWorkRequest
        )
    }

    fun cancelAllWork(context: Context) {
        // onDisabled 按小组件类型回调，仍有其他类型时应保留刷新任务。
        if (hasActiveWidgets(context)) return

        Log.d("WidgetWorkManager", "正在取消所有小组件定期任务...")
        WorkManager.getInstance(context).cancelUniqueWork(UI_UPDATE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(FULL_DATA_SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(UNLOCK_REFRESH_WORK_NAME)
    }
}
