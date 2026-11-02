package com.guolongli.dinner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 时间线首屏摘要卡。
 *
 * 用主题色派生的柔和渐变承载「记了多少顿」这一核心信息，
 * 渐变取 primaryContainer / tertiaryContainer，因此 Material You 动态取色时
 * 会跟着壁纸一起变，不会显得突兀。
 *
 * 右侧那颗「日历」胶囊是进入月历的入口：整卡可点，胶囊让「能点」这件事显式化，
 * 否则用户很难猜到一张统计卡还能点。
 *
 * 装饰圆不使用任何位图资源，避免为一个背景图引入资产与适配成本。
 */
@Composable
fun SummaryHero(
    count: Int,
    days: Int,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // 渐变按配色缓存：不 remember 的话每次重组都重新分配一个 Brush 与三个颜色列表。
    val brush = remember(
        scheme.primaryContainer,
        scheme.tertiaryContainer,
        scheme.secondaryContainer,
    ) {
        Brush.linearGradient(
            listOf(
                scheme.primaryContainer,
                scheme.tertiaryContainer,
                scheme.secondaryContainer,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DinnerDimens.heroMinHeight)
            .clip(RoundedCornerShape(DinnerDimens.radiusCard))
            .background(brush)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // 两个半透明装饰圆，压在右下角制造轻微的光斑层次。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = DinnerDimens.spaceMd, y = DinnerDimens.spaceMd)
                .size(DinnerDimens.decorCircleLg)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = DinnerDimens.alphaTint)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = DinnerDimens.spaceXl, y = -DinnerDimens.spaceMd)
                .size(DinnerDimens.decorCircleMd)
                .clip(CircleShape)
                .background(scheme.surface.copy(alpha = DinnerDimens.alphaSubtle)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DinnerDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = count.toString(),
                        // 大号数字加一点字距，读数不粘连。
                        style = MaterialTheme.typography.displaySmall.copy(
                            letterSpacing = DinnerDimens.letterSpacingCode,
                        ),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = " 条记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.padding(bottom = DinnerDimens.spaceXxs),
                    )
                }
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Text(
                    text = if (days > 0) "跨越 $days 天，继续一起吃好" else "还没开始，记下第一顿吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (onClick != null) {
                CalendarEntryPill()
            }
        }
    }
}

/** 摘要卡右侧的月历入口胶囊：图标 + 文字，明确「这里能点」。 */
@Composable
private fun CalendarEntryPill() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .height(DinnerDimens.pillHeight)
            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
            .background(scheme.surface.copy(alpha = DinnerDimens.alphaOverlaySoft))
            .padding(horizontal = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(DinnerDimens.iconSm),
        )
        Text(
            text = "日历",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * 一个统计磁贴。
 *
 * [onClick] 为空时只是展示；非空时整格可点，且右下角出现箭头，
 * 让「数字能点进去看明细」这件事不需要用户试出来。
 */
data class StatTile(
    val value: String,
    val label: String,
    val onClick: (() -> Unit)? = null,
)

/** 三宫格统计卡片，供「我的」页面展示核心数字。 */
@Composable
fun StatTiles(
    items: List<StatTile>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        items.forEach { item ->
            val clickable = if (item.onClick != null) {
                Modifier.clickable(role = Role.Button, onClick = item.onClick)
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DinnerDimens.radiusMd))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(clickable)
                    .padding(vertical = DinnerDimens.spaceSm, horizontal = DinnerDimens.spaceXs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        letterSpacing = DinnerDimens.letterSpacingCode,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.onClick != null) {
                        Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(DinnerDimens.iconSm),
                        )
                    }
                }
            }
        }
    }
}
