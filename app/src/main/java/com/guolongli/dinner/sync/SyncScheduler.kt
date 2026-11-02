package com.guolongli.dinner.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.guolongli.dinner.data.sync.AppSyncPrefs
import com.guolongli.dinner.data.sync.SyncPrefs
import java.util.concurrent.TimeUnit

/**
 * 把「多久同步一次、什么网络下同步」翻译成 WorkManager 的周期任务。
 *
 * 三个细节：
 * - 用 `UPDATE` 而不是 `REPLACE`：改间隔时保留已有的排期，不会因为反复点开关把定时器重置掉；
 * - 周期任务的退避从 30 秒起指数增长，断网时不会把电量烧掉；
 * - 关掉自动同步要显式 cancel，否则旧任务还会继续跑。
 */
class SyncScheduler(
    private val context: Context,
    private val prefs: SyncPrefs,
) {

    fun reschedule() {
        val manager = WorkManager.getInstance(context)
        if (!prefs.autoEnabled) {
            manager.cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
            return
        }

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            prefs.intervalMinutes.coerceAtLeast(AppSyncPrefs.MIN_INTERVAL).toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        manager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
    }

    /** 只在 Wi-Fi 下传，还是只要有网就传 */
    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()
}
