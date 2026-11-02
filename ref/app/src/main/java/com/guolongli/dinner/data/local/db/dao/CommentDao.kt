package com.guolongli.dinner.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.guolongli.dinner.data.local.db.entity.CommentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentDao {

    /** 某条记录下可见的评论，按时间正序（先说的在上）。 */
    @Query("SELECT * FROM comments WHERE meal_id = :mealId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun observeForMeal(mealId: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments ORDER BY created_at ASC")
    fun observeAll(): Flow<List<CommentEntity>>

    @Query("SELECT COUNT(*) FROM comments WHERE meal_id = :mealId AND deleted_at IS NULL")
    suspend fun countForMeal(mealId: String): Int

    @Query(
        """
        SELECT * FROM comments
        WHERE sync_state IN (:states)
        ORDER BY updated_at ASC
        LIMIT :limit
        """
    )
    suspend fun pendingUpload(states: List<String>, limit: Int): List<CommentEntity>

    @Upsert
    suspend fun upsert(entity: CommentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CommentEntity>)

    @Query("UPDATE comments SET sync_state = :state, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSyncState(id: String, state: String, updatedAt: Long)

    @Query("UPDATE comments SET deleted_at = :deletedAt, sync_state = 'PENDING', updated_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("DELETE FROM comments WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** 记录被彻底删除时，连带清掉它的评论，避免留下孤儿行。 */
    @Query("DELETE FROM comments WHERE meal_id = :mealId")
    suspend fun deleteForMeal(mealId: String)

    @Query("SELECT * FROM comments")
    suspend fun getAll(): List<CommentEntity>

    @Query("UPDATE comments SET sync_state = 'PENDING' WHERE sync_state = 'SYNCING'")
    suspend fun recoverStuckSyncing(): Int
}
