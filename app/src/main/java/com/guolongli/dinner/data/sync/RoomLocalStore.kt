package com.guolongli.dinner.data.sync

import androidx.room.withTransaction
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.local.CommentEntity
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.local.MediaEntity
import com.guolongli.dinner.data.local.MediaKind
import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.local.RecordEntity
import com.guolongli.dinner.data.local.SyncState
import java.io.File

/**
 * LocalStore 的 Room 实现。同步引擎不直接碰 DAO，规则才能在测试里用内存实现跑通；
 * 这里只做「翻译」和事务边界。
 */
class RoomLocalStore(
    private val db: DinnerDatabase,
    private val recordDir: String = BaiduConfig.RECORD_DIR,
    /** 本地已知的「作者 → 云端头像」，写文档时带上，别人拉到就知道去哪取 */
    private val avatarRemoteOf: (String) -> String? = { null },
    /** 从别人的文档里学到头像地址时回调，交给 ProfileStore 记住 */
    private val onAvatarSeen: (authorId: String, remotePath: String?) -> Unit = { _, _ -> },
) : LocalStore {

    private val records = db.recordDao()
    private val outbox = db.outboxDao()

    override suspend fun pendingOps(limit: Int): List<PendingOp> =
        outbox.pending(limit).map {
            PendingOp(
                seq = it.seq,
                kind = runCatching { OpKind.valueOf(it.kind) }.getOrDefault(OpKind.UPSERT_RECORD),
                targetId = it.targetId,
                attempts = it.attempts,
                nextAttemptAt = it.nextAttemptAt,
            )
        }

    override suspend fun pendingCount(): Int = outbox.count()

    override suspend fun completeOp(op: PendingOp) = outbox.delete(op.seq)

    override suspend fun rescheduleOp(op: PendingOp, error: String, nextAttemptAt: Long) =
        outbox.reschedule(op.seq, nextAttemptAt, error)

    override suspend fun failOp(op: PendingOp, error: String) = outbox.delete(op.seq)

    override suspend fun loadDocument(id: String): RecordDocument? {
        val record = records.record(id) ?: return null
        return RecordDocument.from(
            record = record,
            media = records.mediaOf(id),
            comments = records.commentsOf(id),
            authorAvatar = avatarRemoteOf(record.authorId),
        )
    }

    override suspend fun localUpdatedAt(id: String): Long? = records.record(id)?.updatedAt

    override suspend fun remotePathOf(id: String): String? = records.record(id)?.remotePath

    override suspend fun applyRemote(doc: RecordDocument): ApplyResult = db.withTransaction {
        val existing = records.record(doc.id)
        val verdict = MergePolicy.decide(
            localUpdatedAt = existing?.updatedAt,
            localDirty = existing?.syncState == SyncState.PENDING.name,
            remoteUpdatedAt = doc.updatedAt,
        )
        if (verdict != ApplyResult.APPLIED) return@withTransaction verdict

        // 顺手记住作者头像的位置：下次这条记录出现在列表里就有图可下
        onAvatarSeen(doc.authorId, doc.authorAvatar)

        // 远端版本只带远端路径；本地已经下过同一张图的话，把本地缓存路径留住，省一次下载。
        // 「同一张」按 position/kind/remotePath/duration 全字段比对：任何一项变了都视为
        // 内容更新，丢掉本地缓存让「取回」重新下载——否则别人改了图这边还显示旧的。
        val cachedLocal = records.mediaOf(doc.id).associateBy { it.position }

        records.upsertRecord(
            RecordEntity(
                id = doc.id,
                authorId = doc.authorId,
                authorName = doc.authorName,
                type = doc.type,
                note = doc.note,
                rating = doc.rating,
                starred = doc.starred,
                createdAt = doc.createdAt,
                updatedAt = doc.updatedAt,
                deleted = doc.deleted,
                latitude = doc.latitude,
                longitude = doc.longitude,
                place = doc.place,
                syncState = SyncState.SYNCED.name,
                remotePath = "$recordDir/${RecordDocument.fileName(doc.id)}",
            ),
        )

        records.clearMedia(doc.id)
        if (doc.media.isNotEmpty()) {
            records.upsertMedia(
                doc.media.map { item ->
                    val cached = cachedLocal[item.position]
                    val sameContent = cached != null &&
                        cached.remotePath == item.remotePath &&
                        cached.kind == item.kind &&
                        cached.durationMs == item.durationMs
                    MediaEntity(
                        recordId = doc.id,
                        position = item.position,
                        kind = item.kind,
                        // 只有「内容确实没变」**且文件还在**才留本地缓存路径。
                        // 文件可能已被系统回收（存储空间不足时很常见），
                        // 留着这样的路径等于告诉界面「这个格子有本地文件」，
                        // 结果点开是一片空白。宁可当成没下过，重新取一次。
                        localPath = cached?.localPath
                            ?.takeIf { sameContent }
                            ?.takeIf { File(it).isFile },
                        remotePath = item.remotePath,
                        durationMs = item.durationMs,
                        uploaded = item.remotePath != null,
                    )
                },
            )
        }

        records.clearComments(doc.id)
        if (doc.comments.isNotEmpty()) {
            records.upsertComments(
                doc.comments.map {
                    CommentEntity(
                        id = it.id,
                        recordId = doc.id,
                        authorId = it.authorId,
                        authorName = it.authorName,
                        text = it.text,
                        createdAt = it.createdAt,
                        deleted = it.deleted,
                        pendingSync = false,
                    )
                },
            )
        }
        ApplyResult.APPLIED
    }

    override suspend fun markSynced(id: String, remotePath: String) =
        records.markSynced(id, SyncState.SYNCED.name, remotePath)

    override suspend fun markSyncFailed(id: String, error: String) =
        records.markSyncFailed(id, SyncState.FAILED.name, error)

    override suspend fun mediaToUpload(recordId: String): List<LocalMedia> =
        records.mediaToUpload(recordId).mapNotNull { media ->
            media.localPath?.let {
                LocalMedia(
                    position = media.position,
                    localPath = it,
                    isVideo = media.kind == MediaKind.VIDEO.name,
                )
            }
        }

    override suspend fun markMediaUploaded(recordId: String, position: Int, remotePath: String) =
        records.markMediaUploaded(recordId, position, remotePath)
}
