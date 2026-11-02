package com.guolongli.dinner.data.sync

import com.guolongli.dinner.domain.model.GeoLocation
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.SyncState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照格式的往返。
 *
 * 网盘上的 records.json 是所有成员共享的唯一真相，
 * 因此必须保证：领域模型与 DTO 之间的字段不丢、不缺省、不把本地路径带上去；
 * 可选字段缺失时按默认值读出来，不让一条字段不全的记录把整个时间线打不开。
 */
class RecordsSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun record(media: List<MediaItem>) = MealRecord(
        id = "r1",
        timestamp = 1_000L,
        mealType = MealType.SCENERY,
        note = "傍晚的海",
        createdBy = "u1",
        syncState = SyncState.SYNCED,
        updatedAt = 2_000L,
        deletedAt = null,
        rating = 4,
        media = media,
        marked = true,
    )

    @Test
    fun `多媒体随快照往返`() {
        val media = listOf(
            MediaItem(id = "a", mediaType = MediaType.IMAGE, remotePath = "/apps/dinner/a.jpg"),
            MediaItem(
                id = "b",
                mediaType = MediaType.VIDEO,
                remotePath = "/apps/dinner/b.mp4",
                durationMs = 8_000,
            ),
        )
        val payload = json.encodeToString(RecordsSnapshot.serializer(), RecordsSnapshot(records = listOf(record(media).toDto())))
        val decoded = json.decodeFromString(RecordsSnapshot.serializer(), payload)
            .records.single().toDomain()

        assertEquals(listOf("a", "b"), decoded.media.map { it.id })
        assertEquals(MediaType.VIDEO, decoded.media[1].mediaType)
        assertEquals(8_000L, decoded.media[1].durationMs)
        assertEquals(MealType.SCENERY, decoded.mealType)
        assertTrue(decoded.marked)
    }

    @Test
    fun `快照里的媒体不带本地路径`() {
        val dto = record(listOf(MediaItem(id = "a", mediaType = MediaType.IMAGE, localPath = "/local/a.jpg")))
            .toDto()
            .media
            .single()
        assertNull(dto.remote)
    }

    @Test
    fun `纯文字记录不产生媒体段`() {
        val dto = record(emptyList()).toDto()
        assertTrue(dto.media.isEmpty())
        assertTrue(dto.toDomain().media.isEmpty())
    }

    @Test
    fun `快照版本号是 1`() {
        assertEquals(1, RecordsSnapshot.CURRENT_SCHEMA)
    }

    @Test
    fun `位置与视频封面随快照往返`() {
        val media = listOf(
            MediaItem(
                id = "v",
                mediaType = MediaType.VIDEO,
                remotePath = "/apps/dinner/v.mp4",
                thumbRemotePath = "/apps/dinner/v.cover.jpg",
            ),
        )
        val located = record(media).copy(
            location = GeoLocation(
                latitude = 35.7,
                longitude = 116.4,
                address = "山东省某市某区某路 1 号",
                placeName = "某餐厅",
            ),
        )
        val payload = json.encodeToString(
            RecordsSnapshot.serializer(),
            RecordsSnapshot(records = listOf(located.toDto())),
        )
        val decoded = json.decodeFromString(RecordsSnapshot.serializer(), payload)
            .records.single().toDomain()

        assertEquals("某餐厅", decoded.location?.placeName)
        assertEquals(35.7, decoded.location!!.latitude, 0.0)
        assertEquals("/apps/dinner/v.cover.jpg", decoded.media.single().thumbRemotePath)
    }

    @Test
    fun `快照缺位置与封面字段也能读出来`() {
        // 只有必填字段与媒体数组：位置与封面缺失，按可空默认值读出来。
        val payload = """
            {"updated_at":1,"records":[
              {"id":"old","timestamp":1,"meal_type":"DINNER","note":"n",
               "created_by":"u1","updated_at":1,
               "media":[{"id":"v","type":"VIDEO","remote":"/apps/dinner/v.mp4"}]}
            ]}
        """.trimIndent()

        val decoded = json.decodeFromString(RecordsSnapshot.serializer(), payload)
            .records.single().toDomain()

        assertNull(decoded.location)
        assertNull(decoded.media.single().thumbRemotePath)
    }

    @Test
    fun `标签随快照往返`() {
        val tagged = record(emptyList()).copy(tags = listOf("探店", "家常", "探店"))
        val payload = json.encodeToString(
            RecordsSnapshot.serializer(),
            RecordsSnapshot(records = listOf(tagged.toDto())),
        )
        val decoded = json.decodeFromString(RecordsSnapshot.serializer(), payload)
            .records.single().toDomain()

        // 往返途中会归一化：去重、去空白。
        assertEquals(listOf("探店", "家常"), decoded.tags)
    }

    @Test
    fun `快照缺标签字段也能读出来`() {
        // 只有必填字段：标签缺失，按空列表读出来。
        val payload = """
            {"updated_at":1,"records":[
              {"id":"old","timestamp":1,"meal_type":"DINNER","note":"n",
               "created_by":"u1","updated_at":1}
            ]}
        """.trimIndent()

        val decoded = json.decodeFromString(RecordsSnapshot.serializer(), payload)
            .records.single().toDomain()

        assertTrue(decoded.tags.isEmpty())
    }
}
