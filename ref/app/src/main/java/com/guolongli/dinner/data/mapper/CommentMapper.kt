package com.guolongli.dinner.data.mapper

import com.guolongli.dinner.data.local.db.entity.CommentEntity
import com.guolongli.dinner.domain.model.Comment
import com.guolongli.dinner.domain.model.SyncState

fun CommentEntity.toDomain(): Comment = Comment(
    id = id,
    mealId = mealId,
    authorId = authorId,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncState = SyncState.fromStorage(syncState),
)

fun Comment.toEntity(): CommentEntity = CommentEntity(
    id = id,
    mealId = mealId,
    authorId = authorId,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncState = syncState.name,
)
