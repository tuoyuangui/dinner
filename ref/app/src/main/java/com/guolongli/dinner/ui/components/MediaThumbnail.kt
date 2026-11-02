package com.guolongli.dinner.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 把记录里的媒体路径映射为 Coil 可加载的 model。
 *
 * 关键约束：网盘路径（形如 /apps/dinner/meals/2026/10/x.jpg）**不是**可加载地址，
 * 绝不能直接交给 Coil。远端媒体必须由 ViewModel 通过 MediaCache 下载成本地文件，
 * 再把本地路径写回记录；这里只接受真实存在的本地文件。
 *
 * - 照片：直接读本地文件。
 * - 视频：Coil 无法解码视频帧，只能读导入时生成的封面帧。
 */
fun mediaModel(
    localPath: String?,
    thumbnailPath: String?,
    mediaType: MediaType,
): Any? = when (mediaType) {
    MediaType.IMAGE -> MealRecord.existingFile(localPath) ?: MealRecord.existingFile(thumbnailPath)
    MediaType.VIDEO -> MealRecord.existingFile(thumbnailPath)
}

/**
 * 统一媒体缩略图：等比缩放，绝不裁切、绝不拉伸。
 *
 * 容器比例的取法：
 * - 默认（[adaptToImage] = false，九宫格与卡片单图）：容器锁死在 [aspectRatio]
 *   （卡片单图是 [DinnerDimens.photoAspectRatio]，网格格子是 1f），
 *   同列行高才不会参差；格子内部仍然等比缩放，整张图完整可见，留白处用占位色。
 * - [adaptToImage] 为 true：加载成功后跟随图片真实宽高比，竖图就是竖图、
 *   横图就是横图，不再被强行塞进 4:3 再裁掉两边。极端长图 / 全景图
 *   会被限制在 [DinnerDimens.mediaAspectMin]..[DinnerDimens.mediaAspectMax] 之间，
 *   否则一张截图能把整个时间线撑开一屏。
 *   这是「完整可见」特例，走 [MediaThumbnailAdaptive]，默认路径不违反 4:3 规范。
 *
 * 加载 / 失败 / 待下载时保留容器骨架并显示友好文案，不闪空。
 * 视频额外叠一个播放徽标与时长角标。
 */
@Composable
fun MediaThumbnail(
    localPath: String?,
    thumbnailPath: String?,
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    hint: String? = null,
    durationText: String? = null,
    shape: Dp = DinnerDimens.radiusMd,
    aspectRatio: Float = DinnerDimens.photoAspectRatio,
    adaptToImage: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // mediaModel 内部要走 MealRecord.existingFile（File.isFile + length），
    // 是磁盘 I/O。没有 remember 的话每次重组都会对每个缩略图重新 stat 一次，
    // 滚动长列表时这条路径直接打在主线程上。这里按输入缓存住。
    val model = remember(localPath, thumbnailPath, mediaType) {
        mediaModel(localPath, thumbnailPath, mediaType)
    }
    val colors = DinnerTheme.colors
    // 一个 painter 同时供「读 intrinsicSize」与「渲染」使用：
    // 早先这里额外建了一个 AsyncImage，同一张图被加载两遍、占两份 Bitmap。
    val painter = rememberAsyncImagePainter(model = model)
    val state by painter.state.collectAsState()

    // 只有真正解码成功才拿得到原图尺寸；此时才让容器跟随图片。
    val imageRatio = if (state is AsyncImagePainter.State.Success) {
        painter.intrinsicSize
            .takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
            ?.let { it.width / it.height }
    } else {
        null
    }
    val ratio = if (adaptToImage) {
        imageRatio?.coerceIn(DinnerDimens.mediaAspectMin, DinnerDimens.mediaAspectMax)
            ?: aspectRatio
    } else {
        aspectRatio
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(shape))
            .background(colors.photoPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        val isError = state is AsyncImagePainter.State.Error
        if (model == null || isError) {
            Text(
                text = when {
                    isError -> if (mediaType == MediaType.VIDEO) "视频封面加载失败" else "图片加载失败"
                    hint != null -> hint
                    mediaType == MediaType.VIDEO -> "视频待下载"
                    else -> "图片待下载"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onPhotoPlaceholder,
            )
        } else {
            Image(
                painter = painter,
                contentDescription = if (mediaType == MediaType.VIDEO) "视频封面" else "餐食照片",
                // 等比缩放完整显示：宁可留白，也不裁掉画面。
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (mediaType == MediaType.VIDEO) {
            VideoPlayBadge()
            if (!durationText.isNullOrBlank()) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onMedia,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(DinnerDimens.spaceXs)
                        .clip(RoundedCornerShape(DinnerDimens.radiusXs))
                        .background(colors.mediaScrim.copy(alpha = DinnerDimens.alphaOverlay))
                        .padding(horizontal = DinnerDimens.spaceXs, vertical = DinnerDimens.spaceXxs),
                )
            }
        }

        if (onClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick),
            )
        }
    }
}

/**
 * 「完整可见」特例：容器跟随图片真实比例，不强行套 4:3。
 *
 * 详情页逐张看大图这类场景才用它。默认路径（[MediaThumbnail]）一律锁 4:3，
 * 保证时间线行高整齐，不违反 UI 规范里的 4:3 要求。
 * 参数与 [MediaThumbnail] 完全一致，只把 adaptToImage 固定为 true。
 */
@Composable
fun MediaThumbnailAdaptive(
    localPath: String?,
    thumbnailPath: String?,
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    hint: String? = null,
    durationText: String? = null,
    shape: Dp = DinnerDimens.radiusMd,
    aspectRatio: Float = DinnerDimens.photoAspectRatio,
    onClick: (() -> Unit)? = null,
) {
    MediaThumbnail(
        localPath = localPath,
        thumbnailPath = thumbnailPath,
        mediaType = mediaType,
        modifier = modifier,
        hint = hint,
        durationText = durationText,
        shape = shape,
        aspectRatio = aspectRatio,
        adaptToImage = true,
        onClick = onClick,
    )
}

/** 居中的播放按钮，半透明圆底 + 语义前景色，保证任何封面上都能看清。 */
@Composable
private fun BoxScope.VideoPlayBadge() {
    val colors = DinnerTheme.colors
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(DinnerDimens.videoPlayBadgeSize)
            .clip(CircleShape)
            .background(colors.mediaScrim.copy(alpha = DinnerDimens.alphaOverlay)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放视频",
            tint = colors.onMedia,
            modifier = Modifier.size(DinnerDimens.iconXl),
        )
    }
}
