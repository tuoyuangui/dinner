package com.guolongli.dinner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 1..5 星评分。
 *
 * - 可编辑时点击第 1..5 颗星选值；再点当前值那颗星会清零（容错）。
 * - 只读时仅展示。
 * - 动画让星星变化柔和。
 *
 * 无障碍：五颗星各自挂「评分 N 星」会让读屏念出五个互相矛盾的节点，
 * 而且每颗的内容还会随当前值变化。这里改成——
 * - 只读：整行合成一个节点，一次念完「评分 4 星，共 5 星」。
 * - 可编辑：每颗星只表达位置（「第 N 星」），整行不再抢读。
 */
@Composable
fun StarRating(
    value: Int?,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onValueChange: (Int?) -> Unit = {},
    starSize: Dp = DinnerDimens.iconLg,
    spacing: Dp = DinnerDimens.spaceXs,
) {
    val colors = DinnerTheme.colors
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .then(
                if (editable) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics {
                        contentDescription = if (value == null) {
                            "未评分"
                        } else {
                            "评分 $value 星，共 ${DinnerDimens.starCount} 星"
                        }
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (index in 1..DinnerDimens.starCount) {
            val filled = index <= (value ?: 0)
            // 单颗只说位置，值由整行（只读）或点击标签（可编辑）表达。
            val positionText = "第 $index 星"
            val tint by animateColorAsState(
                targetValue = if (filled) colors.starActive else colors.starInactive,
                animationSpec = tween(DinnerDimens.animStandardMs),
                label = "starTint",
            )
            // 点击区撑到 48dp，图标只占 starSize：外层决定手指，内层决定视觉。
            Box(
                modifier = Modifier
                    .sizeIn(
                        minWidth = DinnerDimens.touchTargetMin,
                        minHeight = DinnerDimens.touchTargetMin,
                    )
                    .clip(CircleShape)
                    .then(
                        if (editable) {
                            Modifier
                                .semantics {
                                    contentDescription = positionText
                                    selected = filled
                                    role = Role.Button
                                }
                                .clickableStar(
                                    contentDescription = positionText,
                                    onTap = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onValueChange(if (value == index) null else index)
                                    },
                                )
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(starSize),
                )
            }
        }
    }
}

/** 可编辑时才消费点击。 */
private fun Modifier.clickableStar(
    contentDescription: String,
    onTap: () -> Unit,
): Modifier = clickable(
    role = Role.Button,
    onClickLabel = contentDescription,
    onClick = onTap,
)
