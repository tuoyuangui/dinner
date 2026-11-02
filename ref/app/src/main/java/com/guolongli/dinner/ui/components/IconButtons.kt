package com.guolongli.dinner.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 带 48dp 最小点击区的返回按钮。用 Material3 稳定版 IconButton。
 *
 * minWidth / minHeight 显式声明，避免上游用 size 把点击区压到 48dp 以下。
 */
@Composable
fun BackIconButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onBack, modifier = modifier.minTouchTarget()) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = tint,
        )
    }
}

/** 通用图标按钮，点击区同样不小于 48dp。 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = modifier.minTouchTarget()) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
        )
    }
}

/** 把点击区撑到可访问性下限（48dp），不影响图标自身尺寸。 */
private fun Modifier.minTouchTarget(): Modifier = this.sizeIn(
    minWidth = DinnerDimens.touchTargetMin,
    minHeight = DinnerDimens.touchTargetMin,
)
