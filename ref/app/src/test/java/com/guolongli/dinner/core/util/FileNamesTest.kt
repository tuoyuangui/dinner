package com.guolongli.dinner.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 媒体文件名：`记录创建时间.jpg`，形如 `2026-10-29_11_22_35.jpg`。
 *
 * 覆盖：结构、纯 ASCII、同秒去重后缀、扩展名兜底，以及三种历史命名的日期反解。
 */
class FileNamesTest {

    /** 取一个固定本地时刻，避免跨时区/跨日边界不稳定。 */
    private fun localDateTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun dayStartOf(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun timestampedMedia_buildsCreationTimeStructure() {
        val name = FileNames.timestampedMedia(localDateTime(2026, 10, 29, 11, 22, 35))
        assertEquals("2026-10-29_11_22_35.jpg", name)
    }

    @Test
    fun timestampedMedia_usesActualExtension() {
        // 视频保留白名单扩展名，不能一律写成 jpg
        assertEquals(
            "2026-10-29_11_22_35.mp4",
            FileNames.timestampedMedia(localDateTime(2026, 10, 29, 11, 22, 35), "mp4"),
        )
    }

    @Test
    fun timestampedMedia_isPureAscii() {
        val name = FileNames.timestampedMedia(localDateTime(2026, 10, 29, 11, 22, 35))
        assertTrue("文件名应纯 ASCII，实际 $name", name.all { it.code in 32..126 })
        assertFalse("不应含中文", name.any { it.code > 127 })
    }

    @Test
    fun timestampedMedia_distinctSecondsGiveDistinctNames() {
        val a = FileNames.timestampedMedia(localDateTime(2026, 10, 29, 11, 22, 35))
        val b = FileNames.timestampedMedia(localDateTime(2026, 10, 29, 11, 22, 36))
        assertTrue("相差一秒也应是两个文件", a != b)
    }

    @Test
    fun withDedupSuffix_appendsSequenceOnlyWhenNeeded() {
        val millis = localDateTime(2026, 10, 29, 11, 22, 35)
        // 第一段不加后缀，保持干净
        assertEquals("2026-10-29_11_22_35.jpg", FileNames.withDedupSuffix(millis, 1))
        assertEquals("2026-10-29_11_22_35_2.jpg", FileNames.withDedupSuffix(millis, 2))
        assertEquals("2026-10-29_11_22_35_9.jpg", FileNames.withDedupSuffix(millis, 9))
    }

    /**
     * 新设备兜底扫描要靠文件名反推日期。
     *
     * 这条守的是一个真实缺陷：兜底逻辑曾经用 `substringBefore('_')` 解析，
     * 而带下划线的命名会被当成时间戳去解析，于是每一张都被丢掉，兜底永远返回空。
     */
    @Test
    fun dayMillisOf_readsTimestampedName() {
        val day = FileNames.dayMillisOf("2026-10-29_11_22_35.jpg")
        assertEquals("应取当天 0 点", dayStartOf(2026, 10, 29), day)
    }

    @Test
    fun dayMillisOf_readsDedupSuffixedName() {
        // 带去重后缀也要认得出来，否则取回时会漏掉同一秒的第 2 段之后
        val day = FileNames.dayMillisOf("2026-10-29_11_22_35_3.mp4")
        assertEquals("后缀不该影响日期反解", dayStartOf(2026, 10, 29), day)
    }

    @Test
    fun dayMillisOf_readsFullPath() {
        val day = FileNames.dayMillisOf(
            "/apps/dinner/group_x/members/138/meals/2026/10/2026-10-29_11_22_35.jpg",
        )
        assertEquals(dayStartOf(2026, 10, 29), day)
    }

    /**
     * 上一代的 `yyyy-MM-dd-<user>-<n>.<ext>` 不再认。
     *
     * 它前十个字符也是合法日期，只按前缀判断（`find` / `take(10)`）会把它
     * 当成媒体文件；这里要求整名匹配现行格式，于是它落到「认不出来」那一档。
     */
    @Test
    fun dayMillisOf_rejectsPreviousUserNameFormat() {
        assertEquals(null, FileNames.dayMillisOf("2026-10-28-xiaoming-2.jpg"))
    }

    /** 更早的 `<timestamp>_<hash>.<ext>` 同样不再认——它也不是现行格式。 */
    @Test
    fun dayMillisOf_rejectsLegacyTimestampName() {
        val stamp = localDateTime(2026, 3, 5, hour = 18)
        assertEquals(null, FileNames.dayMillisOf("${stamp}_a1b2c3.jpg"))
    }

    @Test
    fun dayMillisOf_rejectsUnparsableName() {
        assertEquals(null, FileNames.dayMillisOf("readme.txt"))
        assertEquals(null, FileNames.dayMillisOf("avatar.jpg"))
        assertEquals(null, FileNames.dayMillisOf(""))
        // 只有日期没有时间，不是媒体文件
        assertEquals(null, FileNames.dayMillisOf("2026-10-29.jpg"))
        // 时间缺秒，不是媒体文件
        assertEquals(null, FileNames.dayMillisOf("2026-10-29_11_22.jpg"))
    }
}
