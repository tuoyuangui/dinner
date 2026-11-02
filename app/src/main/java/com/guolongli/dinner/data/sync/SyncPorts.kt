package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.local.OpKind

/**
 * 同步引擎只认识这两组端口：
 * 一边是本地库（Room 实现，测试里换成内存实现），一边是远端（网盘实现，测试里换成假的）。
 * 这样「先拉后推、谁新谁赢、失败怎么退避」这些规则可以在 JVM 上直接跑测试。
 */

/**
 * 本地数据本身有问题（比如记录被硬删了但发件箱还留着），
 * 重试多少次都不会好，所以单独一种异常，直接判失败。
 */
class SyncAbortException(message: String) : Exception(message)

/** 发件箱里一条待办 */
data class PendingOp(
    val seq: Long,
    val kind: OpKind,
    val targetId: String,
    val attempts: Int,
    val nextAttemptAt: Long,
)

/** 本地还没传的媒体 */
data class LocalMedia(
    val position: Int,
    val localPath: String,
    /**
     * 是不是视频。
     *
     * 云端文件名的扩展名要如实反映内容，认不出来的格式还得按类型兜底
     * （视频给 .mp4、照片给 .jpg），否则对方拿到 `.bin` 就完全没法播放。
     * 只能从本机这份媒体判断——远端还没有它。
     */
    val isVideo: Boolean = false,
)

/** 远端一个文件 */
data class RemoteRecordRef(
    val path: String,
    val name: String,
    /** 服务端修改时间（秒） */
    val modifiedAtSeconds: Long,
)

/** 远端记录被应用到本地后的结果 */
enum class ApplyResult {
    /** 覆盖了本地 */
    APPLIED,

    /** 本地更新，远端这份不要了 */
    STALE,

    /** 两边一样，什么都没做 */
    UNCHANGED,
}

interface LocalStore {

    suspend fun pendingOps(limit: Int): List<PendingOp>

    suspend fun pendingCount(): Int

    /** 成功：移出发件箱 */
    suspend fun completeOp(op: PendingOp)

    /** 失败但还值得再试：留在发件箱，记下错误与下次时间 */
    suspend fun rescheduleOp(op: PendingOp, error: String, nextAttemptAt: Long)

    /** 彻底失败：移出发件箱，记录标 FAILED 供界面手动重试 */
    suspend fun failOp(op: PendingOp, error: String)

    suspend fun loadDocument(id: String): RecordDocument?

    /** 包含墓碑行，用于判断「本地这份是不是更新」 */
    suspend fun localUpdatedAt(id: String): Long?

    suspend fun remotePathOf(id: String): String?

    suspend fun applyRemote(doc: RecordDocument): ApplyResult

    suspend fun markSynced(id: String, remotePath: String)

    suspend fun markSyncFailed(id: String, error: String)

    suspend fun mediaToUpload(recordId: String): List<LocalMedia>

    suspend fun markMediaUploaded(recordId: String, position: Int, remotePath: String)
}

interface RemoteStore {

    suspend fun uploadDocument(doc: RecordDocument): String

    suspend fun downloadDocument(path: String): String

    suspend fun listDocuments(): List<RemoteRecordRef>

    /** 删远端文件；文件本来就不在也算成功 */
    suspend fun deleteDocument(path: String)

    suspend fun uploadMedia(localPath: String, remoteName: String): String
}

/** 一次同步的结果，直接喂给界面 */
data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val stale: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val authRequired: Boolean = false,
    val errors: List<String> = emptyList(),
    val at: Long = 0L,
) {
    val ok: Boolean get() = failed == 0 && !authRequired

    fun summary(): String = when {
        authRequired -> "授权已失效，请重新授权"
        failed > 0 -> "上传 ${pushed} 条，失败 ${failed} 条"
        pushed == 0 && pulled == 0 && pending == 0 -> "已是最新"
        pending > 0 -> "上去 ${pushed} 条 · 还有 ${pending} 条待传"
        else -> "上去 ${pushed} 条 · 下来 ${pulled} 条"
    }
}

data class PullResult(
    val applied: Int = 0,
    val stale: Int = 0,
    val skipped: Int = 0,
    val authRequired: Boolean = false,
    val errors: List<String> = emptyList(),
)

data class PushResult(
    val pushed: Int = 0,
    val failed: Int = 0,
    val deferred: Int = 0,
    val pending: Int = 0,
    val authRequired: Boolean = false,
    val errors: List<String> = emptyList(),
)
