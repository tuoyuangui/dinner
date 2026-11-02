package com.guolongli.dinner.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.guolongli.dinner.core.Graph
import com.guolongli.dinner.data.sync.SyncReason

/**
 * 后台同步任务。跑的是和前台完全一样的 [com.guolongli.dinner.data.sync.SyncEngine]，
 * 所以「先拉后推、谁新谁赢」这些规则只有一份实现。
 *
 * 返回值含义：
 * - success：跑完了（哪怕有几条上传失败，也已经标成 FAILED 等用户手动重试）；
 * - retry：还有待传的、且不是授权问题，交给 WorkManager 的退避策略过一会儿再来；
 * - failure：授权失效，重试没有意义，等用户去「网盘」页重新授权。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        if (!Graph.isAuthorized()) return Result.success()

        val report = Graph.autoSync.run(SyncReason.PERIODIC)
            ?: return Result.success()

        if (report.authRequired) return Result.failure()

        // 到了最后一次重试还没推上去，就不再占用后台配额了
        val worthRetry = report.pending > 0 && runAttemptCount < MAX_RETRY
        return if (worthRetry) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME_PERIODIC = "dinner-sync-periodic"
        const val WORK_NAME_ONCE = "dinner-sync-once"

        /** 周期任务失败时的重试上限，超过就等下一个周期 */
        const val MAX_RETRY = 3
    }
}
