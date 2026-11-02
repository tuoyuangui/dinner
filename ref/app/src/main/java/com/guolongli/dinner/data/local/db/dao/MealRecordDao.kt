package com.guolongli.dinner.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.guolongli.dinner.data.local.db.entity.MealRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MealRecordDao {

    @Query(
        "SELECT * FROM meal_records WHERE deleted_at IS NULL AND local_removed_at IS NULL " +
            "ORDER BY timestamp DESC"
    )
    fun observeVisible(): Flow<List<MealRecordEntity>>

    @Query("SELECT * FROM meal_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MealRecordEntity>>

    @Query("SELECT * FROM meal_records WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<MealRecordEntity?>

    @Query("SELECT * FROM meal_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MealRecordEntity?

    /**
     * 待上传队列：包含 PENDING 与 FAILED。
     *
     * FAILED 之所以仍进队列，是为了让「手动触发」能重新尝试；
     * 自动同步只取 PENDING，避免无限重试失败项。
     */
    @Query(
        """
        SELECT * FROM meal_records
        WHERE sync_state IN (:states)
        ORDER BY updated_at ASC
        LIMIT :limit
        """
    )
    suspend fun pendingUpload(states: List<String>, limit: Int): List<MealRecordEntity>

    @Query("SELECT COUNT(*) FROM meal_records WHERE sync_state = :state")
    suspend fun countByState(state: String): Long

    @Upsert
    suspend fun upsert(entity: MealRecordEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MealRecordEntity>)

    @Query("UPDATE meal_records SET sync_state = :state, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSyncState(id: String, state: String, updatedAt: Long)

    /**
     * 覆盖媒体列表（JSON）。
     *
     * 刻意不动 sync_state 与 updated_at：这个办法同时用于两件事——
     * 上传成功后回填远端路径（上传流程自己再调 updateSyncState），
     * 以及从网盘拉回媒体后回填本地缓存路径（缓存是设备私有数据，
     * 不属于同步内容，不能把一条已同步记录重新推进上传队列）。
     */
    @Query("UPDATE meal_records SET media_json = :mediaJson WHERE id = :id")
    suspend fun updateMedia(id: String, mediaJson: String)

    /** 星标：只是本机的索引标记，不参与同步、不改 updated_at。 */
    @Query("UPDATE meal_records SET marked = :marked WHERE id = :id")
    suspend fun setMarked(id: String, marked: Boolean)

    /**
     * 云端删除：置 deletedAt 并回到 PENDING，等同步把删除意图告诉所有人。
     *
     * 上传成功后同步层会删掉网盘上的媒体文件。只有记录创建者本人可以调用，
     * 权限判断在仓储层做，DAO 不做业务判断。
     */
    @Query("UPDATE meal_records SET deleted_at = :deletedAt, sync_state = 'PENDING', updated_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * 仅删本地：只打一个本地标记，不动 sync_state / updated_at。
     *
     * 这三个字段一旦改动，记录会被重新推进上传队列，等于把「本机不想看」
     * 变成了「让所有人删掉」，与用户意图相反。
     */
    @Query("UPDATE meal_records SET local_removed_at = :removedAt WHERE id = :id")
    suspend fun localRemove(id: String, removedAt: Long)

    /** 把崩溃/强杀遗留的 SYNCING 记录回退为 PENDING，避免永久卡在上传中。 */
    @Query("UPDATE meal_records SET sync_state = 'PENDING' WHERE sync_state = 'SYNCING'")
    suspend fun recoverStuckSyncing(): Int

    /**
     * 身份迁移：把某个作者标识下的记录全部改挂到新标识。
     *
     * 同时置回 PENDING，让下一轮同步把 records.json 里的 created_by 也刷成新值，
     * 否则其他设备看到的还是旧身份。媒体不会重传（remote_path 已填）。
     */
    @Query(
        "UPDATE meal_records SET created_by = :to, sync_state = 'PENDING', updated_at = :now " +
            "WHERE created_by = :from"
    )
    suspend fun rewriteAuthor(from: String, to: String, now: Long): Int

    @Query("DELETE FROM meal_records WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM meal_records WHERE id IN (:ids)")
    suspend fun hardDeleteAll(ids: List<String>)

    /**
     * 本地清理候选：早于 cutoff 且已完全同步（云端有副本）的记录。
     *
     * 只取 SYNCED：PENDING / FAILED / SYNCING 都意味着云端不完整，
     * 这时候删本地就是把唯一一份数据删掉（红线）。
     */
    @Query(
        """
        SELECT * FROM meal_records
        WHERE timestamp < :cutoff
          AND sync_state = 'SYNCED'
          AND deleted_at IS NULL
          AND local_removed_at IS NULL
        ORDER BY timestamp ASC
        """
    )
    suspend fun purgeCandidates(cutoff: Long): List<MealRecordEntity>

    /** 按时间区间取记录，供「从云端补一段」与本地统计使用。 */
    @Query(
        "SELECT * FROM meal_records WHERE timestamp >= :from AND timestamp < :to " +
            "ORDER BY timestamp ASC"
    )
    suspend fun rangeAll(from: Long, to: Long): List<MealRecordEntity>

    /**
     * 快照用：**包含**「只删本地」的记录。
     *
     * 早先这里排除了 local_removed_at 非空的记录，是个会丢数据的坑：
     * 本机为了省空间清理了 N 天前的本地副本，record 行被打上「本机不展示」，
     * 下一次发布 records.json 就把这些记录从云端快照里抹掉了。
     * 之后换设备 / 重装恢复，这些记录在云端就真的不存在了——
     * 而用户的理解是「只删了本机副本，云端不受影响」。
     *
     * 只删本地的语义只影响 `observeVisible`（本机看不看），不影响云端那份完整性。
     */
    @Query("SELECT * FROM meal_records")
    suspend fun getAll(): List<MealRecordEntity>

    /** 取消「只删本地」标记，让记录重新出现在本机时间线上。 */
    @Query("UPDATE meal_records SET local_removed_at = NULL WHERE id = :id")
    suspend fun clearLocalRemoved(id: String)

    @Query(
        "UPDATE meal_records SET local_removed_at = NULL " +
            "WHERE local_removed_at IS NOT NULL AND timestamp >= :from AND timestamp < :to"
    )
    suspend fun clearLocalRemovedInRange(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM meal_records WHERE deleted_at IS NULL AND local_removed_at IS NULL")
    suspend fun countAll(): Long

    @Query(
        "SELECT COUNT(*) FROM meal_records WHERE created_by = :author " +
            "AND deleted_at IS NULL AND local_removed_at IS NULL"
    )
    suspend fun countMine(author: String): Long
}
