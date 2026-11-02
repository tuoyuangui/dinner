package com.guolongli.dinner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 时间线里的分组日期头。
 *
 * 日期在左，条数做成右侧小胶囊：视觉上把「时间轴」和「数量」分开，
 * 滚动时不会与卡片内容混成一片。
 */
@Composable
fun DayHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = DinnerDimens.screenPadding,
                end = DinnerDimens.screenPadding,
                top = DinnerDimens.spaceSm,
                bottom = DinnerDimens.spaceXs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(DinnerDimens.radiusFull))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(
                        horizontal = DinnerDimens.spaceXs,
                        vertical = DinnerDimens.spaceXxs,
                    ),
            )
        }
    }
}
