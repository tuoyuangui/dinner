package com.guolongli.dinner.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.image.LocalImage
import com.guolongli.dinner.ui.image.rememberVideoThumbPath
import com.guolongli.dinner.ui.model.MediaUiModel
import com.guolongli.dinner.ui.model.RecordType
import com.guolongli.dinner.ui.theme.BadgeScrim
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BorderStrongColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DividerColor
import com.guolongli.dinner.ui.theme.MediaGlyph
import com.guolongli.dinner.ui.theme.MediaPlaceholderBottom
import com.guolongli.dinner.ui.theme.MediaPlaceholderTop
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.StarActive
import com.guolongli.dinner.ui.theme.StarInactive
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.SwitchOffTrack
import com.guolongli.dinner.ui.theme.SwitchOnTrack
import com.guolongli.dinner.ui.theme.SwitchThumb
import com.guolongli.dinner.ui.theme.TextDisabled
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* ----------------------------------------------------------------------------
 * 基础组件：尺寸、圆角、间距全部取自 Dimens，颜色取自 Color.kt。
 * 页面里不再出现裸数值。
 * -------------------------------------------------------------------------- */

/**
 * 成员头像：有照片就显示照片，没有就是纯色圆 + 姓氏首字。
 * 两种形态尺寸、圆角完全一致，切换时不会跳版。
 */
@Composable
fun MemberAvatar(
    initial: String,
    color: Color,
    size: Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        // 首字先画，照片后画：照片解码出来就盖住它，还没解码时看到的也不是空洞
        Text(
            text = initial,
            style = DinnerText.bodyMedium.copy(fontSize = fontSize, lineHeight = fontSize * 1.25f),
            color = OnBrand,
        )
        if (avatarPath != null) {
            LocalImage(
                path = avatarPath,
                maxPx = (size.value * 3).toInt(),
                // 头像是圆形底，必须裁满（Crop）才不会露出渐变底色
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 记录类型标签：20dp 高、3dp 圆角、左右 6dp。 */
@Composable
fun TypeTag(type: RecordType, modifier: Modifier = Modifier) {
    TagBox(text = type.label, bg = type.tagBg, fg = type.tagFg, modifier = modifier)
}

/** 角色标签：管理员 / 成员，18dp 高。 */
@Composable
fun RoleTag(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(18.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(bg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DinnerText.micro, color = fg)
    }
}

@Composable
private fun TagBox(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(20.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(bg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DinnerText.footnoteMedium, color = fg)
    }
}

/** 五星评分：按 24 × 24 之外单独算的星形，间距 4dp。 */
@Composable
fun StarRating(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Dp = 16.dp,
    gap: Dp = 4.dp,
    starCount: Int = 5,
    activeColor: Color = StarActive,
    inactiveColor: Color = StarInactive,
) {
    val totalWidth = starSize * starCount + gap * (starCount - 1)
    Canvas(modifier.size(width = totalWidth, height = starSize)) {
        val diameter = starSize.toPx()
        val step = diameter + gap.toPx()
        val outer = diameter / 2f
        val inner = outer * 0.46f
        for (i in 0 until starCount) {
            drawPath(
                path = starPath(i * step + diameter / 2f, diameter / 2f, outer, inner),
                color = if (i < rating) activeColor else inactiveColor,
            )
        }
    }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
    val path = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = (-PI / 2 + i * PI / 5).toFloat()
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * 媒体格子：有本地文件就显示照片，没有就是灰渐变 + 图片符号。
 * 只有云端那一份的记录（别人同步下来、还没取回）就走占位，尺寸圆角不变。
 *
 * [contentScale] 决定画面怎么填这个格子：
 * - [ContentScale.Fit]：完整显示、不裁切、不变形（单张图用，保留原比例）；
 * - [ContentScale.Crop]：铺满格子、边缘裁掉（多张图的网格用，格子才整齐）。
 *
 * 视频显示的是从视频里抽出来的封面帧（[rememberVideoThumbPath]），
 * 右下角叠一个时长角标。直接把 mp4 交给图片解码只会得到空白，所以必须走封面。
 */
@Composable
fun MediaTile(
    media: MediaUiModel = MediaUiModel(),
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dimens.RadiusSmall,
    glyphSize: Dp = 24.dp,
    contentScale: ContentScale = ContentScale.Fit,
) {
    // 视频取封面，照片取自己；封面还没抽出来时是 null，先画占位
    val thumbPath = if (media.isVideo) rememberVideoThumbPath(media.localPath) else null
    val displayPath = if (media.isVideo) thumbPath else media.localPath

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.verticalGradient(listOf(MediaPlaceholderTop, MediaPlaceholderBottom))),
    ) {
        if (displayPath != null) {
            LocalImage(
                path = displayPath,
                maxPx = 520,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LineIcon(
                // 视频没封面时给播放符号，比图片符号更贴近内容
                spec = if (media.isVideo) LineIconSpec.PLAY else LineIconSpec.IMAGE,
                tint = MediaGlyph.copy(alpha = 0.85f),
                size = glyphSize,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (media.isVideo && media.durationLabel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Dimens.GapXs)
                    .clip(RoundedCornerShape(Dimens.RadiusSmall))
                    .background(BadgeScrim)
                    .padding(horizontal = Dimens.GapXs, vertical = 1.dp),
            ) {
                Text(text = media.durationLabel, style = DinnerText.micro, color = OnBrand)
            }
        }
    }
}

/** 筛选 / 类型胶囊：32dp 高、全圆角、左右 14dp。 */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.RadiusPill)
    Box(
        modifier = modifier
            .height(Dimens.ChipHeight)
            .clip(shape)
            .background(if (selected) BrandBlue else SurfaceWhite)
            .then(if (selected) Modifier else Modifier.border(Dimens.CardBorderWidth, BorderStrongColor, shape))
            .clickable { onClick() }
            .padding(horizontal = Dimens.ChipPaddingH),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) DinnerText.bodySmallMedium else DinnerText.bodySmall,
            color = if (selected) OnBrand else TextSecondary,
        )
    }
}

/** 主按钮：3dp 圆角、品牌蓝底。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(BrandBlue)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DinnerText.bodyLarge, color = OnBrand)
    }
}

/** 悬浮新建按钮：56dp 圆、右下角内缩 20 / 12。 */
@Composable
fun FloatingAddButton(onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Dimens.FabSize)
            .clip(CircleShape)
            .background(BrandBlue)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(spec = LineIconSpec.PLUS, tint = OnBrand, size = 24.dp, strokeWidth = 2.2f)
    }
}

enum class MainTab(val label: String, val icon: LineIconSpec) {
    TIMELINE("时间线", LineIconSpec.LIST),
    MINE("我的", LineIconSpec.USER),
}

@Composable
fun BottomPillNav(
    selected: MainTab,
    modifier: Modifier = Modifier,
    onSelect: (MainTab) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.BottomBarHeight)
            .background(SurfaceWhite)
            .drawBehind {
                drawLine(
                    color = BorderColor,
                    start = Offset(0f, 0.5f),
                    end = Offset(size.width, 0.5f),
                    strokeWidth = Dimens.CardBorderWidth.toPx(),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = Dimens.BarSidePadding,
                    end = Dimens.BarSidePadding,
                    top = 12.dp,
                    bottom = 14.dp,
                )
                .clip(RoundedCornerShape(Dimens.RadiusPill))
                .background(SurfaceMuted)
                .padding(Dimens.NavPillPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapXs),
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Dimens.NavItemRadius))
                        .background(if (isSelected) BrandBlue else Color.Transparent)
                        .clickable { onSelect(tab) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LineIcon(
                        spec = tab.icon,
                        tint = if (isSelected) OnBrand else TextSecondary,
                        size = 18.dp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = DinnerText.micro,
                        color = if (isSelected) OnBrand else TextSecondary,
                    )
                }
            }
        }
    }
}

/** 白卡：9dp 圆角 + 1dp #E8E8E8 描边。 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard)),
        content = content,
    )
}

/** 分组小标题，上方 16dp / 下方 8dp，保持 8dp 节奏。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DinnerText.bodySmallMedium,
        color = TextSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.CardPadding, bottom = Dimens.GapM),
    )
}

/** 设置分组卡：左右 16dp 内边距，行间用 1dp 分隔线。 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(modifier) {
        Column(Modifier.padding(horizontal = Dimens.CardPadding), content = content)
    }
}

/** 设置行：52dp 高，左图标 + 标题，右侧值 + 箭头。 */
@Composable
fun SettingsRow(
    icon: LineIconSpec,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
            ) {
                LineIcon(spec = icon, tint = TextSecondary, size = 20.dp)
                Text(text = title, style = DinnerText.body, color = TextPrimary)
            }
            if (trailing != null) {
                trailing()
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
                ) {
                    if (value != null) {
                        Text(text = value, style = DinnerText.caption, color = TextTertiary)
                    }
                    LineIcon(spec = LineIconSpec.CHEVRON_RIGHT, tint = TextDisabled, size = 16.dp)
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(thickness = 1.dp, color = DividerColor)
        }
    }
}

/** 开关：44 × 26 轨道、22 圆点、左右 2dp 内缩。 */
@Composable
fun DinnerSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        label = "switchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) SwitchOnTrack else SwitchOffTrack)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(SwitchThumb),
        )
    }
}
