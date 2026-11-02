package com.guolongli.dinner.ui.util

import com.guolongli.dinner.core.util.Ascii
import java.util.Calendar

/**
 * 日期与时间文案。
 *
 * 使用 [Calendar] 而不是 java.time，避免依赖 API 26 才有的类与脱糖配置；
 * 本项目 minSdk 可能低于 26，Calendar 在任何版本上都可用。
 * 所有换算都按设备本地时区进行，与用户看到的日期一致。
 */
object DateText {

    private val WEEKDAY_LABELS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    /**
     * 形如 2026-10-28 的本地日期键，用于按天分组。
     *
     * 必须是 ASCII：[dayKey] 有三个用途，都要求它不随设备语言变形——
     * 1. 当日历格子与记录分组的相等判据；
     * 2. 作为路由参数传给记录列表（URL 里不能出现阿拉伯-印度数字）；
     * 3. `CalendarViewModel` 里用 `key > todayKey` 做字符串比较判断「还没到」。
     * `String.format` 在阿拉伯语 / 波斯语设备上会给出 `٢٠٢٦-١٠-٢٨`：
     * 比较结果跨数字系统直接错乱，`parseDayKey` 的 `toIntOrNull` 也解析不了。
     */
    fun dayKey(millis: Long): String {
        val c = calendar(millis)
        return buildDayKey(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** ASCII 补零的日期键：与 [dayKeyOf] 共用同一套拼装，避免两条路径格式漂移。 */
    private fun buildDayKey(year: Int, month1based: Int, dayOfMonth: Int): String =
        Ascii.pad(year, 4) + "-" + Ascii.pad(month1based, 2) + "-" + Ascii.pad(dayOfMonth, 2)

    /** 归一化到当地当天 00:00:00.000。 */
    fun startOfDay(millis: Long): Long {
        val c = calendar(millis)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * 取 [dateSource] 的年月日与 [timeSource] 的时分，组成新的时间戳。
     *
     * 编辑已有记录时保留原本的用餐时刻，新建时取当前时刻，
     * 避免「只改日期」把时间悄悄改成现在。
     */
    fun combineDateAndTime(dateSource: Long, timeSource: Long): Long {
        val date = calendar(dateSource)
        val time = calendar(timeSource)
        val merged = calendar(dateSource)
        merged.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
        merged.set(Calendar.MINUTE, time.get(Calendar.MINUTE))
        merged.set(Calendar.SECOND, 0)
        merged.set(Calendar.MILLISECOND, 0)
        // 显式读取一次，保证 date 被使用（同时防止跨时区偏移）。
        merged.set(Calendar.YEAR, date.get(Calendar.YEAR))
        return merged.timeInMillis
    }

    /** 形如 08:12。 */
    fun timeText(millis: Long): String {
        val c = calendar(millis)
        return "%02d:%02d".format(
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
        )
    }

    /**
     * 形如「10月28日 周三」；与当前不同年时带上年份，
     * 形如「2025年10月28日 周三」。
     */
    fun dateLabel(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val c = calendar(millis)
        val sameYear = c.get(Calendar.YEAR) == calendar(nowMillis).get(Calendar.YEAR)
        val base = "%d月%d日 %s".format(
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            WEEKDAY_LABELS[c.get(Calendar.DAY_OF_WEEK) - 1],
        )
        return if (sameYear) base else "%d年%s".format(c.get(Calendar.YEAR), base)
    }

    /**
     * 形如「今天 · 10月28日 周三」/「昨天 · 10月27日 周二」，
     * 更早的日期直接返回 [dateLabel]，不带前缀。
     */
    fun dayTitle(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val base = dateLabel(millis, nowMillis)
        return when (daysAgo(millis, nowMillis)) {
            0 -> "今天 · $base"
            1 -> "昨天 · $base"
            else -> base
        }
    }

    /**
     * 相对天数差。
     *
     * 用「年 * 400 + 年内天数」作序号，避免 DST 导致的 23/25 小时偏移，
     * 跨年时序号差必然大于 1，因此只会走到「更早」分支，结果正确。
     */
    private fun dayIndex(millis: Long): Long {
        val c = calendar(millis)
        return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
    }

    fun daysAgo(millis: Long, nowMillis: Long): Int =
        (dayIndex(nowMillis) - dayIndex(millis)).toInt().coerceAtLeast(0)

    /** 供日期选择器使用的年月日。month 为 0 基，与 android.app.DatePickerDialog 一致。 */
    fun yearOf(millis: Long): Int = calendar(millis).get(Calendar.YEAR)

    fun monthOf(millis: Long): Int = calendar(millis).get(Calendar.MONTH)

    fun dayOfMonthOf(millis: Long): Int = calendar(millis).get(Calendar.DAY_OF_MONTH)

    /** 由选择的年月日构造当天 00:00 的时间戳。month 为 0 基。 */
    fun fromDateParts(year: Int, month: Int, dayOfMonth: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, year)
        c.set(Calendar.MONTH, month)
        c.set(Calendar.DAY_OF_MONTH, dayOfMonth)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 从时间戳取用餐时刻（分钟精度）作为时间来源。 */
    fun timeSourceOf(millis: Long): Long = millis

    /** 照片按年月归档所需的年份。 */
    fun year(millis: Long): Int = yearOf(millis)

    /** 照片按年月归档所需的月份（1 基）。 */
    fun month(millis: Long): Int = calendar(millis).get(Calendar.MONTH) + 1

    // ---------- 月历 ----------

    /** 月历表头：周一到周日。与 [dateLabel] 的「周三」文案同一套叫法。 */
    val WEEKDAY_SINGLE: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 形如「2026年10月」。month 为 1 基。 */
    fun monthTitle(year: Int, month1based: Int): String = "${year}年${month1based}月"

    /** 1 基月份的天数。 */
    fun daysInMonth(year: Int, month1based: Int): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, year)
        c.set(Calendar.MONTH, month1based - 1)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * 当月 1 号在「周一到周日」网格里的列偏移（0..6）。
     *
     * Calendar 里周日是 1，这里把周日挪到最后，符合中文日历习惯。
     */
    fun firstWeekdayOffset(year: Int, month1based: Int): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, year)
        c.set(Calendar.MONTH, month1based - 1)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    /** 由年月日拼出当天的日期键，与 [dayKey] 同一格式（ASCII 补零）。 */
    fun dayKeyOf(year: Int, month1based: Int, dayOfMonth: Int): String =
        buildDayKey(year, month1based, dayOfMonth)

    /** 把 yyyy-MM-dd 解析成当天 00:00 的时间戳；格式不对返回 null。 */
    fun parseDayKey(key: String?): Long? {
        if (key == null || key.length != 10) return null
        val parts = key.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return fromDateParts(year, month - 1, day)
    }
}
