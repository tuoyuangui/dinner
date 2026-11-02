package com.guolongli.dinner.feature.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.LoadingState
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.components.ThinDivider
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 月历：按月看哪天记了东西。
 *
 * 三种视觉状态各自占一个通道，不再叠在同一个圆上互相干扰：
 * - 底部小圆点：这天有记录
 * - primary 描边：今天
 * - primaryContainer 实心：刚点过的那天
 * - 灰字：还没到的日子
 *
 * 点有记录的日期进入当天的记录列表；空日期不可点，避免点了没反应。
 */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenDay: (String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 翻月方向：由点「上个月 / 下个月」的那一侧决定，AnimatedContent 据此决定滑向哪边。
    var forward by remember { mutableStateOf(true) }
    // 点过的那天：只在本页内记录，用来把「选中」这一路视觉画出来。
    var selectedDay by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "日历",
                subtitle = "按月看哪天记了东西",
                onBack = onBack,
                trailing = {
                    if (!state.isCurrentMonth) {
                        TextButton(
                            onClick = {
                                forward = false
                                viewModel.goToCurrentMonth()
                            },
                        ) { Text("本月") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                LoadingState()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 六周的月份会把底部统计卡挤出屏幕：整页可滚才够得到它。
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DinnerDimens.screenPadding,
                    end = DinnerDimens.screenPadding,
                    top = DinnerDimens.spaceXs,
                    bottom = DinnerDimens.spaceLg,
                ),
        ) {
            MonthHeader(
                title = state.title,
                canGoNext = state.canGoNext,
                onPrevious = {
                    forward = false
                    viewModel.previousMonth()
                },
                onNext = {
                    forward = true
                    viewModel.nextMonth()
                },
            )

            WeekdayHeader(labels = state.weekdayLabels)

            MonthPager(
                title = state.title,
                forward = forward,
                leadingBlanks = state.leadingBlanks,
                days = state.days,
                selectedDay = selectedDay,
                onOpenDay = { dayKey ->
                    selectedDay = dayKey
                    onOpenDay(dayKey)
                },
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceMd))

            SectionCard(title = "这个月") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${state.monthCount} 条记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${state.activeDays} 天有记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                ThinDivider()
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                Text(
                    text = "日期下面的小圆点是这天有记录，描边圈是今天；点有记录的那天看当天记了什么。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 年月标题行：左右翻月，标题居中。 */
@Composable
private fun MonthHeader(
    title: String,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DinnerDimens.calendarHeader),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = "上个月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "下个月",
                tint = if (canGoNext) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DinnerDimens.alphaDisabled)
                },
            )
        }
    }
}

/** 星期表头：周一到周日，与中文日历习惯一致。 */
@Composable
private fun WeekdayHeader(labels: List<String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 翻月切换：整月网格横向滑进滑出，方向与「点了哪一边」一致。
 *
 * 用 AnimatedContent 包住网格而不是整页：标题行与统计卡留在原地，
 * 动的只有会变的那一片，翻月才不像换了一屏。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun MonthPager(
    title: String,
    forward: Boolean,
    leadingBlanks: Int,
    days: List<CalendarDay>,
    selectedDay: String?,
    onOpenDay: (String) -> Unit,
) {
    // 进/出的横向位移：翻到下一月时新内容从右侧进、旧内容往左侧出，方向与「点了哪一边」一致。
    val offset: (Int) -> Int = { full -> if (forward) full else -full }
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            slideInHorizontally(
                animationSpec = tween(DinnerDimens.animStandardMs),
                initialOffsetX = offset,
            ) + fadeIn(tween(DinnerDimens.animStandardMs)) togetherWith
                slideOutHorizontally(
                    animationSpec = tween(DinnerDimens.animStandardMs),
                    targetOffsetX = { full -> -offset(full) },
                ) + fadeOut(tween(DinnerDimens.animStandardMs))
        },
        label = "monthPager",
    ) { _ ->
        MonthGrid(
            leadingBlanks = leadingBlanks,
            days = days,
            selectedDay = selectedDay,
            onOpenDay = onOpenDay,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 月网格：按周切片，1 号前用空格补齐。 */
@Composable
private fun MonthGrid(
    leadingBlanks: Int,
    days: List<CalendarDay>,
    selectedDay: String?,
    onOpenDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells: List<CalendarDay?> = buildList {
        repeat(leadingBlanks) { add(null) }
        addAll(days)
        // 末尾补空格，保证每行都是 7 格，边框对齐不歪。
        while (size % DAYS_PER_WEEK != 0) add(null)
    }

    Column(modifier = modifier) {
        cells.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        selected = day?.dateKey == selectedDay,
                        onOpenDay = onOpenDay,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay?,
    selected: Boolean,
    onOpenDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // 整格即点击区：44dp 的格子只有加上外边距才勉强够到 48dp 的触控下限，
            // 这里直接把格子高度提到 48dp，圆只负责视觉。
            .height(DinnerDimens.touchTargetMin)
            .then(
                // 空日期不可点：点了没反应比不显示更让人困惑。
                if (day != null && day.count > 0) {
                    Modifier.clickable { onOpenDay(day.dateKey) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (day == null) return@Box
        val hasRecords = day.count > 0
        val background = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
        val textColor = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            day.isFuture -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
        Box(
            modifier = Modifier
                .size(DinnerDimens.touchTargetMin - DinnerDimens.spaceXs)
                .clip(CircleShape)
                .background(background)
                .then(
                    // 今天用描边圈：不跟「有记录」的圆点抢同一个圆。
                    if (day.isToday) {
                        Modifier.border(
                            width = DinnerDimens.borderMedium,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    }
                )
                .alpha(if (day.isFuture) DinnerDimens.alphaDisabled else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontWeight = when {
                        hasRecords || selected -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                )
                if (hasRecords) {
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                    Box(
                        modifier = Modifier
                            .size(DinnerDimens.calendarDot)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

private const val DAYS_PER_WEEK = 7
