package com.guolongli.dinner.data.repository

import com.guolongli.dinner.core.SyncConfig
import com.guolongli.dinner.data.local.db.dao.CommentDao
import com.guolongli.dinner.data.mapper.toDomain
import com.guolongli.dinner.data.mapper.toEntity
import com.guolongli.dinner.domain.model.Comment
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.CommentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 评论仓库实现。
 *
 * 与记录一致：先落本地标 PENDING，再由同步层统一发布快照。
 * 评论没有媒体文件，所以这里不涉及任何网盘调用。
 */
@Singleton
class CommentRepositoryImpl @Inject constructor(
    private val dao: CommentDao,
) : CommentRepository {

    override fun observeForMeal(mealId: String): Flow<List<Comment>> =
        dao.observeForMeal(mealId).map { list -> list.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Comment>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun snapshotAll(): List<Comment> =
        dao.getAll().map { it.toDomain() }

    override suspend fun add(mealId: String, authorId: String, text: String): Comment {
        val trimmed = text.trim().take(MAX_COMMENT_LENGTH)
        require(trimmed.isNotEmpty()) { "评论不能为空" }
        val now = System.currentTimeMillis()
        val comment = Comment(
            id = UUID.randomUUID().toString(),
            mealId = mealId,
            authorId = authorId,
            text = trimmed,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncState = SyncState.PENDING,
        )
        dao.upsert(comment.toEntity())
        return comment
    }

    override suspend fun softDelete(id: String, deletedAt: Long) {
        dao.softDelete(id = id, deletedAt = deletedAt)
    }

    override suspend fun deleteForMeal(mealId: String) {
        dao.deleteForMeal(mealId)
    }

    override suspend fun pendingUpload(limit: Int, includeFailed: Boolean): List<Comment> {
        val states = if (includeFailed) {
            listOf(SyncState.PENDING.name, SyncState.FAILED.name)
        } else {
            listOf(SyncState.PENDING.name)
        }
        return dao.pendingUpload(states, limit.coerceAtLeast(1)).map { it.toDomain() }
    }

    override suspend fun updateSyncState(id: String, state: SyncState, updatedAt: Long) {
        dao.updateSyncState(id = id, state = state.name, updatedAt = updatedAt)
    }

    /**
     * 快照发布成功后，把一批评论标为已同步。
     *
     * **只改 sync_state，绝不动 updated_at。**
     *
     * `updated_at` 是「这条评论最后一次被改动」的时间，也是 [mergeFromRemote] 判断
     * 「远端和本地谁更新」的唯一依据。早先这里把它刷成 `System.currentTimeMillis()`，
     * 于是本机每条评论在同步成功后都变成了「刚刚被改过」：
     * 别人删掉评论后推上来的那份快照，updated_at 必然早于本机这份，
     * 在合并时被判成「远端不新于本地」直接丢弃——别人的删除永远同步不过来，
     * 只有在本机重新编辑这条评论才可能恢复一致。
     */
    override suspend fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        val known = dao.getAll().associateBy { it.id }
        // 用 upsert 而不是 updateSyncState：后者会顺带覆盖 updated_at，
        // 这里只改一个字段，其余（含 updated_at）保持原值。
        val updated = ids.mapNotNull { id ->
            known[id]
                ?.takeIf { it.syncState != SyncState.SYNCED.name }
                ?.copy(syncState = SyncState.SYNCED.name)
        }
        if (updated.isNotEmpty()) dao.upsertAll(updated)
    }

    override suspend fun mergeFromRemote(comments: List<Comment>) {
        if (comments.isEmpty()) return
        val localById = dao.getAll().associateBy { it.id }
        val toApply = comments.mapNotNull { incoming ->
            val existing = localById[incoming.id]
            when {
                existing == null -> incoming.copy(syncState = SyncState.SYNCED).toEntity()
                incoming.updatedAt > existing.updatedAt ->
                    incoming.copy(syncState = SyncState.SYNCED).toEntity()
                else -> null
            }
        }
        if (toApply.isNotEmpty()) dao.upsertAll(toApply)
    }

    override suspend fun recoverStuckSyncing(): Int = dao.recoverStuckSyncing()

    companion object {
        /** 单条评论长度上限，避免一条备注把快照撑爆。 */
        const val MAX_COMMENT_LENGTH = 300

        val DEFAULT_BATCH = SyncConfig.BATCH_SIZE
    }
}
