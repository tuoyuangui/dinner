package com.guolongli.dinner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.theme.DinnerColors

/**
 * 记录类型语义色。
 *
 * 约束：该色只用于圆点 / 描边 / 背景着色，绝不作为正文文字颜色——
 * 类型色在浅色面上的非文字对比度满足 3:1，但不都满足正文所需的 4.5:1。
 */
fun mealAccentFor(colors: DinnerColors, type: MealType): Color = when (type) {
    MealType.BREAKFAST -> colors.mealBreakfast
    MealType.LUNCH -> colors.mealLunch
    MealType.DINNER -> colors.mealDinner
    MealType.SNACK -> colors.mealSnack
    MealType.SCENERY -> colors.momentScenery
    MealType.STUDY -> colors.momentStudy
    MealType.SPORT -> colors.momentSport
    MealType.CREATION -> colors.momentCreation
    MealType.OTHER -> colors.momentOther
}

/**
 * 记录类型选择胶囊。
 *
 * 选中态用低透明度的类型色着色背景 + 实色描边，文字始终使用 onSurface，
 * 这样无论选中与否，文字对比度都稳定达标。
 */
@Composable
fun MealTypeChip(
    type: MealType,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DinnerTheme.colors
    val accent = mealAccentFor(colors, type)
    val scheme = MaterialTheme.colorScheme

    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = DinnerDimens.alphaTint) else scheme.surfaceVariant,
        animationSpec = tween(DinnerDimens.animStandardMs),
        label = "chipBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accent else scheme.outlineVariant,
        animationSpec = tween(DinnerDimens.animStandardMs),
        label = "chipBorder",
    )
    val dotColor by animateColorAsState(
        targetValue = if (selected) accent else scheme.outline,
        animationSpec = tween(DinnerDimens.animStandardMs),
        label = "chipDot",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DinnerDimens.touchTargetMin)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(DinnerDimens.borderThin, borderColor, MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .padding(horizontal = DinnerDimens.spaceSm, vertical = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        Box(
            modifier = Modifier
                .size(DinnerDimens.dotSize)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = scheme.onSurface,
        )
    }
}

/** 供卡片元信息使用的小圆点 + 餐别文字。圆点承载颜色，文字保持高对比。 */
@Composable
fun MealTypeMarker(
    type: MealType,
    label: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val accent = mealAccentFor(DinnerTheme.colors, type)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
    ) {
        Box(
            modifier = Modifier
                .size(DinnerDimens.dotSize)
                .clip(CircleShape)
                .background(accent),
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}
