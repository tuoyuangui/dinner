package com.guolongli.dinner.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记录表。
 *
 * [deletedAt] 为软删除时间戳，与 [updatedAt] 一起用于冲突判定与对端告知。
 * [rating] 可为空，表示未评分。
 * [mediaJson] 是媒体列表（JSON），一条记录可以带 0..9 段照片 / 视频。
 * [marked] 是星标，用于时间线快速索引。
 *
 * 2.0.0 起只有这一种形态：没有单媒体旧列，也没有「medialess 旧库」的回落路径。
 * 数据库版本重置为 1，新安装直接建表，不做任何历史迁移。
 */
@Entity(
    tableName = "meal_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["sync_state"]),
        // 待上传队列按 (sync_state, updated_at) 排序筛选，复合索引避免全表扫描。
        Index(value = ["sync_state", "updated_at"]),
        Index(value = ["marked"]),
    ],
)
data class MealRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "meal_type")
    val mealType: String,

    @ColumnInfo(name = "note")
    val note: String,

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    @ColumnInfo(name = "sync_state")
    val syncState: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,

    @ColumnInfo(name = "rating")
    val rating: Int?,

    /**
     * 「只删本地」标记。
     *
     * 非空表示这条记录只在本机隐藏：不上传、不同步，
     * 云端文件与其他人完全不受影响。与 deleted_at 是两件事。
     */
    @ColumnInfo(name = "local_removed_at")
    val localRemovedAt: Long? = null,

    /** 媒体列表，JSON 数组，空串表示还没有媒体。 */
    @ColumnInfo(name = "media_json")
    val mediaJson: String = "",

    /** 星标，时间线可按它筛选。 */
    @ColumnInfo(name = "marked")
    val marked: Boolean = false,

    /** 记录时所在位置，JSON 编码的 GeoLocation，空串表示没有位置。 */
    @ColumnInfo(name = "location_json")
    val locationJson: String = "",

    /** 标签列表，JSON 数组，空串表示没有标签。 */
    @ColumnInfo(name = "tags_json")
    val tagsJson: String = "",
)
