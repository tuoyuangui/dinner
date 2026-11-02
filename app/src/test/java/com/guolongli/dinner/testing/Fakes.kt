package com.guolongli.dinner.testing

import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.data.sync.ApplyResult
import com.guolongli.dinner.data.sync.LocalMedia
import com.guolongli.dinner.data.sync.LocalStore
import com.guolongli.dinner.data.sync.MergePolicy
import com.guolongli.dinner.data.sync.PendingOp
import com.guolongli.dinner.data.sync.RecordDocument
import com.guolongli.dinner.data.sync.RemoteRecordRef
import com.guolongli.dinner.data.sync.RemoteStore

/**
 * 同步引擎的测试替身：一个内存版本地库、一个内存版网盘。
 * 引擎只依赖 LocalStore / RemoteStore 两个接口，所以这些规则能在 JVM 上直接跑，
 * 不用起模拟器，也不用连真的百度网盘。
 */
class FakeLocalStore : LocalStore {

    /** 本地记录，键是记录 id；墓碑也留在里面（deleted = true） */
    val documents = linkedMapOf<String, RecordDocument>()

    /** 本地还有没传出去的改动 */
    val dirty = mutableSetOf<String>()

    val ops = mutableListOf<PendingOp>()

    /** recordId -> 待上传媒体 */
    val mediaQueues = mutableMapOf<String, MutableList<LocalMedia>>()

    val syncedPaths = mutableMapOf<String, String>()
    val failures = mutableMapOf<String, String>()

    /** 调用记录，用来断言顺序 */
    val calls = mutableListOf<String>()

    private var seq = 0L

    fun enqueueOp(
        targetId: String,
        kind: OpKind = OpKind.UPSERT_RECORD,
        attempts: Int = 0,
        nextAttemptAt: Long = 0L,
    ): PendingOp {
        val op = PendingOp(++seq, kind, targetId, attempts, nextAttemptAt)
        ops += op
        return op
    }

    fun putDocument(doc: RecordDocument, isDirty: Boolean = false) {
        documents[doc.id] = doc
        if (isDirty) dirty += doc.id else dirty -= doc.id
    }

    override suspend fun pendingOps(limit: Int): List<PendingOp> {
        calls += "pendingOps"
        return ops.sortedBy { it.seq }.take(limit)
    }

    override suspend fun pendingCount(): Int = ops.size

    override suspend fun completeOp(op: PendingOp) {
        ops.removeAll { it.seq == op.seq }
        calls += "completeOp:${op.targetId}"
    }

    override suspend fun rescheduleOp(op: PendingOp, error: String, nextAttemptAt: Long) {
        val index = ops.indexOfFirst { it.seq == op.seq }
        if (index >= 0) {
            ops[index] = ops[index].copy(
                attempts = ops[index].attempts + 1,
                nextAttemptAt = nextAttemptAt,
            )
        }
        calls += "reschedule:${op.targetId}:$error"
    }

    override suspend fun failOp(op: PendingOp, error: String) {
        ops.removeAll { it.seq == op.seq }
        calls += "failOp:${op.targetId}:$error"
    }

    override suspend fun loadDocument(id: String): RecordDocument? = documents[id]

    override suspend fun localUpdatedAt(id: String): Long? = documents[id]?.updatedAt

    override suspend fun remotePathOf(id: String): String? = syncedPaths[id]

    override suspend fun applyRemote(doc: RecordDocument): ApplyResult {
        calls += "applyRemote:${doc.id}"
        val existing = documents[doc.id]
        val verdict = MergePolicy.decide(
            localUpdatedAt = existing?.updatedAt,
            localDirty = existing != null && doc.id in dirty,
            remoteUpdatedAt = doc.updatedAt,
        )
        if (verdict == ApplyResult.APPLIED) {
            documents[doc.id] = doc
            dirty -= doc.id
            syncedPaths[doc.id] = "/apps/dinner/records/${RecordDocument.fileName(doc.id)}"
        }
        return verdict
    }

    override suspend fun markSynced(id: String, remotePath: String) {
        syncedPaths[id] = remotePath
        dirty -= id
        calls += "markSynced:$id"
    }

    override suspend fun markSyncFailed(id: String, error: String) {
        failures[id] = error
        calls += "markSyncFailed:$id"
    }

    override suspend fun mediaToUpload(recordId: String): List<LocalMedia> =
        mediaQueues[recordId].orEmpty().toList()

    override suspend fun markMediaUploaded(recordId: String, position: Int, remotePath: String) {
        mediaQueues[recordId]?.removeAll { it.position == position }
        val doc = documents[recordId]
        if (doc != null) {
            // 和真实库一致：媒体行一定存在，上传完只是补上 remotePath
            val existing = doc.media.firstOrNull { it.position == position }
            val media = if (existing != null) {
                doc.media.map { if (it.position == position) it.copy(remotePath = remotePath) else it }
            } else {
                doc.media + com.guolongli.dinner.data.sync.MediaDoc(position, "PHOTO", remotePath, null)
            }
            documents[recordId] = doc.copy(media = media)
        }
        calls += "markMediaUploaded:$recordId:$position"
    }
}

class FakeRemoteStore : RemoteStore {

    /** 路径 -> (内容, 秒级修改时间) */
    val files = linkedMapOf<String, Pair<String, Long>>()

    val deletedPaths = mutableListOf<String>()
    val uploadedMedia = mutableListOf<Pair<String, String>>()
    val calls = mutableListOf<String>()

    /** 注入错误 */
    var listError: NetdiskException? = null
    var downloadError: NetdiskException? = null
    var uploadError: NetdiskException? = null
    var mediaUploadError: NetdiskException? = null
    var deleteError: NetdiskException? = null

    var downloadCount = 0
    var uploadCount = 0
    var mediaUploadCount = 0

    fun putDocument(doc: RecordDocument, modifiedAtSeconds: Long = 0) {
        files["/apps/dinner/records/${RecordDocument.fileName(doc.id)}"] = doc.toJson() to modifiedAtSeconds
    }

    override suspend fun uploadDocument(doc: RecordDocument): String {
        uploadCount++
        calls += "uploadDocument:${doc.id}"
        uploadError?.let { throw it }
        val path = "/apps/dinner/records/${RecordDocument.fileName(doc.id)}"
        files[path] = doc.toJson() to 0
        return path
    }

    override suspend fun downloadDocument(path: String): String {
        downloadCount++
        calls += "downloadDocument:$path"
        downloadError?.let { throw it }
        return files[path]?.first ?: throw NetdiskException(31066, "not exist")
    }

    override suspend fun listDocuments(): List<RemoteRecordRef> {
        calls += "listDocuments"
        listError?.let { throw it }
        return files.map { (path, value) ->
            RemoteRecordRef(path, path.substringAfterLast('/'), value.second)
        }
    }

    override suspend fun deleteDocument(path: String) {
        calls += "deleteDocument:$path"
        deleteError?.let { throw it }
        files.remove(path)
        deletedPaths += path
    }

    override suspend fun uploadMedia(localPath: String, remoteName: String): String {
        mediaUploadCount++
        calls += "uploadMedia:$remoteName"
        mediaUploadError?.let { throw it }
        uploadedMedia += localPath to remoteName
        return "/apps/dinner/media/$remoteName"
    }
}

/** 固定 token，测试里不需要授权链 */
class FakeTokenSource(private val value: String = "test-token") :
    com.guolongli.dinner.data.auth.TokenSource {
    var callCount = 0
        private set

    override suspend fun token(): String {
        callCount++
        return value
    }
}

/** 方便造文档 */
fun draftRecord(
    id: String,
    updatedAt: Long = 1_000L,
    deleted: Boolean = false,
    note: String = "番茄牛腩",
    type: String = "DINNER",
    rating: Int = 4,
    authorId: String = "author-1",
): RecordDocument = RecordDocument(
    id = id,
    authorId = authorId,
    authorName = "李国龙",
    type = type,
    note = note,
    rating = rating,
    createdAt = 900L,
    updatedAt = updatedAt,
    starred = false,
    deleted = deleted,
)
