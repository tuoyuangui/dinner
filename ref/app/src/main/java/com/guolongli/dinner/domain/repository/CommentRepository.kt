package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.Comment
import com.guolongli.dinner.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * 评论仓库。
 *
 * 与餐食记录一样走「本地优先」：先落 Room 标 PENDING，再由同步层写进快照。
 * 评论没有媒体文件，因此不需要单独的分片上传，随 records.json 一起发布即可。
 */
interface CommentRepository {

    fun observeForMeal(mealId: String): Flow<List<Comment>>

    /** 全部评论，供时间线一次性建立「记录 -> 评论」索引，避免每条记录各开一个查询。 */
    fun observeAll(): Flow<List<Comment>>

    /** 全部评论（含软删除），供快照使用。 */
    suspend fun snapshotAll(): List<Comment>

    suspend fun add(mealId: String, authorId: String, text: String): Comment

    /** 软删除：置 deletedAt 并回到 PENDING，等待同步告知其他人。 */
    suspend fun softDelete(id: String, deletedAt: Long)

    /** 记录被彻底删除时连带清理评论。 */
    suspend fun deleteForMeal(mealId: String)

    suspend fun pendingUpload(limit: Int, includeFailed: Boolean): List<Comment>

    suspend fun updateSyncState(id: String, state: SyncState, updatedAt: Long)

    /** 快照发布成功后批量标记为已同步。 */
    suspend fun markSynced(ids: List<String>)

    /** 用远端评论并入本地（远端较新者胜）。 */
    suspend fun mergeFromRemote(comments: List<Comment>)

    suspend fun recoverStuckSyncing(): Int
}
