package com.guolongli.dinner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 内容分区卡片，统一圆角 / 内外边距 / 描边。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    elevation: Dp = DinnerDimens.elevationFlat,
    contentPadding: Dp = DinnerDimens.spaceSm,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(
            DinnerDimens.borderThin,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = DinnerDimens.spaceSm),
                )
            }
            content()
        }
    }
}

/** 低对比度分隔线（细 Surface 实现，避免依赖已废弃的 Divider）。 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(DinnerDimens.borderThin),
        color = MaterialTheme.colorScheme.outlineVariant,
        contentColor = Color.Transparent,
    ) {}
}