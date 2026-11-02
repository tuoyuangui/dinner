package com.guolongli.dinner.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.ui.component.MediaTile
import com.guolongli.dinner.ui.component.MemberAvatar
import com.guolongli.dinner.ui.component.SectionCard
import com.guolongli.dinner.ui.component.TypeTag
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.timeOfDay
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val weekLabels = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 03 · 日历
 * 月历卡（有记录的日子标品牌蓝、选中日实心圆）+ 当日记录摘要 + 底部导航。
 *
 * 日期格子完全按真实日历算：每月 1 号落在星期几、这个月几天、闰年二月，
 * 都交给 java.time，不写死。有记录的日期来自本地库，翻月/选日都是即时过滤。
 */
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    records: List<RecordUiModel> = emptyList(),
    /** 当前群名称：每条动态上标一下，方便一眼看出是哪个群的记录 */
    groupName: String = "",
    today: LocalDate = LocalDate.now(),
    onBack: () -> Unit = {},
    onAddRecord: () -> Unit = {},
    /** 点某一天里的任意一条动态 → 打开这条动态的详情 */
    onOpenRecord: (String) -> Unit = {},
) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }

    // 按自然日归组：同一天的多条记录在下面按时间倒序显示
    val byDay = remember(records) {
        records.groupBy { record ->
            java.time.Instant.ofEpochMilli(record.createdAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }
    val dayRecords = byDay[selected].orEmpty().sortedByDescending { it.createdAt }
    val weeks = remember(month) { buildWeeks(month) }

    Column(modifier.fillMaxSize().background(PageBackground)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.PagePadding),
        ) {

            // 页头
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.GapS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LineIcon(
                    spec = LineIconSpec.ARROW_LEFT,
                    tint = TextPrimary,
                    size = 24.dp,
                    modifier = Modifier.clickable { onBack() },
                )
                Text(text = "日历", style = DinnerText.screenTitle, color = TextPrimary)
                Text(
                    text = "今天",
                    style = DinnerText.bodyMedium,
                    color = BrandBlue,
                    modifier = Modifier.clickable {
                        month = YearMonth.from(today)
                        selected = today
                    },
                )
            }

            // 月份导航
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapS, bottom = Dimens.GapL),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LineIcon(
                    spec = LineIconSpec.CHEVRON_LEFT,
                    tint = TextSecondary,
                    size = 24.dp,
                    modifier = Modifier.clickable {
                        month = month.minusMonths(1)
                        selected = month.atDay(1)
                    },
                )
                Text(
                    text = "${month.year}年${month.monthValue}月",
                    style = DinnerText.navTitle,
                    color = TextPrimary,
                )
                LineIcon(
                    spec = LineIconSpec.CHEVRON_RIGHT,
                    tint = TextSecondary,
                    size = 24.dp,
                    modifier = Modifier.clickable {
                        month = month.plusMonths(1)
                        selected = month.atDay(1)
                    },
                )
            }

            // 月历卡
            SectionCard {
                Column(Modifier.padding(Dimens.GapXl)) {
                    Row(Modifier.fillMaxWidth()) {
                        weekLabels.forEach { label ->
                            Text(
                                text = label,
                                style = DinnerText.footnote,
                                color = TextTertiary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.GapXs))
                    weeks.forEach { week ->
                        Row(Modifier.fillMaxWidth().height(Dimens.CalendarCellHeight)) {
                            week.forEach { day ->
                                DayCell(
                                    day = day,
                                    highlighted = day != null && byDay.containsKey(day),
                                    selected = day == selected,
                                    isToday = day == today,
                                    onClick = { day?.let { selected = it } },
                                )
                            }
                        }
                    }
                    // 图例
                    Row(
                        modifier = Modifier.padding(top = Dimens.GapXs, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(BrandBlue),
                        )
                        Text(text = "当天有记录", style = DinnerText.footnote, color = TextTertiary)
                    }
                }
            }

            // 当日记录
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.CardPadding, bottom = Dimens.GapM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${selected.monthValue}月${selected.dayOfMonth}日 · ${dayRecords.size} 条记录",
                    style = DinnerText.bodySmallMedium,
                    color = TextSecondary,
                )
                Text(
                    text = "去记一条",
                    style = DinnerText.caption,
                    color = BrandBlue,
                    modifier = Modifier.clickable { onAddRecord() },
                )
            }

            SectionCard {
                Column(
                    modifier = Modifier.padding(Dimens.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.GapXl),
                ) {
                    if (dayRecords.isEmpty()) {
                        Text(
                            text = "这天还没有记录",
                            style = DinnerText.bodySmall,
                            color = TextTertiary,
                        )
                    }
                    dayRecords.forEach { item ->
                        // 每条动态都能点进详情；头像 + 群名称是认人的第一线索
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dimens.RadiusSmall))
                                .clickable { onOpenRecord(item.id) }
                                .padding(vertical = Dimens.GapXs),
                            verticalArrangement = Arrangement.spacedBy(Dimens.GapS),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
                            ) {
                                MemberAvatar(
                                    initial = item.authorInitial,
                                    color = item.authorColor,
                                    size = 20.dp,
                                    fontSize = 9.sp,
                                    avatarPath = item.authorAvatarPath,
                                )
                                Text(
                                    text = item.authorName,
                                    style = DinnerText.footnoteMedium,
                                    color = TextSecondary,
                                )
                                if (groupName.isNotBlank()) {
                                    Text(
                                        text = groupName,
                                        style = DinnerText.footnote,
                                        color = TextTertiary,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = timeOfDay(item.createdAt),
                                    style = DinnerText.footnote,
                                    color = TextTertiary,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
                            ) {
                                TypeTag(item.type)
                                Text(
                                    text = item.note,
                                    style = DinnerText.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                item.media.firstOrNull()?.let { media ->
                                    MediaTile(
                                        media = media,
                                        modifier = Modifier.size(44.dp),
                                        glyphSize = 18.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

    }
}

/**
 * 把一个月摊成周列表：前面补空位对齐星期，后面补满 7 格。
 * 只有前 0~6 位是空的，其余都是本月真实日期。
 */
private fun buildWeeks(month: YearMonth): List<List<LocalDate?>> {
    val first = month.atDay(1)
    // DayOfWeek.MONDAY.value = 1 … SUNDAY = 7；这里按「周日开头」排版
    val lead = first.dayOfWeek.value % 7
    val days = month.lengthOfMonth()
    val cells = MutableList<LocalDate?>(lead) { null } +
        (1..days).map { month.atDay(it) }
    return cells.chunked(7).map { week -> week + List(7 - week.size) { null } }
}

@Composable
private fun RowScope.DayCell(
    day: LocalDate?,
    highlighted: Boolean,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(Dimens.CalendarCellHeight)
            .clickable(enabled = day != null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val number = day?.dayOfMonth ?: return@Box
        when {
            selected -> Box(
                modifier = Modifier
                    .size(Dimens.CalendarSelectedSize)
                    .clip(CircleShape)
                    .background(BrandBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "$number", style = DinnerText.bodySmallMedium, color = OnBrand)
            }

            else -> Text(
                text = "$number",
                style = if (highlighted || isToday) DinnerText.bodySmallMedium else DinnerText.bodySmall,
                color = when {
                    highlighted -> BrandBlue
                    isToday -> BrandBlue
                    else -> TextPrimary
                },
            )
        }
    }
}

@Preview(name = "03 日历", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun CalendarScreenPreview() {
    DinnerTheme {
        CalendarScreen()
    }
}
