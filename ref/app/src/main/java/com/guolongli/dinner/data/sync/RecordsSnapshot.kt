package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.mapper.normalizeTags
import com.guolongli.dinner.domain.model.Comment
import com.guolongli.dinner.domain.model.GeoLocation
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.SyncState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 记录快照的结构化格式。
 *
 * 用显式字段而不是直接序列化领域模型，保证网盘上的数据格式稳定，
 * 后续领域模型演进时不至于破坏其他成员的兼容性。
 *
 * 2.0.0 起快照只有一种形态：一条记录的媒体一律放在 `media` 数组里（0..9 段），
 * 没有单张照片的旧字段，也没有「media 为空就去 photo_remote_path 捞」的回落。
 * schema 从 1 重新起算，因为不再需要区分历史版本。
 */
@Serializable
data class RecordsSnapshot(
    @SerialName("schema") val schema: Int = CURRENT_SCHEMA,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("records") val records: List<RecordDto> = emptyList(),
    @SerialName("comments") val comments: List<CommentDto> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class RecordDto(
    @SerialName("id") val id: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("meal_type") val mealType: String,
    @SerialName("note") val note: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("rating") val rating: Int? = null,
    /** 0..9 段媒体，照片与视频混排。 */
    @SerialName("media") val media: List<MediaDto> = emptyList(),
    @SerialName("marked") val marked: Boolean = false,
    @SerialName("location") val location: LocationDto? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
)

/** 快照里的位置：只留经纬度与一句人话地址，不带轨迹也不带精度。 */
@Serializable
data class LocationDto(
    @SerialName("lat") val latitude: Double = 0.0,
    @SerialName("lng") val longitude: Double = 0.0,
    @SerialName("address") val address: String = "",
    @SerialName("place") val placeName: String = "",
)

/**
 * 快照里的媒体：只带云端信息，本地路径属于设备私有数据。
 *
 * 字段按 AGENTS「数据」的约定：`remote` / `md5` / `duration_ms`；
 * `thumb_remote` 是同一 schema 内的附加字段（只多不少，老客户端忽略即可），
 * 它让对端能只拉视频封面而不必下载正片，因此**不删**。
 */
@Serializable
data class MediaDto(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = MediaType.IMAGE.name,
    @SerialName("remote") val remote: String? = null,
    @SerialName("md5") val md5: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** 视频封面在网盘上的路径，对端只拉封面就够了。 */
    @SerialName("thumb_remote") val thumbRemote: String? = null,
)

@Serializable
data class CommentDto(
    @SerialName("id") val id: String,
    @SerialName("meal_id") val mealId: String,
    @SerialName("author_id") val authorId: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long? = null,
)

fun RecordDto.toDomain(): MealRecord {
    // 远端记录不含本地路径，需要展示时由 MediaCache 按需下载后回填。
    val media = media.map { dto ->
        MediaItem(
            id = dto.id,
            mediaType = MediaType.fromStorage(dto.type),
            localPath = null,
            remotePath = dto.remote,
            md5 = dto.md5,
            thumbPath = null,
            durationMs = dto.durationMs,
            thumbRemotePath = dto.thumbRemote,
        )
    }
    return MealRecord(
        id = id,
        timestamp = timestamp,
        mealType = MealType.fromStorage(mealType),
        note = note,
        createdBy = createdBy,
        syncState = SyncState.SYNCED,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        rating = rating,
        media = media,
        marked = marked,
        location = location?.toDomain(),
        tags = normalizeTags(tags),
    )
}

private fun LocationDto.toDomain(): GeoLocation? =
    if (GeoLocation.isValid(latitude, longitude)) {
        GeoLocation(
            latitude = latitude,
            longitude = longitude,
            address = address,
            placeName = placeName,
        )
    } else {
        null
    }

private fun GeoLocation.toDto(): LocationDto = LocationDto(
    latitude = latitude,
    longitude = longitude,
    address = address,
    placeName = placeName,
)

fun MealRecord.toDto(): RecordDto = RecordDto(
    id = id,
    timestamp = timestamp,
    mealType = mealType.name,
    note = note,
    createdBy = createdBy,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    rating = rating,
    media = media.map { item ->
        MediaDto(
            id = item.id,
            type = item.mediaType.name,
            remote = item.remotePath,
            md5 = item.md5,
            durationMs = item.durationMs,
            thumbRemote = item.thumbRemotePath,
        )
    },
    marked = marked,
    location = location?.toDto(),
    tags = normalizeTags(tags),
)

fun CommentDto.toDomain(): Comment = Comment(
    id = id,
    mealId = mealId,
    authorId = authorId,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncState = SyncState.SYNCED,
)

fun Comment.toDto(): CommentDto = CommentDto(
    id = id,
    mealId = mealId,
    authorId = authorId,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
