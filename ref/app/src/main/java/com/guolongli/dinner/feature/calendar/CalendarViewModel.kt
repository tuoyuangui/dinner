package com.guolongli.dinner.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.ui.util.DateText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 月历里的一格。[count] 为当天的记录数，0 表示这天没有记录。 */
data class CalendarDay(
    val dayOfMonth: Int,
    val dateKey: String,
    val count: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

data class CalendarUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    /** 是否停在当前月：不是的话给一个「回到本月」出口。 */
    val isCurrentMonth: Boolean = true,
    /** 已经到最新的月份时不能再往后翻。 */
    val canGoNext: Boolean = false,
    val weekdayLabels: List<String> = DateText.WEEKDAY_SINGLE,
    /** 当月 1 号前面要空几格，用于把日期摆到正确的星期下。 */
    val leadingBlanks: Int = 0,
    val days: List<CalendarDay> = emptyList(),
    val monthCount: Int = 0,
    val activeDays: Int = 0,
)

/**
 * 月历：把记录按月铺成网格，一眼看出哪天记了、哪天没记。
 *
 * 只读本地库，不碰网盘——这是索引视图，不该为了「看有哪几天」去消耗接口频次。
 * 从网盘补取历史数据走「我的 - 本地数据 - 从云端取回」。
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val mealRepository: MealRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    private val todayKey: String = DateText.dayKey(System.currentTimeMillis())
    private val currentYear: Int = DateText.yearOf(System.currentTimeMillis())

    /**
     * 当前月份，**1 基**。
     *
     * 这里必须用 [DateText.month] 而不是 [DateText.monthOf]：后者是 0 基，
     * 只适合喂给 DatePickerDialog。曾经错用 0 基，导致日历整体少一个月——
     * 国庆的记录在「10 月」里看不见，标题还会把 9 月写成 10 月。
     */
    private val currentMonth: Int = DateText.month(System.currentTimeMillis())

    private var year: Int = currentYear
    private var month: Int = currentMonth

    /** dateKey -> 当天记录数。整月重算比按天查库便宜，这里直接留全量。 */
    private var counts: Map<String, Int> = emptyMap()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            mealRepository.observeAll().collect { records ->
                counts = records.groupingBy { DateText.dayKey(it.timestamp) }.eachCount()
                publish()
            }
        }
    }

    fun previousMonth() {
        if (month == 1) {
            year -= 1
            month = 12
        } else {
            month -= 1
        }
        publish()
    }

    fun nextMonth() {
        if (!_uiState.value.canGoNext) return
        if (month == 12) {
            year += 1
            month = 1
        } else {
            month += 1
        }
        publish()
    }

    fun goToCurrentMonth() {
        year = currentYear
        month = currentMonth
        publish()
    }

    private fun publish() {
        val total = DateText.daysInMonth(year, month)
        val days = (1..total).map { dayOfMonth ->
            val key = DateText.dayKeyOf(year, month, dayOfMonth)
            CalendarDay(
                dayOfMonth = dayOfMonth,
                dateKey = key,
                count = counts[key] ?: 0,
                isToday = key == todayKey,
                // 日期键是 ISO 格式，字符串比较等价于时间比较。
                isFuture = key > todayKey,
            )
        }
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                title = DateText.monthTitle(year, month),
                isCurrentMonth = year == currentYear && month == currentMonth,
                canGoNext = year < currentYear || (year == currentYear && month < currentMonth),
                leadingBlanks = DateText.firstWeekdayOffset(year, month),
                days = days,
                monthCount = days.sumOf { it.count },
                activeDays = days.count { it.count > 0 },
            )
        }
    }
}
