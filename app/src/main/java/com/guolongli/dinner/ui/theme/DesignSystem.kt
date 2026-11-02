package com.guolongli.dinner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 尺寸令牌：画布以 390 × 844 绘制，1 画布 px = 1 dp，因此这里可以直接照抄节点数值。
 * 全应用只用 8dp 栅格派生值：4 / 8 / 10 / 12 / 14 / 16 / 20 / 21。
 */
object Dimens {
    // 画布基准
    val ScreenWidth = 390.dp
    val ScreenHeight = 844.dp
    val StatusBarHeight = 62.dp
    val BottomBarHeight = 88.dp

    // 版面
    val PagePadding = 20.dp      // 内容区左右留白
    val CardPadding = 16.dp      // 卡片内边距
    val BarSidePadding = 21.dp   // 底部导航左右留白

    // 间距
    val GapXs = 4.dp
    val GapS = 6.dp
    val GapM = 8.dp
    val GapL = 10.dp
    val GapXl = 12.dp

    // 圆角（统一走圆角、不出现直角矩形：小控件 8、卡片 16，观感更现代）
    val RadiusSmall = 8.dp       // 标签、输入小按钮、媒体小图
    val RadiusMedium = 12.dp     // 备注输入框、开关卡片
    val RadiusCard = 16.dp       // 内容卡片
    val RadiusPill = 999.dp      // 胶囊

    // 组件尺寸
    val CardBorderWidth = 1.dp
    val ChipHeight = 32.dp
    val ChipPaddingH = 14.dp
    val NavPillHeight = 62.dp
    val NavPillPadding = 4.dp
    val NavItemRadius = 26.dp
    val FabSize = 56.dp
    val FabBottomInset = 12.dp
    val AvatarLarge = 52.dp
    val AvatarMedium = 36.dp
    val AvatarSmall = 32.dp
    val AvatarComment = 18.dp
    val MediaTileHeight = 100.dp
    val MediaTileEditSize = 107.dp
    val CalendarCellHeight = 36.dp
    val CalendarSelectedSize = 30.dp
}

/**
 * 字重与字号令牌。字体族用系统默认（Android 中文环境下即 Noto Sans CJK / Noto Sans SC），
 * 与画布的 Noto Sans SC 保持一致；SemiBold = 600，Medium = 500。
 */
private val Family = FontFamily.Default

object DinnerText {
    val appTitle = TextStyle(fontFamily = Family, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val screenTitle = TextStyle(fontFamily = Family, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val dialogTitle = TextStyle(fontFamily = Family, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val navTitle = TextStyle(fontFamily = Family, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val clock = TextStyle(fontFamily = Family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    val bodyLarge = TextStyle(fontFamily = Family, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Family, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val bodyMedium = TextStyle(fontFamily = Family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val bodyStrong = TextStyle(fontFamily = Family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    val bodySmall = TextStyle(fontFamily = Family, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val bodySmallMedium = TextStyle(fontFamily = Family, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    val caption = TextStyle(fontFamily = Family, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val captionMedium = TextStyle(fontFamily = Family, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    val footnote = TextStyle(fontFamily = Family, fontSize = 11.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val footnoteMedium = TextStyle(fontFamily = Family, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

    val micro = TextStyle(fontFamily = Family, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

private val DinnerTypography = Typography(
    titleLarge = DinnerText.screenTitle,
    titleMedium = DinnerText.navTitle,
    bodyLarge = DinnerText.body,
    bodyMedium = DinnerText.bodySmall,
    bodySmall = DinnerText.caption,
    labelLarge = DinnerText.bodyMedium,
    labelMedium = DinnerText.captionMedium,
    labelSmall = DinnerText.footnote,
)

private val DinnerShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.RadiusSmall),
    small = RoundedCornerShape(Dimens.RadiusSmall),
    medium = RoundedCornerShape(Dimens.RadiusMedium),
    large = RoundedCornerShape(Dimens.RadiusCard),
    extraLarge = RoundedCornerShape(Dimens.RadiusCard),
)

/**
 * 亮/暗两套色板：设计稿用的是 TDesign 品牌蓝，不跟随 Material You 动态取色，
 * 否则壁纸一变整套颜色就跑偏。深色是同语义的暗色映射。
 */
private val DinnerLightColors = lightColorScheme(
    primary = Color(0xFF0052D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2F3FF),
    onPrimaryContainer = Color(0xFF0052D9),
    secondary = Color(0xFF2BA471),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFE37318),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEEEEEE),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFDDDDDD),
    outlineVariant = Color(0xFFEEEEEE),
    error = Color(0xFFD54941),
    onError = Color(0xFFFFFFFF),
)

private val DinnerDarkColors = darkColorScheme(
    primary = Color(0xFF4D8DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1A2440),
    onPrimaryContainer = Color(0xFF7FA8FF),
    secondary = Color(0xFF2BA471),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFB27A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF8D85),
    onError = Color(0xFFFFFFFF),
)

/**
 * @param dark 是否深色。写进 [DinnerThemeState] 后，
 * 页面里直接引用的色板 getter 会跟着这个状态走，切主题整树重绘。
 */
@Composable
fun DinnerTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    // 组合期直接写入（不是 SideEffect）：色板 getter 在同一次组合里就要读到
    // 正确的主题值，放到副作用里会先用旧值画一帧再闪一下
    DinnerThemeState.isDark.value = dark
    MaterialTheme(
        colorScheme = if (dark) DinnerDarkColors else DinnerLightColors,
        typography = DinnerTypography,
        shapes = DinnerShapes,
        content = content,
    )
}
