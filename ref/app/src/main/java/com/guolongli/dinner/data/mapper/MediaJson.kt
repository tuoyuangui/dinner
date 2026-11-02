package com.guolongli.dinner.data.mapper

import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 记录媒体的列内编码。
 *
 * 媒体是记录的从属数据：永远跟着记录整体读写，从不单独查询，
 * 因此不值得为它开一张关系表 + @Relation。用一列 JSON 存下来，
 * 读写都只碰一次记录行。
 *
 * 只有一种存储格式：`media_json`。2.0.0 起不再有单媒体旧列，
 * 也没有「media_json 为空就去旧列捞」的回落路径。
 */
@Serializable
private data class MediaJsonItem(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = MediaType.IMAGE.name,
    @SerialName("local") val local: String? = null,
    @SerialName("remote") val remote: String? = null,
    @SerialName("md5") val md5: String? = null,
    @SerialName("thumb") val thumb: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** 视频封面在网盘上的路径。 */
    @SerialName("thumb_remote") val thumbRemote: String? = null,
)

private val mediaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val mediaItemsSerializer = ListSerializer(MediaJsonItem.serializer())

internal fun encodeMedia(media: List<MediaItem>): String {
    if (media.isEmpty()) return ""
    return mediaJson.encodeToString(
        mediaItemsSerializer,
        media.map { item ->
            MediaJsonItem(
                id = item.id,
                type = item.mediaType.name,
                local = item.localPath,
                remote = item.remotePath,
                md5 = item.md5,
                thumb = item.thumbPath,
                durationMs = item.durationMs,
                thumbRemote = item.thumbRemotePath,
            )
        },
    )
}

/**
 * 解码媒体列表；解析失败一律回落成「没有媒体」。
 *
 * 解析失败绝不能抛给上层：一条坏数据不该让整个时间线打不开。
 */
internal fun decodeMedia(raw: String): List<MediaItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching { mediaJson.decodeFromString(mediaItemsSerializer, raw) }
        .map { items ->
            items.map { item ->
                MediaItem(
                    id = item.id,
                    mediaType = MediaType.fromStorage(item.type),
                    localPath = item.local,
                    remotePath = item.remote,
                    md5 = item.md5,
                    thumbPath = item.thumb,
                    durationMs = item.durationMs,
                    thumbRemotePath = item.thumbRemote,
                )
            }
        }
        .onFailure { Logger.w("媒体 JSON 解析失败，按无媒体处理：${it.message}") }
        .getOrDefault(emptyList())
}
