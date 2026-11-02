package com.guolongli.dinner.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.guolongli.dinner.domain.media.MediaSource
import com.guolongli.dinner.ui.theme.DinnerColors
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 媒体拾取区：相册照片 / 相册视频 / 拍照 三个入口。
 *
 * - 相册用 PickVisualMedia（系统照片选择器，无需任何存储权限）。
 * - 相机用 TakePicturePreview（返回缩略 Bitmap，无需配置 FileProvider，
 *   因此在没有 Provider 声明也能用，代价是清晰度较低）。
 *
 * 回调通过 [onSourcePicked] 把 [MediaSource] 交给 ViewModel 走导入流程，
 * 不在 Composable 里做任何网络或文件 I/O。
 */
@Composable
fun MediaPicker(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSourcePicked: (MediaSource) -> Unit,
) {
    val colors = DinnerTheme.colors

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) onSourcePicked(MediaSource.Image(uri))
    }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) onSourcePicked(MediaSource.Video(uri))
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap: Bitmap? ->
        if (bitmap != null) onSourcePicked(MediaSource.CameraBitmap(bitmap))
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        MediaPickerSlot(
            title = "照片",
            icon = Icons.Default.PhotoLibrary,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            colors = colors,
        )
        MediaPickerSlot(
            title = "视频",
            icon = Icons.Default.Videocam,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            onClick = {
                videoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            },
            colors = colors,
        )
        MediaPickerSlot(
            title = "拍照",
            icon = Icons.Default.PhotoCamera,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            onClick = { cameraLauncher.launch(null) },
            colors = colors,
        )
    }
}

@Composable
private fun MediaPickerSlot(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: DinnerColors,
) {
    val shape = RoundedCornerShape(DinnerDimens.radiusMd)
    val dashColor = if (enabled) {
        colors.dashedBorder
    } else {
        colors.dashedBorder.copy(alpha = DinnerDimens.alphaDisabled)
    }
    Column(
        modifier = modifier
            .aspectRatio(DinnerDimens.photoAspectRatio)
            .clip(shape)
            .background(colors.photoPlaceholder)
            .border(
                width = DinnerDimens.borderThin,
                color = dashColor,
                shape = shape,
            )
            // 禁用时去掉 clickable，但内容照旧渲染——
            // 早先是整块留空，用户只看到一个空方框，看不出这里本来是什么。
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(DinnerDimens.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 图标与文字始终存在，禁用态只整体降到 alphaDisabled。
        val contentAlpha = if (enabled) 1f else DinnerDimens.alphaDisabled
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = colors.onPhotoPlaceholder.copy(alpha = contentAlpha),
            modifier = Modifier.size(DinnerDimens.iconLg),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onPhotoPlaceholder.copy(alpha = contentAlpha),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
