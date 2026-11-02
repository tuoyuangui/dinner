package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.local.CommentEntity
import com.guolongli.dinner.data.local.MediaEntity
import com.guolongli.dinner.data.local.RecordEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条记录在网盘上的样子：`/apps/dinner/records/rec-<id>.json`。
 *
 * 为什么一条记录一个文件、而不是所有人往一个大 JSON 里追加：
 * 网盘只有「整文件覆盖」，两个人同时写同一份就会互相吞掉；一条一文件，
 * 冲突范围最小，合并规则也简单——谁 updatedAt 更新谁赢。
 *
 * 媒体不内嵌，只存远端路径，照片视频上传后各占一个文件。
 */
data class RecordDocument(
    val id: String,
    val authorId: String,
    val authorName: String,
    /** 作者头像在网盘上的路径；没有就回落到首字头像 */
    val authorAvatar: String? = null,
    val type: String,
    val note: String,
    val rating: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val starred: Boolean,
    val deleted: Boolean,
    /** 位置：null 表示这条没记位置 */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val place: String? = null,
    val media: List<MediaDoc> = emptyList(),
    val comments: List<CommentDoc> = emptyList(),
) {

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("id", id)
        root.put("authorId", authorId)
        root.put("authorName", authorName)
        root.put("authorAvatar", authorAvatar ?: JSONObject.NULL)
        root.put("type", type)
        root.put("note", note)
        root.put("rating", rating)
        root.put("createdAt", createdAt)
        root.put("updatedAt", updatedAt)
        root.put("starred", starred)
        root.put("deleted", deleted)
        root.put("latitude", latitude ?: JSONObject.NULL)
        root.put("longitude", longitude ?: JSONObject.NULL)
        root.put("place", place ?: JSONObject.NULL)

        val mediaArray = JSONArray()
        media.forEach { item ->
            mediaArray.put(
                JSONObject()
                    .put("position", item.position)
                    .put("kind", item.kind)
                    .put("remotePath", item.remotePath ?: JSONObject.NULL)
                    .put("durationMs", item.durationMs ?: JSONObject.NULL),
            )
        }
        root.put("media", mediaArray)

        val commentArray = JSONArray()
        comments.forEach { item ->
            commentArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("authorId", item.authorId)
                    .put("authorName", item.authorName)
                    .put("text", item.text)
                    .put("createdAt", item.createdAt)
                    .put("deleted", item.deleted),
            )
        }
        root.put("comments", commentArray)
        return root.toString()
    }

    companion object {
        const val SCHEMA = 1
        const val PREFIX = "rec-"
        const val SUFFIX = ".json"

        fun fileName(id: String): String = "$PREFIX$id$SUFFIX"

        /** 从文件名反解记录 id；不是记录文件返回 null */
        fun idOf(fileName: String): String? {
            if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null
            val id = fileName.removePrefix(PREFIX).removeSuffix(SUFFIX)
            return id.ifBlank { null }
        }

        /** 由本地实体拼出文档；媒体/评论按 position、时间排好序 */
        fun from(
            record: RecordEntity,
            media: List<MediaEntity>,
            comments: List<CommentEntity>,
            authorAvatar: String? = null,
        ): RecordDocument = RecordDocument(
            id = record.id,
            authorId = record.authorId,
            authorName = record.authorName,
            authorAvatar = authorAvatar,
            type = record.type,
            note = record.note,
            rating = record.rating,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            starred = record.starred,
            deleted = record.deleted,
            latitude = record.latitude,
            longitude = record.longitude,
            place = record.place,
            media = media.sortedBy { it.position }.map {
                MediaDoc(it.position, it.kind, it.remotePath, it.durationMs)
            },
            comments = comments.filterNot { it.deleted }.sortedBy { it.createdAt }.map {
                CommentDoc(it.id, it.authorId, it.authorName, it.text, it.createdAt, it.deleted)
            },
        )

        /** 解析远端文件；字段缺失时用默认值，坏文件返回 null 而不是抛异常 */
        fun parse(json: String): RecordDocument? = try {
            val root = JSONObject(json)
            val id = root.optString("id").ifBlank { null } ?: return null
            val mediaArray = root.optJSONArray("media") ?: JSONArray()
            val commentArray = root.optJSONArray("comments") ?: JSONArray()
            RecordDocument(
                id = id,
                authorId = root.optString("authorId"),
                authorName = root.optString("authorName"),
                authorAvatar = root.optString("authorAvatar").ifBlank { null },
                type = root.optString("type", "OTHER"),
                note = root.optString("note"),
                rating = root.optInt("rating"),
                createdAt = root.optLong("createdAt"),
                updatedAt = root.optLong("updatedAt"),
                starred = root.optBoolean("starred"),
                deleted = root.optBoolean("deleted"),
                latitude = if (root.isNull("latitude")) null else root.optDouble("latitude"),
                longitude = if (root.isNull("longitude")) null else root.optDouble("longitude"),
                place = root.optString("place").ifBlank { null },
                media = (0 until mediaArray.length()).mapNotNull { index ->
                    mediaArray.optJSONObject(index)?.let {
                        MediaDoc(
                            position = it.optInt("position"),
                            kind = it.optString("kind", "PHOTO"),
                            remotePath = it.optString("remotePath").ifBlank { null },
                            durationMs = if (it.isNull("durationMs")) null else it.optLong("durationMs"),
                        )
                    }
                },
                comments = (0 until commentArray.length()).mapNotNull { index ->
                    commentArray.optJSONObject(index)?.let {
                        CommentDoc(
                            id = it.optString("id"),
                            authorId = it.optString("authorId"),
                            authorName = it.optString("authorName"),
                            text = it.optString("text"),
                            createdAt = it.optLong("createdAt"),
                            deleted = it.optBoolean("deleted"),
                        )
                    }
                }.filter { it.id.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class MediaDoc(
    val position: Int,
    val kind: String,
    val remotePath: String?,
    val durationMs: Long?,
)

data class CommentDoc(
    val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long,
    val deleted: Boolean,
)
