package com.guolongli.dinner.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并规则本身：三个数决定一份远端文件该不该落地。 */
class MergePolicyTest {

    @Test
    fun `本地没有就收下`() {
        assertEquals(ApplyResult.APPLIED, MergePolicy.decide(null, false, 100L))
    }

    @Test
    fun `本地更新就不要远端的`() {
        assertEquals(ApplyResult.STALE, MergePolicy.decide(200L, false, 100L))
    }

    @Test
    fun `远端更新就覆盖`() {
        assertEquals(ApplyResult.APPLIED, MergePolicy.decide(100L, false, 200L))
    }

    @Test
    fun `时间相同且本地干净算没变化`() {
        assertEquals(ApplyResult.UNCHANGED, MergePolicy.decide(100L, false, 100L))
    }

    @Test
    fun `时间相同但本地脏就保留本地`() {
        assertEquals(ApplyResult.STALE, MergePolicy.decide(100L, true, 100L))
    }
}

/** 云端 JSON 的读写：这是跨设备唯一的契约，字段错了别人就看不到你的记录。 */
class RecordDocumentTest {

    @Test
    fun `编解码一轮，字段一个不少`() {
        val doc = RecordDocument(
            id = "rec-1",
            authorId = "user-1",
            authorName = "李国龙",
            type = "DINNER",
            note = "番茄牛腩炖了两个小时",
            rating = 5,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
            starred = true,
            deleted = false,
            media = listOf(
                MediaDoc(0, "PHOTO", "/apps/dinner/media/media-rec-1-0.jpg", null),
                MediaDoc(1, "VIDEO", "/apps/dinner/media/media-rec-1-1.mp4", 15_000L),
            ),
            comments = listOf(
                CommentDoc("c-1", "user-2", "王秀英", "少放点盐", 1_700_000_050_000L, false),
            ),
        )

        val parsed = RecordDocument.parse(doc.toJson())!!

        assertEquals(doc.id, parsed.id)
        assertEquals(doc.authorId, parsed.authorId)
        assertEquals("中文备注不能乱码", doc.note, parsed.note)
        assertEquals(doc.rating, parsed.rating)
        assertEquals(doc.createdAt, parsed.createdAt)
        assertEquals(doc.updatedAt, parsed.updatedAt)
        assertEquals(doc.starred, parsed.starred)
        assertEquals(2, parsed.media.size)
        assertEquals("VIDEO", parsed.media[1].kind)
        assertEquals(15_000L, parsed.media[1].durationMs)
        assertNull("照片没有时长", parsed.media[0].durationMs)
        assertEquals("/apps/dinner/media/media-rec-1-0.jpg", parsed.media[0].remotePath)
        assertEquals(1, parsed.comments.size)
        assertEquals("少放点盐", parsed.comments[0].text)
    }

    @Test
    fun `空媒体与空评论也能解析`() {
        val doc = RecordDocument(
            id = "rec-2",
            authorId = "u",
            authorName = "我",
            type = "OTHER",
            note = "",
            rating = 0,
            createdAt = 1L,
            updatedAt = 2L,
            starred = false,
            deleted = false,
        )
        val parsed = RecordDocument.parse(doc.toJson())!!
        assertTrue(parsed.media.isEmpty())
        assertTrue(parsed.comments.isEmpty())
    }

    @Test
    fun `墓碑标记要能过一遍网络`() {
        val doc = RecordDocument(
            id = "rec-3",
            authorId = "u",
            authorName = "我",
            type = "DINNER",
            note = "",
            rating = 0,
            createdAt = 1L,
            updatedAt = 9L,
            starred = false,
            deleted = true,
        )
        assertTrue(RecordDocument.parse(doc.toJson())!!.deleted)
    }

    @Test
    fun `文件名与记录 id 一一对应`() {
        assertEquals("rec-abc.json", RecordDocument.fileName("abc"))
        assertEquals("abc", RecordDocument.idOf("rec-abc.json"))
        assertNull(RecordDocument.idOf("note.txt"))
        assertNull(RecordDocument.idOf("abc.json"))
        assertNull(RecordDocument.idOf("rec-.json"))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(RecordDocument.parse("{ not json"))
        assertNull(RecordDocument.parse(""))
        assertNull(RecordDocument.parse("""{"schema":1}"""))
    }

    @Test
    fun `缺字段时用默认值兜住`() = runTest {
        val parsed = RecordDocument.parse("""{"id":"rec-4","updatedAt":7}""")!!
        assertEquals("OTHER", parsed.type)
        assertEquals("", parsed.note)
        assertEquals(0, parsed.rating)
        assertEquals(false, parsed.deleted)
    }
}
