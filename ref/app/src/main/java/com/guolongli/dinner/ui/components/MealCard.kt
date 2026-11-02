package com.guolongli.dinner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 卡片展示所需的不可变数据，由 ViewModel 组装，Composable 只读。
 *
 * [mediaHint] 由 ViewModel 给出（正在从网盘取回 / 取回失败），
 * Composable 不做任何网络判断，只负责展示。
 */
@Immutable
data class MealCardUi(
    val id: String,
    val timestamp: Long,
    val dayTitle: String,
    val timeText: String,
    val mealType: MealType,
    val mealLabel: String,
    val authorName: String,
    val isMine: Boolean,
    val avatarImage: Any? = null,
    /** 这条记录带的媒体，0..9 段，照片与视频混排。 */
    val media: List<MediaCardUi> = emptyList(),
    val note: String,
    val syncState: SyncState,
    val rating: Int?,
    val marked: Boolean = false,
    /** 记录时所在位置，没有定位时为 null，不占一行。 */
    val locationText: String? = null,
    /** 标签列表，空列表不展示。 */
    val tags: List<String> = emptyList(),
    /** 评论总数，0 表示还没有人评论。 */
    val commentCount: Int = 0,
    /** 卡片上的评论预览，最多两条，完整列表在评论面板里看。 */
    val commentPreview: List<CommentUi> = emptyList(),
)

/**
 * 卡片里的一段媒体。
 *
 * [hint] 由 ViewModel 给出（正在从网盘取回 / 取回失败），
 * Composable 不做任何网络判断，只负责展示。
 */
@Immutable
data class MediaCardUi(
    val id: String,
    val localPath: String?,
    val thumbPath: String?,
    val mediaType: MediaType,
    val hint: String? = null,
    val durationText: String? = null,
)

/**
 * 时间线卡片。
 *
 * 布局：头像 + 作者/餐别 + 同步徽标 / 媒体(4:3) / 备注 / 元信息 / 评论 / 操作。
 * 信息按「媒体 -> 备注 -> 元信息 -> 评论」四级降幂排布，越往下越弱。
 *
 * 视觉：发丝描边代替阴影。整页都是卡片时，阴影会互相叠加变脏，
 * 描边 + 纯白/纯深底更干净，也更接近 iOS / Material 3 的「分层」做法。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealCard(
    item: MealCardUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onMediaClick: (Int) -> Unit = {},
    onComments: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onToggleMark: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(DinnerDimens.radiusCard)
    // 元信息行至少承载时间，只有位置 / 标签都空时才整行不出现。
    val hasMetaInfo = item.timeText.isNotBlank() ||
        !item.locationText.isNullOrBlank() ||
        item.tags.isNotEmpty()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = DinnerDimens.elevationFlat),
        border = BorderStroke(
            DinnerDimens.borderThin,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(DinnerDimens.spaceSm)) {
            // 作者 + 餐别 + 同步状态（时间下移到元信息行）
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(
                    name = item.authorName,
                    size = DinnerDimens.avatarMd,
                    isMine = item.isMine,
                    image = item.avatarImage,
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.authorName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                    MealTypeMarker(
                        type = item.mealType,
                        label = item.mealLabel,
                    )
                }
                SyncBadge(state = item.syncState)
            }

            // 媒体（照片 / 视频，九宫格）
            if (item.media.isNotEmpty()) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                MediaGrid(media = item.media, onMediaClick = onMediaClick)
            }

            // 备注：信息层级里仅次于媒体，用正文色而不是次要色。
            if (item.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = DinnerDimens.noteMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 元信息行：时间 / 位置 / 标签收进同一条浅色行，形成降幂的第三级。
            if (hasMetaInfo) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                    verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // 时间 + 位置连成一段，换行时不会被拆散。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            text = item.timeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!item.locationText.isNullOrBlank()) {
                            // 纯装饰分隔符：不给读屏念出「间隔号」。
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                            Icon(
                                imageVector = Icons.Outlined.Place,
                                contentDescription = "位置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(DinnerDimens.iconSm),
                            )
                            Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                            Text(
                                text = item.locationText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item.tags.forEach { tag ->
                        TagPill(text = tag)
                    }
                }
            }

            // 评论预览：最多两条，完整对话在评论面板里
            if (item.commentPreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DinnerDimens.radiusMd))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DinnerDimens.alphaSubtle))
                        .padding(horizontal = DinnerDimens.spaceXs, vertical = DinnerDimens.spaceXxs),
                    verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
                ) {
                    item.commentPreview.forEach { comment ->
                        // 预览不是阅读区：超长正文按字符数截断，避免一张卡片被一条评论撑满。
                        val previewText = comment.text.let { raw ->
                            if (raw.length > DinnerDimens.commentPreviewChars) {
                                raw.take(DinnerDimens.commentPreviewChars) + "…"
                            } else {
                                raw
                            }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${comment.authorName}：",
                                style = MaterialTheme.typography.bodySmall,
                                // primary 当文字色在浅底上只有 2.6:1，用语义强调色替掉。
                                color = DinnerTheme.colors.textAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = DinnerDimens.commentTextMaxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 底部操作行：评分 / 星标 / 评论在左，删除在右。
            //
            // 这一行原本是单行 Row + SpaceBetween，左侧「5 颗星 + 星标 48dp + 评论」加起来
            // 接近 250dp，窄屏上右侧的删除按钮被挤出可视区、点不到，等于删不掉。
            // 现在拆成两段布局：主操作放一行（超出就换行），删除单独占一个尾部区域，
            // 无论左侧多宽都不会把删除挤走。
            if (item.rating != null || onDelete != null || onComments != null || onToggleMark != null) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 主操作区可以折行：星级 + 星标 + 评论在窄屏上一定会超宽，
                    // 折行比被裁掉要好。删除按钮在它右边，永远有位置。
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                        verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.rating != null) {
                            StarRating(
                                value = item.rating,
                                editable = false,
                                starSize = DinnerDimens.iconMd,
                                spacing = DinnerDimens.spaceXxs,
                            )
                        }
                        if (onToggleMark != null) {
                            MarkAction(marked = item.marked, onClick = onToggleMark)
                        }
                        if (onComments != null) {
                            CommentAction(
                                count = item.commentCount,
                                onClick = onComments,
                            )
                        }
                    }
                    if (onDelete != null) {
                        AppIconButton(
                            onClick = onDelete,
                            label = "删除这条记录",
                            icon = Icons.Outlined.DeleteOutline,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 卡片上的标签胶囊。 */
@Composable
private fun TagPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DinnerDimens.alphaSubtle))
            .padding(horizontal = DinnerDimens.spaceXs, vertical = DinnerDimens.spaceXxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 卡片里的媒体九宫格。
 *
 * 排版跟随数量，和常见社交应用一致：1 张给 4:3 大图，2 / 4 张走两列方图，
 * 其余走三列方图。方图裁切，绝不拉伸。
 */
@Composable
private fun MediaGrid(
    media: List<MediaCardUi>,
    onMediaClick: (Int) -> Unit,
) {
    if (media.size == 1) {
        val only = media.first()
        MediaThumbnail(
            localPath = only.localPath,
            thumbnailPath = only.thumbPath,
            mediaType = only.mediaType,
            hint = only.hint,
            durationText = only.durationText,
            shape = DinnerDimens.radiusMd,
            // 单图固定 4:3：容器不跟随图片比例，时间线行高才不会参差。
            aspectRatio = DinnerDimens.photoAspectRatio,
            adaptToImage = false,
            onClick = { onMediaClick(0) },
        )
        return
    }

    val columns = when (media.size) {
        2, 4 -> 2
        else -> 3
    }
    // 分组结果按输入缓存：否则每次重组都要重新切一遍列表。
    val rows = remember(media, columns) { media.withIndex().chunked(columns) }
    Column(verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
            ) {
                row.forEach { indexed ->
                    Box(modifier = Modifier.weight(1f)) {
                        MediaThumbnail(
                            localPath = indexed.value.localPath,
                            thumbnailPath = indexed.value.thumbPath,
                            mediaType = indexed.value.mediaType,
                            hint = indexed.value.hint,
                            durationText = indexed.value.durationText,
                            shape = DinnerDimens.radiusSm,
                            aspectRatio = 1f,
                            // 格子保持正方形，网格才整齐；格子内部等比缩放，图不被裁。
                            adaptToImage = false,
                            onClick = { onMediaClick(indexed.index) },
                        )
                    }
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 星标入口：实心星表示已标记，点了就在时间线里能筛出来。
 *
 * 点击区强制撑到 [DinnerDimens.touchTargetMin]：只包住一个 18dp 图标时实际高度不到一半，
 * 手指按不准，也不满足无障碍的最小触控标准。
 */
@Composable
private fun MarkAction(marked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(DinnerDimens.touchTargetMin)
            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (marked) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = if (marked) "取消星标" else "加星标",
            tint = if (marked) {
                DinnerTheme.colors.starActive
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(DinnerDimens.iconMd),
        )
    }
}

/**
 * 卡片底部的评论入口：图标 + 数量，点开评论面板。
 *
 * 有评论时显示「共 N 条」而不是一个光秃秃的数字，没有评论时显示「评论」，
 * 两种状态都是一句话，不会让用户去猜这个数字是什么。
 * 点击区同样撑到 [DinnerDimens.touchTargetMin]。
 */
@Composable
private fun CommentAction(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(DinnerDimens.touchTargetMin)
            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
            .clickable(onClick = onClick)
            .padding(horizontal = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(DinnerDimens.iconMd),
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
        Text(
            text = if (count > 0) "共 $count 条" else "评论",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
