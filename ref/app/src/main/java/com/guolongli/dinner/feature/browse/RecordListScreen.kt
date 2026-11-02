package com.guolongli.dinner.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.EmptyState
import com.guolongli.dinner.ui.components.LoadingState
import com.guolongli.dinner.ui.components.MealTypeMarker
import com.guolongli.dinner.ui.components.SyncBadge
import com.guolongli.dinner.ui.components.mediaModel
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 记录明细列表。
 *
 * 一行一条，左边的缩略图给「有没有照片」一个直观答案，中间是类型 / 时间 / 备注摘要，
 * 右边是进入编辑页的箭头。整行可点，避免只有小箭头可点那种不舒服的手感。
 */
@Composable
fun RecordListScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    viewModel: RecordListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = state.title, subtitle = state.subtitle, onBack = onBack)
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> LoadingState()
                state.entries.isEmpty() -> EmptyState(
                    message = state.emptyMessage,
                    hint = state.emptyHint,
                    icon = Icons.Outlined.RestaurantMenu,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = DinnerDimens.screenPadding,
                        end = DinnerDimens.screenPadding,
                        top = DinnerDimens.spaceXs,
                        bottom = DinnerDimens.spaceLg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                ) {
                    items(items = state.entries, key = { it.id }) { row ->
                        RecordRow(item = row, onClick = { onOpenRecord(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(
    item: RecordRowUi,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(DinnerDimens.radiusCard)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = DinnerDimens.elevationFlat),
        border = BorderStroke(DinnerDimens.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DinnerDimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowThumb(item)
            Spacer(modifier = Modifier.width(DinnerDimens.spaceSm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MealTypeMarker(type = item.mealType, label = item.mealLabel)
                    Text(
                        text = " · ${item.timeText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.marked) {
                        Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "已加星标",
                            tint = DinnerTheme.colors.starActive,
                            modifier = Modifier.size(DinnerDimens.iconSm),
                        )
                    }
                }
                if (item.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 用真实昵称而不是「我」：时间线卡片上也这么显示，两处对得上才不会错位。
                    Text(
                        text = item.authorName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isMine) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = " · ${item.dayLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.mediaCount > 0) {
                        Text(
                            text = " · ${item.mediaCount} 段",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                    SyncBadge(state = item.syncState)
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(DinnerDimens.iconMd),
            )
        }
    }
}

/** 行首缩略图：有图就显示图，没图用类型色底 + 图标，保证每行左边对齐。 */
@Composable
private fun RowThumb(item: RecordRowUi) {
    val shape = RoundedCornerShape(DinnerDimens.radiusSm)
    // mediaModel 内部是 MealRecord.existingFile（File.isFile + length），属于磁盘 I/O。
    // 不做 remember 的话，每次重组都对每一行重新 stat 一次，滚动时直接压在主线程上。
    val model = remember(item.firstMediaPath, item.firstMediaThumb, item.firstMediaType) {
        item.firstMediaType?.let { type ->
            mediaModel(item.firstMediaPath, item.firstMediaThumb, type)
        }
    }
    Box(
        modifier = Modifier
            .size(DinnerDimens.listThumb)
            .clip(shape)
            .background(DinnerTheme.colors.photoPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                // 行首缩略图也等比完整显示：尺寸小，但仍不允许裁切画面。
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = DinnerTheme.colors.onPhotoPlaceholder,
                modifier = Modifier.size(DinnerDimens.iconMd),
            )
        }
    }
}
