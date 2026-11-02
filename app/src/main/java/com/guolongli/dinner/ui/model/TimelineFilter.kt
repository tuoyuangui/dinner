package com.guolongli.dinner.ui.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间线顶部那条筛选。设计稿只画了「全部 / 星标 / 早餐 / 午餐 / 晚餐」五个，
 * 但记录类型本身有九种，筛选条横向可滚，把九种都放进去——
 * 只给三种饭、不给风景和学习，等于把多用途的记录器又缩回记账本。
 */
enum class TimelineFilter(val label: String, val type: RecordType? = null) {
    ALL("全部"),
    STARRED("星标"),
    BREAKFAST("早餐", RecordType.BREAKFAST),
    LUNCH("午餐", RecordType.LUNCH),
    DINNER("晚餐", RecordType.DINNER),
    SNACK("加餐", RecordType.SNACK),
    SCENERY("风景", RecordType.SCENERY),
    STUDY("学习", RecordType.STUDY),
    SPORT("运动", RecordType.SPORT),
    CREATE("创作", RecordType.CREATE),
    OTHER("其他", RecordType.OTHER),
    ;

    fun accepts(record: RecordUiModel): Boolean = when (this) {
        ALL -> true
        STARRED -> record.starred
        else -> record.type == type
    }

    companion object {
        /** 筛完是空的时候，「回到全部」按钮要知道退到哪一格 */
        val DEFAULT = ALL
    }
}

/** 一条时间线里的东西：要么是某天的标题，要么是一条记录 */
sealed interface TimelineItem {
    val key: String

    data class DayHeader(val dayKey: Long, val firstRecordAt: Long, val count: Int) : TimelineItem {
        override val key: String get() = "day-$dayKey"
    }

    data class RecordRow(val record: RecordUiModel) : TimelineItem {
        override val key: String get() = "rec-${record.id}"
    }
}

/** 跨月时顶部那条月份条要的一项 */
data class MonthJump(val year: Int, val month: Int, val label: String, val itemIndex: Int)

/**
 * 把记录摊平成「日期标题 + 记录」的序列。
 *
 * 摊平的好处是列表只有一个，滚动定位、月份跳转、key 稳定都只处理一种数据类型；
 * 分组逻辑放在纯函数里，可以直接用单元测试盯住（见 TimelineGroupingTest）。
 */
fun buildTimeline(
    records: List<RecordUiModel>,
    filter: TimelineFilter,
): List<TimelineItem> {
    val visible = records.filter { filter.accepts(it) }
    if (visible.isEmpty()) return emptyList()

    val result = mutableListOf<TimelineItem>()
    var currentDay: Long? = null
    var bucket = mutableListOf<RecordUiModel>()

    fun flush() {
        val day = currentDay ?: return
        result += TimelineItem.DayHeader(day, bucket.first().createdAt, bucket.size)
        bucket.forEach { result += TimelineItem.RecordRow(it) }
        bucket = mutableListOf()
    }

    visible.sortedByDescending { it.createdAt }.forEach { record ->
        val day = dayIndex(record.createdAt)
        if (day != currentDay) {
            flush()
            currentDay = day
        }
        bucket += record
    }
    flush()
    return result
}

/** 月份条：记录跨越两个月以上才值得给；同月内没什么可跳的 */
fun buildMonthJumps(items: List<TimelineItem>): List<MonthJump> {
    val months = mutableListOf<MonthJump>()
    val seen = mutableSetOf<Pair<Int, Int>>()
    items.forEachIndexed { index, item ->
        if (item !is TimelineItem.DayHeader) return@forEachIndexed
        val calendar = Calendar.getInstance().apply { timeInMillis = item.firstRecordAt }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        if (!seen.add(year to month)) return@forEachIndexed
        months += MonthJump(year, month, "${year}年${month}月", index)
    }
    return if (months.size >= 2) months else emptyList()
}

/** 本机时区下的「第几天」。跨时区 / 夏令时都不会把记录分错组 */
fun dayIndex(millis: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis / 86_400_000L
}

/** 「今天 / 昨天 / 10月28日」 */
fun dayLabel(millis: Long, today: Long = System.currentTimeMillis()): String =
    when (dayIndex(today) - dayIndex(millis)) {
        0L -> "今天"
        1L -> "昨天"
        2L -> "前天"
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(millis))
    }

/** 「10月30日 星期五」——分组标题右侧那半句 */
fun dayTitle(millis: Long): String =
    SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(Date(millis))

/** 「18:42」 */
fun timeOfDay(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
