package com.guolongli.dinner.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.ui.component.BottomPillNav
import com.guolongli.dinner.ui.component.FloatingAddButton
import com.guolongli.dinner.ui.component.MainTab
import com.guolongli.dinner.ui.component.MediaTile
import com.guolongli.dinner.ui.component.MediaViewerDialog
import com.guolongli.dinner.ui.component.MemberAvatar
import com.guolongli.dinner.ui.image.rememberImageAspectRatio
import com.guolongli.dinner.ui.image.rememberVideoThumbPath
import com.guolongli.dinner.ui.component.PillChip
import com.guolongli.dinner.ui.component.StarRating
import com.guolongli.dinner.ui.component.TypeTag
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.CommentUiModel
import com.guolongli.dinner.ui.model.MediaUiModel
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.TimelineFilter
import com.guolongli.dinner.ui.model.TimelineItem
import com.guolongli.dinner.ui.model.buildMonthJumps
import com.guolongli.dinner.ui.model.buildTimeline
import com.guolongli.dinner.ui.model.dayLabel
import com.guolongli.dinner.ui.model.dayTitle
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BorderStrongColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DividerColor
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagRedFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import java.io.File

/**
 * 01 · 时间线（设计稿第 1 页）
 *
 * 页头（统计 + 日历入口）→ 常驻筛选条 →（跨月时的）月份条 → 按天分组的记录卡
 * → 右下角悬浮新建 → 底部两个 tab。
 *
 * 这一屏只读 [records] 与几个回调，不认识仓储，也不做业务判断：
 * 筛选与分组是纯函数（[buildTimeline]），可以在单测里直接盯住。
 */
@Composable
fun TimelineScreen(
    records: List<RecordUiModel>,
    summary: String,
    modifier: Modifier = Modifier,
    /** 当前群名称：每条动态标一下，家人一眼分得清是哪条是谁、在哪个群 */
    groupName: String = "",
    onAddRecord: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onToggleStar: (String) -> Unit = {},
    onSelectTab: (MainTab) -> Unit = {},
    /**
     * 按需取回还没下载的媒体（recordId, position → 本地路径）。
     * 点开云端那一份的格子时先取回再全屏查看，保证「点开就能看」。
     */
    onFetchMedia: suspend (recordId: String, position: Int) -> String? = { _, _ -> null },
) {
    var filter by remember { mutableStateOf(TimelineFilter.DEFAULT) }
    val items = remember(records, filter) { buildTimeline(records, filter) }
    val months = remember(items) { buildMonthJumps(items) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var viewer by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var fetching by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(PageBackground)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = Dimens.PagePadding,
                    end = Dimens.PagePadding,
                    bottom = Dimens.FabSize + Dimens.GapXl * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapXl),
            ) {
                item(key = "header") {
                    TimelineHeader(summary = summary, onOpenCalendar = onOpenCalendar)
                }
                item(key = "filter") {
                    FilterRow(selected = filter, onSelect = { filter = it })
                }
                if (months.isNotEmpty()) {
                    item(key = "months") {
                        MonthBar(months = months) { index ->
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "empty") {
                        EmptyTimelineCard(filter = filter, onAddRecord = onAddRecord, onResetFilter = {
                            filter = TimelineFilter.DEFAULT
                        })
                    }
                }
                items(items = items, key = { it.key }) { item ->
                    when (item) {
                        is TimelineItem.DayHeader -> DayHeader(item)
                        is TimelineItem.RecordRow -> RecordCard(
                            record = item.record,
                            groupName = groupName,
                            onClick = { onOpenRecord(item.record.id) },
                            onToggleStar = { onToggleStar(item.record.id) },
                            onMediaClick = { media ->
                                // localPath 有值 ≠ 文件还在：
                                // 系统存储紧张时文件会被回收，而数据库里的路径还在。
                                // 这种情况下要当「没下载」处理，走按需取回，
                                // 否则点开就是一片空白（图片）或一片黑（视频）。
                                val local = media.localPath?.takeIf { File(it).isFile }
                                if (local != null) {
                                    viewer = local to media.isVideo
                                } else if (!fetching) {
                                    // 只有云端那一份：先按需取回，取到就直接全屏看
                                    fetching = true
                                    scope.launch {
                                        try {
                                            val path = onFetchMedia(media.recordId, media.position)
                                            if (path != null) viewer = path to media.isVideo
                                        } finally {
                                            fetching = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            FloatingAddButton(
                onClick = onAddRecord,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Dimens.PagePadding, bottom = Dimens.FabBottomInset),
            )
        }

        BottomPillNav(
            selected = MainTab.TIMELINE,
            onSelect = onSelectTab,
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    viewer?.let { (path, isVideo) ->
        MediaViewerDialog(path = path, isVideo = isVideo, onDismiss = { viewer = null })
    }
}

/** 页头：标题 + 「一共 N 条 · 跨越 M 天」+ 日历入口 */
@Composable
private fun TimelineHeader(summary: String, onOpenCalendar: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapL, bottom = Dimens.GapS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "食伴", style = DinnerText.appTitle, color = TextPrimary)
            Text(text = summary, style = DinnerText.caption, color = TextSecondary)
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SurfaceWhite)
                .border(Dimens.CardBorderWidth, BorderStrongColor, CircleShape)
                .clickable { onOpenCalendar() },
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(spec = LineIconSpec.CALENDAR, tint = BrandBlue, size = 20.dp)
        }
    }
}

/** 常驻筛选条：横向可滚，选中的那一格是品牌蓝实心 */
@Composable
private fun FilterRow(selected: TimelineFilter, onSelect: (TimelineFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
    ) {
        TimelineFilter.entries.forEach { item ->
            PillChip(
                label = item.label,
                selected = item == selected,
                onClick = { onSelect(item) },
            )
        }
    }
}

/** 跨月时才出现的月份条，点一下滚到那个月 */
@Composable
private fun MonthBar(months: List<com.guolongli.dinner.ui.model.MonthJump>, onJump: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
    ) {
        months.forEach { month ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.RadiusPill))
                    .background(SurfaceMuted)
                    .clickable { onJump(month.itemIndex) }
                    .padding(horizontal = Dimens.GapXl, vertical = Dimens.GapS),
            ) {
                Text(text = month.label, style = DinnerText.footnoteMedium, color = TextSecondary)
            }
        }
    }
}

/** 「今天 · 10月30日 星期五 · 3 条」——分组标题比记录里的时间更先被眼睛扫到 */
@Composable
private fun DayHeader(item: TimelineItem.DayHeader) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
    ) {
        Text(
            text = dayLabel(item.firstRecordAt),
            style = DinnerText.bodySmallMedium,
            color = TextSecondary,
        )
        Text(
            text = dayTitle(item.firstRecordAt),
            style = DinnerText.caption,
            color = TextTertiary,
        )
        Text(text = "· ${item.count} 条", style = DinnerText.caption, color = TextTertiary)
    }
}

/** 还没有记录 / 筛完是空：给一句指引，而不是留白 */
@Composable
private fun EmptyTimelineCard(
    filter: TimelineFilter,
    onAddRecord: () -> Unit,
    onResetFilter: () -> Unit,
) {
    val emptyByFilter = filter != TimelineFilter.ALL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard))
            .clickable { if (emptyByFilter) onResetFilter() else onAddRecord() }
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
    ) {
        Text(
            text = if (emptyByFilter) "这个筛选下还没有记录" else "还没有记录",
            style = DinnerText.bodyMedium,
            color = TextPrimary,
        )
        Text(
            text = if (emptyByFilter) {
                "点一下回到「全部」"
            } else {
                "点右下角的「+」记下今天这一顿，离线也能记，联网后自动同步到自己的网盘"
            },
            style = DinnerText.caption,
            color = TextSecondary,
        )
    }
}

/** 一条记录卡：作者 + 类型 + 时间 → 备注 → 九宫格 → 星级 → 评论预览 */
@Composable
private fun RecordCard(
    record: RecordUiModel,
    groupName: String = "",
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onMediaClick: (MediaUiModel) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard))
            .clickable { onClick() }
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        CardHeader(record, groupName)

        if (record.note.isNotBlank()) {
            Text(
                text = record.note,
                style = DinnerText.body,
                color = TextPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MediaGrid(record.media, onMediaClick = onMediaClick)

        // 位置只在卡片上占一行小字：吃饭这件事，「在哪儿」往往比「几点」更值得记
        record.locationLabel?.let { place ->
            Text(
                text = "📍 $place",
                style = DinnerText.footnote,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            StarRating(rating = record.rating, starSize = 16.dp, gap = 4.dp)
            Text(
                text = "%.1f".format(record.rating.toFloat()),
                style = DinnerText.captionMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (record.starred) {
                Text(
                    text = "已星标",
                    style = DinnerText.footnote,
                    color = TagRedFg,
                    modifier = Modifier.clickable { onToggleStar() },
                )
            }
        }

        if (record.comments.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = DividerColor)
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                record.comments.take(2).forEach { CommentRow(it) }
                if (record.comments.size > 2) {
                    Text(
                        text = "还有 ${record.comments.size - 2} 条评论",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * 卡片头：头像 + 群里的名字 + 群名称 + 类型 + 时间。
 * 头像和名字放最前面——时间线是「谁做了什么」，认人是第一眼的事。
 */
@Composable
private fun CardHeader(record: RecordUiModel, groupName: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        MemberAvatar(
            initial = record.authorInitial,
            color = record.authorColor,
            size = Dimens.AvatarMedium,
            fontSize = 14.sp,
            avatarPath = record.authorAvatarPath,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                Text(text = record.authorName, style = DinnerText.bodyStrong, color = TextPrimary)
                if (groupName.isNotBlank()) {
                    // 群名称：多条动态混在一条时间线里时，一眼分得清属于哪个群
                    Text(
                        text = groupName,
                        style = DinnerText.footnote,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                TypeTag(record.type)
                Text(text = record.timeLabel, style = DinnerText.caption, color = TextTertiary)
                record.syncLabel?.let { label ->
                    Text(text = label, style = DinnerText.footnote, color = TextTertiary)
                }
            }
        }
    }
}

/**
 * 单图显示时用来兜住比例的范围。
 *
 * 下限：太窄的竖图（宽高比 < 0.5）会撑成一根细长条，列表里很难点；
 * 上限：太宽的横幅（宽高比 > 2.2）如果不管，一行就占满宽度、
 * 高度缩到几乎看不见。
 */
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.2f

/** 比例读不出来时的默认值（接近 4:3，常见的手机照片比例） */
private const val DEFAULT_RATIO = 1.33f

/** 高度上限相对宽度的倍数：最多占 1.6 倍宽，再高就变成一张巨幅图了 */
private const val MAX_HEIGHT_FACTOR = 1.6f

/**
 * 媒体网格：**单张保持原比例，多张铺满格子**。
 *
 * 两种情况看的是不同的东西，所以显示策略本来就不该一样：
 *
 * - **只有一张**：这是这张照片本身，用户的意图是「看这张图」。
 *   所以容器高度按图片**真实宽高比**算（竖图就高、方图就方、横图就扁），
 *   画面用 [ContentScale.Fit] 完整显示，不裁切也不变形。
 *   早先固定给一个高度，竖图上下会空出两条大黑边——那不是「保持比例」，
 *   只是「不裁切」。
 * - **两张及以上**：这是网格，用户看的是「一屏图」。
 *   格子必须等大等宽才整齐，所以画面用 [ContentScale.Crop] 铺满，
 *   边缘被裁掉一点没关系——九宫格里没人指望看到照片的全部边角。
 *
 * 视频走的是封面帧，比例按视频本身算（抽帧出来的图和视频同宽高比）。
 */
@Composable
fun MediaGrid(
    media: List<MediaUiModel>,
    modifier: Modifier = Modifier,
    tileHeight: androidx.compose.ui.unit.Dp = Dimens.MediaTileHeight,
    onMediaClick: ((MediaUiModel) -> Unit)? = null,
) {
    if (media.isEmpty()) return
    // 单张 → 保持比例；其余 → 铺满格子
    val single = media.size == 1
    val columns = when {
        single -> 1
        media.size == 2 -> 2
        else -> 3
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.GapXs)) {
        media.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapXs)) {
                rowItems.forEach { item ->
                    if (single) {
                        SingleMediaTile(
                            media = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (onMediaClick != null) {
                                        Modifier.clickable { onMediaClick(item) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    } else {
                        MediaTile(
                            media = item,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .height(tileHeight)
                                .then(
                                    if (onMediaClick != null) {
                                        Modifier.clickable { onMediaClick(item) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 只有一张时的显示：按图片真实宽高比定高，完整显示不裁切。
 *
 * 比例拿不到（文件还没下载下来 / 读不出来）时退回一个稳妥的默认值，
 * 至少不会把界面撑变形。
 */
@Composable
private fun SingleMediaTile(
    media: MediaUiModel,
    modifier: Modifier = Modifier,
) {
    // 视频的封面帧与视频同宽高比，所以用同一个比例源
    val sourcePath = if (media.isVideo) {
        rememberVideoThumbPath(media.localPath) ?: media.localPath
    } else {
        media.localPath
    }
    val ratio = rememberImageAspectRatio(sourcePath)

    // 用 BoxWithConstraints 拿到实际宽度后**显式算高度**，
    // 而不是叠 heightIn + aspectRatio：那两个修饰符会互相打架——
    // 宽度固定时高度约束被 aspectRatio 顶掉，结果高度既不是比例算出来的，
    // 也不保证不小于最小高度，比例就废了。
    BoxWithConstraints(modifier = modifier) {
        val width = maxWidth
        val safeRatio = (ratio ?: DEFAULT_RATIO).coerceIn(MIN_RATIO, MAX_RATIO)
        val height = (width / safeRatio)
            // 超长竖图别占满整屏，横向极端的图也别塌成一条
            .coerceIn(Dimens.MediaTileHeight, width / MIN_RATIO * MAX_HEIGHT_FACTOR)

        MediaTile(
            media = media,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}

@Composable
private fun CommentRow(comment: CommentUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
    ) {
        MemberAvatar(
            initial = comment.authorInitial,
            color = comment.authorColor,
            size = Dimens.AvatarComment,
            fontSize = 9.sp,
        )
        Text(
            text = comment.text,
            style = DinnerText.caption,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
