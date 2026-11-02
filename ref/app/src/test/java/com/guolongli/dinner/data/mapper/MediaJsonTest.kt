package com.guolongli.dinner.data.mapper

import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体列的编解码。
 *
 * 这一层坏掉的后果是「照片凭空消失」，所以除了往返一致，
 * 还要覆盖脏数据（截断的 JSON）与未知类型的回落。
 */
class MediaJsonTest {

    private fun image(id: String, local: String? = null, remote: String? = null) = MediaItem(
        id = id,
        mediaType = MediaType.IMAGE,
        localPath = local,
        remotePath = remote,
        md5 = remote?.let { "md5-$id" },
    )

    @Test
    fun `空列表编码为空串`() {
        assertEquals("", encodeMedia(emptyList()))
    }

    @Test
    fun `多段媒体往返一致`() {
        val media = listOf(
            image("a", local = "/local/a.jpg", remote = "/apps/dinner/meals/2026/10/a.jpg"),
            MediaItem(
                id = "b",
                mediaType = MediaType.VIDEO,
                localPath = "/local/b.mp4",
                thumbPath = "/local/b.thumb.jpg",
                durationMs = 12_000,
            ),
        )
        val decoded = decodeMedia(encodeMedia(media))
        assertEquals(media, decoded)
    }

    @Test
    fun `媒体顺序即展示顺序`() {
        val media = listOf(image("1"), image("2"), image("3"))
        val decoded = decodeMedia(encodeMedia(media))
        assertEquals(listOf("1", "2", "3"), decoded.map { it.id })
    }

    @Test
    fun `脏 JSON 不抛异常，按无媒体处理`() {
        assertTrue(decodeMedia("[{\"id\":\"broken\"").isEmpty())
    }

    @Test
    fun `纯文字记录没有任何媒体`() {
        assertTrue(decodeMedia("").isEmpty())
    }

    @Test
    fun `未知的媒体类型按图片处理`() {
        val item = decodeMedia(
            """
            [{"id":"x","type":"HOLOGRAM","remote":"/apps/dinner/x.jpg"}]
            """.trimIndent()
        ).single()
        assertEquals(MediaType.IMAGE, item.mediaType)
        assertEquals("/apps/dinner/x.jpg", item.remotePath)
    }
}
