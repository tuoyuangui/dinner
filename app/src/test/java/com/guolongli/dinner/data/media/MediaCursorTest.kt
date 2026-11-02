package com.guolongli.dinner.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体补齐时的分页游标。
 *
 * 为什么值得单独测：游标一旦不严格单调，**整段媒体会被静默跳过**——
 * 时间线上少几张图，而且没有任何报错，只会觉得「同步好像没把照片带回来」。
 */
class MediaCursorTest {

    @Test
    fun `初始游标在所有记录之前`() {
        val cursor = MediaCursor()
        assertEquals(0L, cursor.createdAt)
        assertEquals(-1, cursor.position)
        assertEquals("", cursor.recordId)
    }

    @Test
    fun `时间变大时整个游标跟着走`() {
        val cursor = MediaCursor().advance(100L, 0, "rec-b")
        assertEquals(100L, cursor.createdAt)
        assertEquals(0, cursor.position)
        assertEquals("rec-b", cursor.recordId)
    }

    @Test
    fun `时间相同但序号更大时只推进序号`() {
        val cursor = MediaCursor(100L, 1, "rec-a").advance(100L, 2, "rec-b")
        assertEquals(100L, cursor.createdAt)
        assertEquals(2, cursor.position)
        assertEquals("rec-b", cursor.recordId)
    }

    @Test
    fun `时间和序号都相同时靠记录 id 区分`() {
        val cursor = MediaCursor(100L, 0, "rec-a").advance(100L, 0, "rec-b")
        assertEquals(100L, cursor.createdAt)
        assertEquals(0, cursor.position)
        assertEquals("rec-b", cursor.recordId)
    }

    @Test
    fun `游标绝不后退`() {
        val start = MediaCursor(100L, 2, "rec-c")
        // 时间更早：不许回退
        assertEquals(start, start.advance(50L, 0, "rec-a"))
        // 时间相同但序号更小：不许回退
        assertEquals(start, start.advance(100L, 1, "rec-z"))
        // 全都相同：不许回退
        assertEquals(start, start.advance(100L, 2, "rec-c"))
    }

    @Test
    fun `同毫秒创建的记录按 id 依次推进不漏`() {
        var cursor = MediaCursor()
        val ids = listOf("rec-a", "rec-b", "rec-c")
        for (id in ids) {
            cursor = cursor.advance(1000L, 0, id)
        }
        assertEquals("rec-c", cursor.recordId)
        // 每一步都必须真的推进了，否则中间那条会被跳过
        var c = MediaCursor()
        val seen = mutableListOf<String>()
        for (id in ids) {
            assertTrue("游标必须严格前进", c.recordId < id)
            seen += id
            c = c.advance(1000L, 0, id)
        }
        assertEquals(ids, seen)
    }
}
