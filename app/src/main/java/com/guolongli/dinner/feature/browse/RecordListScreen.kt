package com.guolongli.dinner.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guolongli.dinner.ui.component.StarRating
import com.guolongli.dinner.ui.component.TypeTag
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.StatScope
import com.guolongli.dinner.ui.model.dayLabel
import com.guolongli.dinner.ui.model.timeOfDay
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/**
 * 统计明细：「我的」顶部三个数字点进来的那一页。
 *
 * 数字本身没有信息量，点得进去才有用——所以要有一页按行列出这批记录，
 * 而不是弹一句「共 128 条」。
 */
@Composable
fun RecordListScreen(
    scope: StatScope,
    records: List<RecordUiModel>,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(PageBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                LineIcon(spec = LineIconSpec.ARROW_LEFT, tint = TextPrimary, size = 20.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = scope.label, style = DinnerText.screenTitle, color = TextPrimary)
                Text(text = "${records.size} 条", style = DinnerText.caption, color = TextSecondary)
            }
        }

        if (records.isEmpty()) {
            EmptyState(scope)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = Dimens.PagePadding,
                    end = Dimens.PagePadding,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                items(items = records, key = { it.id }) { record ->
                    ListRow(record = record, onClick = { onOpenRecord(record.id) })
                }
            }
        }
    }
}

/** 空态：说清楚「为什么空」，而不是留一片白 */
@Composable
private fun EmptyState(scope: StatScope) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PagePadding)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard))
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
    ) {
        Text(text = "这里还是空的", style = DinnerText.bodyMedium, color = TextPrimary)
        Text(
            text = when (scope) {
                StatScope.ALL -> "记下第一条之后，这里会列出全部记录"
                StatScope.MINE -> "你还没有记过，去时间线点「+」"
                StatScope.SYNCED -> "都还在本机排队，联网后会自动上传"
            },
            style = DinnerText.caption,
            color = TextSecondary,
        )
    }
}

@Composable
private fun ListRow(record: RecordUiModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard))
            .clickable { onClick() }
            .padding(Dimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                TypeTag(record.type)
                Text(
                    text = "${dayLabel(record.createdAt)} ${timeOfDay(record.createdAt)}",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                )
            }
            Text(
                text = record.note.ifBlank { "（没有备注）" },
                style = DinnerText.bodySmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                StarRating(rating = record.rating, starSize = 12.dp, gap = 2.dp)
                Text(text = record.authorName, style = DinnerText.footnote, color = TextTertiary)
            }
        }
        LineIcon(spec = LineIconSpec.CHEVRON_RIGHT, tint = TextTertiary, size = 16.dp)
    }
}
