package com.guolongli.dinner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    /** 时间线：未删除的记录，最新在前，连带媒体与评论 */
    @Transaction
    @Query("SELECT * FROM records WHERE deleted = 0 ORDER BY createdAt DESC, id ASC")
    fun observeTimeline(): Flow<List<RecordWithChildren>>

    @Query("SELECT * FROM records WHERE deleted = 0 ORDER BY createdAt DESC, id ASC")
    suspend fun timelineOnce(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun record(id: String): RecordEntity?

    @Query("SELECT * FROM media WHERE recordId = :recordId ORDER BY position ASC")
    suspend fun mediaOf(recordId: String): List<MediaEntity>

    @Query("SELECT * FROM comments WHERE recordId = :recordId ORDER BY createdAt ASC")
    suspend fun commentsOf(recordId: String): List<CommentEntity>

    /** 还没传到云端的媒体 */
    @Query("SELECT * FROM media WHERE recordId = :recordId AND remotePath IS NULL ORDER BY position ASC")
    suspend fun mediaToUpload(recordId: String): List<MediaEntity>

    @Query("SELECT remotePath FROM media WHERE recordId = :recordId AND remotePath IS NOT NULL ORDER BY position ASC")
    suspend fun uploadedMediaPaths(recordId: String): List<String>

    @Query("UPDATE media SET remotePath = :remotePath, uploaded = 1 WHERE recordId = :recordId AND position = :position")
    suspend fun markMediaUploaded(recordId: String, position: Int, remotePath: String)

    /** 本地文件没了（按保留期清理）或刚补回来，都要同步这一列 */
    @Query("UPDATE media SET localPath = :path WHERE recordId = :recordId AND position = :position")
    suspend fun setMediaLocalPath(recordId: String, position: Int, path: String?)

    /**
     * 「区间取回」要找的媒体：云端有、本地没有，且记录没被删。
     * 按记录时间从旧到新，先补老照片——它们最可能不在本地。
     *
     * [since] 是记录时间下界，[afterPosition] 是同一时刻内的媒体序号下界；
     * 两者一起构成一个游标，让「分批补齐」能一直往前走。
     */
    @Query(
        """
        SELECT media.* FROM media
        INNER JOIN records ON records.id = media.recordId
        WHERE records.deleted = 0
          AND records.createdAt >= :since
          AND media.remotePath IS NOT NULL
          AND media.localPath IS NULL
        ORDER BY records.createdAt ASC, media.position ASC
        LIMIT :limit
        """,
    )
    suspend fun mediaMissing(since: Long, limit: Int): List<MediaEntity>

    /**
     * 带游标的「找缺失媒体」。
     *
     * 游标是 (记录时间, 媒体序号, 记录 id) 三元组，取严格大于它的那些。
     *
     * 为什么需要它：查询条件里有 `localPath IS NULL`，而**下载失败的文件
     * 仍然满足这个条件**。如果分批时每次都从头查，失败的那几条会永远排在
     * 最前面，后面还没试过的文件就再也轮不到——补齐会卡死在同一个地方。
     * 游标保证「试过的（含失败的）不再回头」，下一轮同步再重新来过。
     *
     * 为什么游标要三级：两条记录可能同一时刻创建（毫秒级时间戳会撞），
     * 它们的第 0 张图 position 也是 0。只用 (时间, 序号) 两级游标，
     * 就会把「同时间同序号但记录 id 更大」的那条整个跳过。
     */
    @Query(
        """
        SELECT media.* FROM media
        INNER JOIN records ON records.id = media.recordId
        WHERE records.deleted = 0
          AND (
                records.createdAt > :afterCreatedAt
             OR (records.createdAt = :afterCreatedAt AND media.position > :afterPosition)
             OR (records.createdAt = :afterCreatedAt AND media.position = :afterPosition
                 AND records.id > :afterRecordId)
          )
          AND media.remotePath IS NOT NULL
          AND media.localPath IS NULL
        ORDER BY records.createdAt ASC, media.position ASC, records.id ASC
        LIMIT :limit
        """,
    )
    suspend fun mediaMissingAfter(
        afterCreatedAt: Long,
        afterPosition: Int,
        afterRecordId: String,
        limit: Int,
    ): List<MediaEntity>

    /**
     * 缺失媒体所属记录的时间（补齐时用来推进游标）。
     *
     * [MediaEntity] 本身不存记录时间，所以这里单独查一次。
     */
    @Query("SELECT createdAt FROM records WHERE id = :recordId")
    suspend fun recordCreatedAt(recordId: String): Long?

    @Upsert
    suspend fun upsertRecord(entity: RecordEntity)

    @Upsert
    suspend fun upsertRecords(entities: List<RecordEntity>)

    @Upsert
    suspend fun upsertMedia(entities: List<MediaEntity>)

    @Upsert
    suspend fun upsertComments(entities: List<CommentEntity>)

    @Query("DELETE FROM media WHERE recordId = :recordId")
    suspend fun clearMedia(recordId: String)

    @Query("DELETE FROM comments WHERE recordId = :recordId")
    suspend fun clearComments(recordId: String)

    @Query("UPDATE records SET syncState = :state, remotePath = :remotePath, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: String, state: String, remotePath: String?)

    @Query("UPDATE records SET syncState = :state, lastError = :error WHERE id = :id")
    suspend fun markSyncFailed(id: String, state: String, error: String?)

    // ------------------------------------------------------------------ 统计

    @Query("SELECT COUNT(*) FROM records WHERE deleted = 0")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE deleted = 0 AND authorId = :authorId")
    fun observeMine(authorId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE deleted = 0 AND syncState = 'SYNCED'")
    fun observeSynced(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeOutboxCount(): Flow<Int>

    /**
     * 绑定手机号之后把旧身份（设备 id）名下的内容并到新身份上。
     *
     * 作者归属换了，记录本身的内容不动；不迁移的话，同一个人会以两个身份
     * 同时出现在时间线与成员列表里，统计也变成两份。
     */
    @Query("UPDATE records SET authorId = :to WHERE authorId = :from")
    suspend fun migrateRecordAuthor(from: String, to: String)

    @Query("UPDATE comments SET authorId = :to WHERE authorId = :from")
    suspend fun migrateCommentAuthor(from: String, to: String)

    /** 某条媒体的远端路径（删记录时用来顺手清云端附件） */
    @Query("DELETE FROM media WHERE recordId = :recordId AND position = :position")
    suspend fun deleteMedia(recordId: String, position: Int)

    /** 日历用：有记录的日期（本地无时区换算成本，直接按天取整） */
    @Query("SELECT DISTINCT createdAt / 86400000 AS day FROM records WHERE deleted = 0 ORDER BY day ASC")
    suspend fun recordedEpochDays(): List<Long>
}

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox ORDER BY seq ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE targetId = :targetId ORDER BY seq ASC")
    suspend fun forTarget(targetId: String): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity): Long

    @Update
    suspend fun update(entity: OutboxEntity)

    /** 退避：次数 +1，记下错误与下次可试时间 */
    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE seq = :seq")
    suspend fun reschedule(seq: Long, nextAttemptAt: Long, error: String)

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun delete(seq: Long)

    @Query("DELETE FROM outbox WHERE targetId = :targetId")
    suspend fun deleteForTarget(targetId: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
