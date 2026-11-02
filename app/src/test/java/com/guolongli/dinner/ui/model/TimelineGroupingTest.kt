package com.guolongli.dinner.ui.model

import com.guolongli.dinner.ui.theme.AvatarBlue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 时间线的分组与筛选。
 *
 * 这些逻辑原来是写在 Composable 里的，只能靠「装上跑一遍看对不对」；
 * 抽成纯函数之后，时区、跨月、筛空这些边角可以直接盯住。
 */
class TimelineGroupingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun record(
        id: String,
        createdAt: Long,
        type: RecordType = RecordType.DINNER,
        starred: Boolean = false,
    ) = RecordUiModel(
        id = id,
        authorId = "me",
        authorName = "我",
        authorInitial = "我",
        authorColor = AvatarBlue,
        type = type,
        createdAt = createdAt,
        timeLabel = "",
        note = "",
        rating = 0,
        starred = starred,
    )

    @Test
    fun `同一天的多条只出一次日期标题`() {
        val items = buildTimeline(
            records = listOf(
                record("a", at(2026, 10, 18, 8)),
                record("b", at(2026, 10, 18, 12)),
                record("c", at(2026, 10, 18, 19)),
            ),
            filter = TimelineFilter.ALL,
        )

        val headers = items.filterIsInstance<TimelineItem.DayHeader>()
        assertEquals(1, headers.size)
        assertEquals(3, headers.single().count)
        assertEquals(4, items.size)
    }

    @Test
    fun `最新的排在最前，同一天内也按时间倒序`() {
        val items = buildTimeline(
            records = listOf(
                record("old", at(2026, 10, 17, 9)),
                record("noon", at(2026, 10, 18, 12)),
                record("evening", at(2026, 10, 18, 20)),
            ),
            filter = TimelineFilter.ALL,
        )

        val ids = items.filterIsInstance<TimelineItem.RecordRow>().map { it.record.id }
        assertEquals(listOf("evening", "noon", "old"), ids)
    }

    @Test
    fun `筛选星标后组头数量跟着变`() {
        val items = buildTimeline(
            records = listOf(
                record("a", at(2026, 10, 18, 8), starred = true),
                record("b", at(2026, 10, 18, 12)),
            ),
            filter = TimelineFilter.STARRED,
        )

        assertEquals(1, items.filterIsInstance<TimelineItem.DayHeader>().single().count)
        assertEquals("a", items.filterIsInstance<TimelineItem.RecordRow>().single().record.id)
    }

    @Test
    fun `按类型筛选只留那一种`() {
        val items = buildTimeline(
            records = listOf(
                record("breakfast", at(2026, 10, 18, 8), type = RecordType.BREAKFAST),
                record("scenery", at(2026, 10, 18, 18), type = RecordType.SCENERY),
            ),
            filter = TimelineFilter.SCENERY,
        )

        val rows = items.filterIsInstance<TimelineItem.RecordRow>()
        assertEquals(1, rows.size)
        assertEquals(RecordType.SCENERY, rows.single().record.type)
    }

    @Test
    fun `筛完为空时返回空列表，界面据此显示回全部`() {
        val items = buildTimeline(
            records = listOf(record("a", at(2026, 10, 18, 8), type = RecordType.LUNCH)),
            filter = TimelineFilter.SPORT,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `只在同一个月里滚动时不给月份条`() {
        val items = buildTimeline(
            records = listOf(
                record("a", at(2026, 10, 3, 8)),
                record("b", at(2026, 10, 18, 8)),
            ),
            filter = TimelineFilter.ALL,
        )

        assertTrue(buildMonthJumps(items).isEmpty())
    }

    @Test
    fun `跨月时给出月份条，并指到该月第一天所在的位置`() {
        val items = buildTimeline(
            records = listOf(
                record("oct", at(2026, 10, 18, 8)),
                record("sep", at(2026, 9, 20, 8)),
            ),
            filter = TimelineFilter.ALL,
        )

        val jumps = buildMonthJumps(items)
        assertEquals(2, jumps.size)
        assertEquals("2026年10月", jumps[0].label)
        assertEquals("2026年9月", jumps[1].label)
        // 指向的那一格必须真是该月的日期标题，点下去才滚得到
        val target = items[jumps[1].itemIndex]
        assertTrue(target is TimelineItem.DayHeader)
        assertEquals(dayIndex(at(2026, 9, 20, 8)), (target as TimelineItem.DayHeader).dayKey)
    }

    @Test
    fun `今天昨天前天用相对说法，再早给日期`() {
        val today = at(2026, 10, 18, 20)

        assertEquals("今天", dayLabel(at(2026, 10, 18, 8), today))
        assertEquals("昨天", dayLabel(at(2026, 10, 17, 23), today))
        assertEquals("前天", dayLabel(at(2026, 10, 16, 9), today))
        assertEquals("10月14日", dayLabel(at(2026, 10, 14, 9), today))
    }

    /** 跨夏令时的日子也要按自然日分，不能按「86400000 毫秒」硬除 */
    @Test
    fun `同一天的凌晨与深夜落在同一个分组里`() {
        assertEquals(dayIndex(at(2026, 10, 18, 0)), dayIndex(at(2026, 10, 18, 23)))
    }
}
