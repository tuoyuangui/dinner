package com.guolongli.dinner.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 记录的同步状态：待上传 / 已同步 / 上传失败（失败后仍留在发件箱外的记录里，可手动重试） */
enum class SyncState { PENDING, SYNCED, FAILED }

/** 发件箱里的操作类型 */
enum class OpKind { UPSERT_RECORD, DELETE_RECORD }

/** 媒体类型 */
enum class MediaKind { PHOTO, VIDEO }

/**
 * 一条记录。
 *
 * id 由客户端生成（UUID），跨设备稳定，也是云端文件名 `rec-<id>.json` 的来源；
 * updatedAt 参与 last-write-wins 合并，删除用墓碑（deleted = true）而不是直接删行，
 * 否则同步时会被别的设备的旧版本「复活」。
 */
@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    /** RecordType.name */
    val type: String,
    val note: String,
    val rating: Int = 0,
    val starred: Boolean = false,
    /** 记录本身的时刻（用于按天分组） */
    val createdAt: Long,
    /** 最后一次修改，合并时比大小 */
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** 记录发生的位置。三列一起为空表示「这条没记位置」，不是 0,0 */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 一句人话的地址（逆地理编码来的），可能是空串 */
    val place: String? = null,
    /** SyncState.name */
    val syncState: String = SyncState.PENDING.name,
    val remotePath: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "media",
    primaryKeys = ["recordId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordId")],
)
data class MediaEntity(
    val recordId: String,
    /** 0..8，与九宫格顺序一致 */
    val position: Int,
    /** MediaKind.name */
    val kind: String = MediaKind.PHOTO.name,
    val localPath: String? = null,
    val remotePath: String? = null,
    val durationMs: Long? = null,
    val uploaded: Boolean = false,
)

@Entity(
    tableName = "comments",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordId")],
)
data class CommentEntity(
    val id: String,
    val recordId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long,
    val deleted: Boolean = false,
    val pendingSync: Boolean = true,
)

/**
 * 发件箱：离线优先的核心。写本地成功就算「记下来了」，
 * 真正的上传留给这个队列，联网后按 seq 顺序补传。
 */
@Entity(tableName = "outbox", indices = [Index("targetId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0L,
    /** 幂等键，仅用于排查 */
    val opId: String,
    /** OpKind.name */
    val kind: String,
    val targetId: String,
    val attempts: Int = 0,
    val enqueuedAt: Long,
    /** 退避用：早于这个时间戳不重试 */
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
)

/** 时间线查询结果：一条记录带齐媒体与评论，一次查询搞定，不产生 N+1 */
data class RecordWithChildren(
    @Embedded val record: RecordEntity,
    @Relation(parentColumn = "id", entityColumn = "recordId")
    val media: List<MediaEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "recordId")
    val comments: List<CommentEntity> = emptyList(),
)
