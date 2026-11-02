package com.guolongli.dinner.data.mapper

import com.guolongli.dinner.core.RecordLimits
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.local.db.entity.MealRecordEntity
import com.guolongli.dinner.domain.model.GeoLocation
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.SyncState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 记录实体 <-> 领域模型。
 *
 * 媒体只走 media_json，位置只走 location_json，标签只走 tags_json。
 * 2.0.0 起表里没有单媒体旧列，这里也就不存在「旧列兜底」这段逻辑。
 */
fun MealRecordEntity.toDomain(): MealRecord = MealRecord(
    id = id,
    timestamp = timestamp,
    mealType = MealType.fromStorage(mealType),
    note = note,
    createdBy = createdBy,
    syncState = SyncState.fromStorage(syncState),
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    rating = rating,
    media = decodeMedia(mediaJson),
    marked = marked,
    localRemovedAt = localRemovedAt,
    location = decodeLocation(locationJson),
    tags = decodeTags(tagsJson),
)

fun MealRecord.toEntity(): MealRecordEntity = MealRecordEntity(
    id = id,
    timestamp = timestamp,
    mealType = mealType.name,
    note = note,
    createdBy = createdBy,
    syncState = syncState.name,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    rating = rating,
    localRemovedAt = localRemovedAt,
    mediaJson = encodeMedia(media),
    marked = marked,
    locationJson = encodeLocation(location),
    tagsJson = encodeTags(tags),
)

private val metaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 位置编码进一列 JSON。
 *
 * 坏数据一律回落成「没有位置」，绝不让一条手改过的记录把整个时间线打不开。
 */
private fun encodeLocation(location: GeoLocation?): String {
    val safe = location
        ?.takeIf { GeoLocation.isValid(it.latitude, it.longitude) }
        ?: return ""
    return runCatching {
        metaJson.encodeToString(GeoLocation.serializer(), safe)
    }.getOrDefault("")
}

private fun decodeLocation(raw: String): GeoLocation? {
    if (raw.isBlank()) return null
    return runCatching {
        metaJson.decodeFromString(GeoLocation.serializer(), raw)
    }.getOrNull()
        ?.takeIf { GeoLocation.isValid(it.latitude, it.longitude) }
        .also { if (it == null) Logger.w("位置 JSON 解析失败，按无位置处理") }
}

private val tagsSerializer = ListSerializer(String.serializer())

/**
 * 标签编码进一列 JSON。
 *
 * 标签统一归一化：去空白、去重、每条上限 [MAX_TAG_LENGTH] 字符、
 * 最多 [MAX_TAG_COUNT] 条。坏数据一律回落成「没有标签」。
 */
private fun encodeTags(tags: List<String>): String {
    val normalized = normalizeTags(tags)
    if (normalized.isEmpty()) return ""
    return runCatching {
        metaJson.encodeToString(tagsSerializer, normalized)
    }.getOrDefault("")
}

private fun decodeTags(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        metaJson.decodeFromString(tagsSerializer, raw)
    }.getOrDefault(emptyList())
        .let { normalizeTags(it) }
        .also { if (it.isEmpty()) Logger.w("标签 JSON 解析失败，按无标签处理") }
}

/** 标签归一化：写入侧与解码侧共用同一套规则，避免两端口径漂移。 */
internal fun normalizeTags(tags: List<String>): List<String> = tags
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .map { it.take(RecordLimits.MAX_TAG_LENGTH) }
    .distinct()
    .take(RecordLimits.MAX_TAGS)

