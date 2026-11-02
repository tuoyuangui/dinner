package com.guolongli.dinner.ui.util

import com.guolongli.dinner.domain.model.MealRecord

/**
 * 按本地日历天分组的餐食记录。
 */
data class DayGroup(
    val dayKey: String,
    val dayStartMillis: Long,
    val records: List<MealRecord>,
) {
    companion object {
        /**
         * 把记录按日期键分组，并保证同一天内按时间降序（最新在前）。
         * 返回的组按日期降序（最新在前）。
         */
        fun of(records: List<MealRecord>, dayStart: (MealRecord) -> Long = { it.timestamp }): List<DayGroup> {
            // key -> 记录列表（保持时间倒序）。
            val grouped = linkedMapOf<String, MutableList<MealRecord>>()
            // 先按时间倒序处理，保证同组内顺序正确。
            records.sortedByDescending { it.timestamp }.forEach { record ->
                val key = DateText.dayKey(dayStart(record))
                grouped.getOrPut(key) { mutableListOf() }.add(record)
            }
            return grouped.map { (key, list) ->
                val start = DateText.startOfDay(dayStart(list.first()))
                DayGroup(key, start, list)
            }.sortedByDescending { it.dayStartMillis }
        }
    }
}