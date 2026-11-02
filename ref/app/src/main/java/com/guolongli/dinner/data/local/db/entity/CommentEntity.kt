package com.guolongli.dinner.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 评论表。
 *
 * [mealId] 指向 meal_records.id（不建外键约束：记录可能被本地删除而评论需要保留到同步完成）。
 * 复合索引 (meal_id, created_at) 对应「某条记录下的评论按时间排序」这一唯一查询形态。
 */
@Entity(
    tableName = "comments",
    indices = [
        Index(value = ["meal_id", "created_at"]),
        Index(value = ["sync_state"]),
    ],
)
data class CommentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "meal_id")
    val mealId: String,

    @ColumnInfo(name = "author_id")
    val authorId: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,

    @ColumnInfo(name = "sync_state")
    val syncState: String,
)
