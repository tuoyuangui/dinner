package com.guolongli.dinner.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/** 全屏加载态。 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "加载中…",
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                strokeWidth = DinnerDimens.progressStroke,
                // primary 在浅色底上只有 2.6:1，指示色同样要用强调文字色。
                color = DinnerTheme.colors.textAccent,
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 空状态。
 *
 * 图标放进低透明度圆底里，避免一枚孤零零的线性图标显得像加载失败；
 * 文案分「结论 + 下一步」两行，用户知道该做什么。
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    message: String,
    hint: String? = null,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DinnerDimens.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(DinnerDimens.decorCircleLg)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DinnerDimens.iconLg),
                )
            }
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            action()
        }
    }
}

/** 错误状态，带可选重试。文案来自 toUserText，绝不显示原始异常。 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DinnerDimens.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(DinnerDimens.decorCircleLg)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(DinnerDimens.iconLg),
            )
        }
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            OutlinedButton(onClick = onRetry) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
                // 按钮内部是横向排列，间距要靠 width，height 在这条轴上不生效。
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 时间线首屏骨架屏：摘要英雄卡 + 媒体骨架 + 两行文字条。
 *
 * 首屏第一次进应用时数据是空的，转圈会让「还没有记录」和「还没加载完」混在一起；
 * 骨架屏把即将出现的结构先画出来，加载完成后直接替换。
 *
 * 与 [LoadingState] 互不替代：整页转圈用于「不知道要等多久」的操作，
 * 骨架屏用于「结构已知、只等数据」的列表首屏，因此这里不改动 LoadingState 的行为。
 */
@Composable
fun TimelineSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 2,
) {
    val shimmer = rememberShimmerProgress()
    // 占位底色取 surface，微光取 surfaceVariant：
    // 浅色下微光更暗、深色下更亮，两种模式都能看出「在扫」，不会变成一片死灰。
    val base = MaterialTheme.colorScheme.surface
    val highlight = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DinnerDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
    ) {
        // 英雄摘要卡
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DinnerDimens.radiusCard),
            color = base,
            border = BorderStroke(
                DinnerDimens.borderThin,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DinnerDimens.heroMinHeight)
                    .clip(RoundedCornerShape(DinnerDimens.radiusCard))
                    .shimmer(base, highlight, shimmer),
            )
        }
        repeat(itemCount) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DinnerDimens.radiusCard),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    DinnerDimens.borderThin,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(DinnerDimens.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                ) {
                    // 作者行：圆形头像 + 一条短文字
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                    ) {
                        Spacer(
                            modifier = Modifier
                                .size(DinnerDimens.avatarMd)
                                .clip(CircleShape)
                                .shimmer(base, highlight, shimmer),
                        )
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth(SKELETON_TITLE_WIDTH)
                                .height(DinnerDimens.skeletonLine)
                                .clip(RoundedCornerShape(DinnerDimens.radiusXs))
                                .shimmer(base, highlight, shimmer),
                        )
                    }
                    // 媒体骨架
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DinnerDimens.skeletonPhotoHeight)
                            .clip(RoundedCornerShape(DinnerDimens.radiusMd))
                            .shimmer(base, highlight, shimmer),
                    )
                    // 两行文字条
                    SkeletonLine(shimmer, 1f, base, highlight)
                    SkeletonLine(shimmer, SKELETON_TEXT_WIDTH, base, highlight)
                }
            }
        }
    }
}

/** 骨架屏里的一条文字占位条。 */
@Composable
private fun SkeletonLine(
    progress: State<Float>,
    widthFraction: Float,
    base: Color,
    highlight: Color,
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(DinnerDimens.skeletonLine)
            .clip(RoundedCornerShape(DinnerDimens.radiusXs))
            .shimmer(base, highlight, progress),
    )
}

/**
 * 微光扫过的进度（0..1），循环一次 [DinnerDimens.animShimmerMs] 毫秒。
 *
 * 返回的是 State 而不是值：值只在绘制阶段被读（[Modifier.shimmer] 的 drawBehind 里），
 * 于是动画每帧只触发重绘，不会把整棵骨架子树重组一遍。
 */
@Composable
private fun rememberShimmerProgress(): State<Float> {
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "skeletonShimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DinnerDimens.animShimmerMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
}

/**
 * 给占位块铺一层从左到右扫过的微光。
 *
 * 渐变端点按实际绘制宽度在 drawBehind 里算，因此不需要把宽度传进来，
 * 也不依赖任何重组期测量。
 */
private fun Modifier.shimmer(base: Color, highlight: Color, progress: State<Float>): Modifier =
    this.drawBehind {
        val width = size.width
        if (width <= 0f) return@drawBehind
        // 从完全在左侧之外扫到完全在右侧之外。
        val start = -width + progress.value * width * 2f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x = start, y = 0f),
                end = Offset(x = start + width, y = size.height),
            ),
        )
    }

/** 骨架屏文字条的宽度占比。 */
private const val SKELETON_TITLE_WIDTH = 0.45f
private const val SKELETON_TEXT_WIDTH = 0.72f
