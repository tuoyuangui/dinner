package com.guolongli.dinner.domain.model

/**
 * 一条评论。
 *
 * 评论挂在 [mealId] 上，作者用 [authorId] 标识（与 MealRecord.createdBy 同一套身份）。
 * [deletedAt] 为软删除时间戳：删除同样要同步给所有人，不能只在本机消失。
 */
data class Comment(
    val id: String,
    val mealId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.PENDING,
) {
    val isDeleted: Boolean get() = deletedAt != null
}
