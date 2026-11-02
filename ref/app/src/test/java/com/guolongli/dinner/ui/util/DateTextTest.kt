package com.guolongli.dinner.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 月历与按天筛选依赖的日期换算。
 *
 * 这些函数直接决定「哪一格代表哪一天」「点了一天会筛出哪些记录」，
 * 算错就是整月错位或筛不到东西，因此单测覆盖边界。
 */
class DateTextTest {

    @Test
    fun `月天数按闰年判断`() {
        assertEquals(28, DateText.daysInMonth(2026, 2))
        assertEquals(29, DateText.daysInMonth(2024, 2))
        assertEquals(31, DateText.daysInMonth(2026, 10))
        assertEquals(30, DateText.daysInMonth(2026, 4))
    }

    @Test
    fun `首日偏移按周一开始`() {
        // 2026-10-01 是周四，周一开始的网格里列号应为 3。
        assertEquals(3, DateText.firstWeekdayOffset(2026, 10))
        // 2026-03-01 是周日，应落在最后一列。
        assertEquals(6, DateText.firstWeekdayOffset(2026, 3))
    }

    @Test
    fun `年月标题`() {
        assertEquals("2026年10月", DateText.monthTitle(2026, 10))
        assertEquals("2026年1月", DateText.monthTitle(2026, 1))
    }

    @Test
    fun `由年月日拼出的日期键能原样解析回来`() {
        val key = DateText.dayKeyOf(2026, 10, 28)
        assertEquals("2026-10-28", key)
        val millis = DateText.parseDayKey(key)
        assertEquals(key, DateText.dayKey(millis!!))
    }

    @Test
    fun `非法日期键一律返回 null`() {
        assertNull(DateText.parseDayKey(null))
        assertNull(DateText.parseDayKey(""))
        assertNull(DateText.parseDayKey("2026/10/28"))
        assertNull(DateText.parseDayKey("2026-13-01"))
        assertNull(DateText.parseDayKey("2026-10"))
        assertNull(DateText.parseDayKey("abcd-ef-gh"))
    }

    @Test
    fun `表头是周一到周日七格`() {
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), DateText.WEEKDAY_SINGLE)
    }

    /**
     * 月份有两套基准，混用会让整个日历少一个月：
     * [DateText.monthOf] 给 DatePickerDialog 用（0 基），
     * [DateText.month] 给月历自己用（1 基）。
     */
    @Test
    fun `month 是 1 基而 monthOf 是 0 基`() {
        val october = DateText.parseDayKey("2026-10-15")!!
        assertEquals(10, DateText.month(october))
        assertEquals(9, DateText.monthOf(october))
    }

    /** 月历的日期键必须落在「同一年的同一月」，否则格子与记录对不上。 */
    @Test
    fun `月历日期键与当天时间戳一致`() {
        val now = DateText.parseDayKey("2026-10-28")!!.plus(12 * 60 * 60 * 1000)
        val year = DateText.yearOf(now)
        val month = DateText.month(now)
        assertEquals("2026-10-28", DateText.dayKeyOf(year, month, 28))
    }
}
