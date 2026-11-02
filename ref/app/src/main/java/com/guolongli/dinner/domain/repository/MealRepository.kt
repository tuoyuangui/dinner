package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * 本地记录仓库（Room）。所有写入先落本地，再标 PENDING 等待上传。
 */
interface MealRepository {
    fun observeAll(): Flow<List<MealRecord>>

    /** 只观察一条记录，编辑页使用。 */
    fun observeById(id: String): Flow<MealRecord?>

    suspend fun getById(id: String): MealRecord?

    /**
     * 待上传记录，按 updatedAt 升序，供同步分批取用。
     *
     * @param includeFailed 是否纳入上传失败（FAILED）的记录。
     *   false（自动同步）只取 PENDING，避免失败项被无限重试；
     *   true（手动触发）把 FAILED 一并纳入重新尝试。
     */
    suspend fun pendingUpload(limit: Int, includeFailed: Boolean = false): List<MealRecord>

    suspend fun failedCount(): Int

    suspend fun upsert(record: MealRecord)

    suspend fun upsertAll(records: List<MealRecord>)

    /** 仅更新同步相关字段，避免覆盖用户正在编辑的内容。 */
    suspend fun updateSyncState(id: String, state: com.guolongli.dinner.domain.model.SyncState, updatedAt: Long)

    /**
     * 覆盖一条记录的媒体列表。
     *
     * 上传成功后回填远端路径、从网盘取回后回填本地路径，都用这一个入口。
     * 它**不改** sync_state / updated_at：缓存回填是设备私有动作，
     * 上传流程自己会再调 [updateSyncState]。
     */
    suspend fun updateMedia(id: String, media: List<MediaItem>)

    /** 星标：只影响本机索引，不参与同步。 */
    suspend fun setMarked(id: String, marked: Boolean)

    /** 软删除：置 deletedAt 并回到 PENDING，等待同步告知其他人（云端删除）。 */
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * 仅删本地：只在本机隐藏，不上传、不影响云端与其他人。
     *
     * 与 [softDelete] 的区别是它不碰 sync_state / updated_at，
     * 因此不会把「本机不想看」变成「让所有人删掉」。
     */
    suspend fun localRemove(id: String, removedAt: Long)

    /** 取消「只删本地」标记：从云端取回时用，让记录重新出现在本机。 */
    suspend fun clearLocalRemoved(id: String)

    /**
     * 取消某个时间区间内的「只删本地」标记，返回恢复条数。
     *
     * 「从云端补一段」补的是记录，本机当初为了省空间把它藏起来了，
     * 补回来就得让它重新可见，否则用户会以为没补上。
     */
    suspend fun clearLocalRemovedInRange(from: Long, to: Long): Int

    /**
     * 物理删除本地记录（连同评论）。
     *
     * 只用于「本地和云端都删」：云端删除会先落成软删除并进入上传队列，
     * 上传成功后才允许把本地行真正抹掉。
     */
    suspend fun hardDelete(id: String)

    /** 批量物理删除（本地清理用）。 */
    suspend fun hardDeleteAll(ids: List<String>)

    /**
     * 本地清理候选：早于 cutoff 且已完全同步的记录。
     *
     * 只返回云端已有副本的记录——这是「删本地之前先确认云端存在」的第一道闸。
     */
    suspend fun purgeCandidates(cutoff: Long): List<MealRecord>

    /** 按时间区间取记录（含软删除），用于「从云端补一段」前的本地比对。 */
    suspend fun rangeAll(from: Long, to: Long): List<MealRecord>

    /** 把崩溃/强杀遗留的 SYNCING 记录回退为 PENDING，避免永久卡在上传中。 */
    suspend fun recoverStuckSyncing(): Int

    /**
     * 把某个作者标识下的记录全部改挂到新标识，返回改动条数。
     *
     * 只在「绑定手机号、身份 id 变化」时使用。改完这些记录会回到 PENDING，
     * 下一轮同步把新的 created_by 写进 records.json；媒体本身不会重传。
     */
    suspend fun rewriteAuthor(from: String, to: String): Int

    /** 新设备恢复时用：把整批远端记录并入本地。 */
    suspend fun replaceAll(records: List<MealRecord>)

    /**
     * 取全量记录（含软删除），用于生成 records.json 快照。
     * 软删除必须包含在内，否则对端无法得知哪些记录被删除。
     */
    suspend fun snapshotAll(): List<MealRecord>

    suspend fun countAll(): Long

    suspend fun countMine(createdBy: String): Long
}
