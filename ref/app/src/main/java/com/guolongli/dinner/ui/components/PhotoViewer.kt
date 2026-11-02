package com.guolongli.dinner.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 照片全屏查看器：支持捏合缩放。
 *
 * 用全屏 Dialog（usePlatformDefaultWidth=false），避免走 Navigation 增加复杂度。
 * 只有一张图，用平台变换手势实现，不依赖任何 Pager 或 PagerState。
 */
@Composable
fun PhotoViewer(
    model: Any?,
    imageDesc: String,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = DinnerDimens.alphaScrim)),
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var viewSize by remember { mutableStateOf(IntSize.Zero) }

            /**
             * 放大后可以拖动看局部；缩回 1x 时偏移归零，避免图片卡在角落。
             *
             * 拖动范围限制在「放大出来的那一圈」内，松手不会把图拖出屏幕之外。
             */
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, DinnerDimens.photoMaxScale)
                offset = if (scale <= 1f) {
                    Offset.Zero
                } else {
                    val maxX = (viewSize.width * (scale - 1f)) / 2f
                    val maxY = (viewSize.height * (scale - 1f)) / 2f
                    Offset(
                        x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                        y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .transformable(transformState)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = imageDesc,
                    // 全屏用 Fit：整张图完整可见，靠捏合放大看细节，不做任何裁切。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 关闭按钮，置于安全区。
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DinnerDimens.spaceXs)
                    .semantics { contentDescription = "关闭查看" },
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭查看",
                    tint = DinnerTheme.colors.onMedia,
                )
            }
        }
    }
}