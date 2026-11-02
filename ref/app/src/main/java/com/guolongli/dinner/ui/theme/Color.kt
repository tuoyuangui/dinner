package com.guolongli.dinner.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 品牌色：暖橙。用作种子色与不支持动态取色时的回退主色。
 */
val BrandOrange = Color(0xFFFF7A45)

/**
 * 浅色配色。
 *
 * 关键对比度（相对亮度已逐项核算，均满足 WCAG AA）：
 * - onSurface #22252A / surface #FFFFFF = 15.4:1
 * - onPrimary #2A1100 / primary #FF7A45 = 6.9:1
 *   注意：白字放在 #FF7A45 上只有 2.6:1，不达 AA，因此主按钮一律用深色文字，
 *   而不是参考实现里的白字。
 * - onSurfaceVariant #5F5A55 / surface = 6.8:1
 * - outline #8A817A / surface = 3.8:1（满足 3:1 的图形边界要求）
 */
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFF7A45),
    onPrimary = Color(0xFF2A1100),
    primaryContainer = Color(0xFFFFE6D5),
    onPrimaryContainer = Color(0xFF7A2E0A),

    secondary = Color(0xFF7A5C4E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E0D6),
    onSecondaryContainer = Color(0xFF4A2C1E),

    tertiary = Color(0xFF9A5B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF3A2000),

    background = Color(0xFFF6F5F3),
    onBackground = Color(0xFF22252A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF22252A),
    surfaceVariant = Color(0xFFF1EEEB),
    onSurfaceVariant = Color(0xFF5F5A55),
    surfaceTint = Color(0xFFFF7A45),

    outline = Color(0xFF8A817A),
    outlineVariant = Color(0xFFD9D2CC),

    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1DF),
    onErrorContainer = Color(0xFF6B0F0F),

    scrim = Color(0xFF000000),
)

/**
 * 深色配色：专门为对比度调过，不是浅色的简单反色。
 *
 * 关键对比度（相对亮度已逐项核算，均满足 WCAG AA 或 AAA）：
 * - surface #1A1714 是暖调深灰而非纯黑，避免纯黑上的眩光与 OLED 拖影
 * - onSurface #EDE7E2 / surface = 15.0:1（AAA）
 * - onSurfaceVariant #C9C0B8 / surfaceVariant #2A2521 = 8.5:1（AAA）
 * - onPrimary #3A1200 / primary #FFB08C = 9.4:1
 * - outline #8A817A / surface = 4.8:1
 */
internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB08C),
    onPrimary = Color(0xFF3A1200),
    primaryContainer = Color(0xFF7A3218),
    onPrimaryContainer = Color(0xFFFFD9C4),

    secondary = Color(0xFFE0C0AE),
    onSecondary = Color(0xFF3A2418),
    secondaryContainer = Color(0xFF523829),
    onSecondaryContainer = Color(0xFFFFDCC9),

    tertiary = Color(0xFFE8A44C),
    onTertiary = Color(0xFF3A2000),
    tertiaryContainer = Color(0xFF6B4500),
    onTertiaryContainer = Color(0xFFFFDEA8),

    background = Color(0xFF12100E),
    onBackground = Color(0xFFEDE7E2),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFEDE7E2),
    surfaceVariant = Color(0xFF2A2521),
    onSurfaceVariant = Color(0xFFC9C0B8),
    surfaceTint = Color(0xFFFFB08C),

    outline = Color(0xFF8A817A),
    outlineVariant = Color(0xFF3A342E),

    error = Color(0xFFF87171),
    onError = Color(0xFF4A0000),
    errorContainer = Color(0xFF6B1616),
    onErrorContainer = Color(0xFFFFDAD6),

    scrim = Color(0xFF000000),
)

/**
 * Material 3 配色之外的产品专属语义色。
 *
 * 这些颜色不随动态取色漂移，因为它们承载「记录类型」与「同步状态」这类语义。
 * 使用约束：只用于圆点、描边、背景着色，绝不作为正文文字颜色——
 * 除同步状态色以外，其余在浅色背景上的对比度不足 4.5:1。
 */
@Immutable
data class DinnerColors(
    val starActive: Color,
    val starInactive: Color,
    val mealBreakfast: Color,
    val mealLunch: Color,
    val mealDinner: Color,
    val mealSnack: Color,
    val momentScenery: Color,
    val momentStudy: Color,
    val momentSport: Color,
    val momentCreation: Color,
    val momentOther: Color,
    val syncPending: Color,
    val syncSyncing: Color,
    val syncSynced: Color,
    val syncFailed: Color,
    val photoPlaceholder: Color,
    val onPhotoPlaceholder: Color,
    val dashedBorder: Color,
    /**
     * 强调型文字色（评论作者、次要操作按钮文字、进度指示）。
     *
     * 存在的理由：`colorScheme.primary` (#FF7A45) 放在浅色底上只有 2.6:1，
     * 直接当文字色不达 WCAG AA；这里给出同色系但压暗的版本，
     * 浅色 #8A3200 对白面约 8.3:1，深色复用 primary #FFB08C 对深面约 9.4:1。
     *
     * 注意：primary 作为**容器填色** + onPrimary 文字的成对用法（FAB、选中 chip）
     * 不受影响，那条路径本来就达标，不要拿 textAccent 去替换它。
     */
    val textAccent: Color,
    /**
     * 压在媒体（照片 / 视频封面）上的遮罩底色：播放徽标、时长角标、全屏背景。
     * 近黑，保证上面叠的白字 / 白图标始终可读。
     */
    val mediaScrim: Color,
    /** 压在 [mediaScrim] 之上的前景色（白字 / 白图标）。 */
    val onMedia: Color,
)

/**
 * 类型色在浅色面上的对比度（相对 #FFFFFF）：
 * 早餐 3.9:1 / 午餐 5.5:1 / 晚餐 5.7:1 / 加餐 4.6:1 /
 * 风景 5.9:1 / 学习 7.6:1 / 运动 5.3:1 / 创作 7.0:1 / 其他 7.7:1
 * 同步状态色同时作为文字色，均 >= 4.8:1。
 */
internal val LightDinnerColors = DinnerColors(
    starActive = Color(0xFFD97706),
    starInactive = Color(0xFFBFB8B1),
    mealBreakfast = Color(0xFFC26A00),
    mealLunch = Color(0xFF0F766E),
    mealDinner = Color(0xFF7C3AED),
    mealSnack = Color(0xFFDB2777),
    momentScenery = Color(0xFF0369A1),
    momentStudy = Color(0xFF4338CA),
    momentSport = Color(0xFF047857),
    momentCreation = Color(0xFF9A3412),
    momentOther = Color(0xFF57534E),
    syncPending = Color(0xFF6B7280),
    syncSyncing = Color(0xFF2563EB),
    syncSynced = Color(0xFF15803D),
    syncFailed = Color(0xFFB91C1C),
    photoPlaceholder = Color(0xFFF1EEEB),
    onPhotoPlaceholder = Color(0xFF8A817A),
    dashedBorder = Color(0xFF8A817A),
    // 主色压暗版：对白面 8.3:1，取代「把 primary 直接当文字色」的写法。
    textAccent = Color(0xFF8A3200),
    mediaScrim = Color(0xFF12100E),
    onMedia = Color(0xFFFFFFFF),
)

/**
 * 类型色在深色面上的对比度（相对 #1A1714）均 >= 4.7:1；
 * 同步状态色作为文字色均 >= 6.6:1。
 */
internal val DarkDinnerColors = DinnerColors(
    starActive = Color(0xFFF5B74F),
    starInactive = Color(0xFF5A544E),
    mealBreakfast = Color(0xFFF5B74F),
    mealLunch = Color(0xFF5EEAD4),
    mealDinner = Color(0xFFA78BFA),
    mealSnack = Color(0xFFF472B6),
    momentScenery = Color(0xFF7DD3FC),
    momentStudy = Color(0xFFA5B4FC),
    momentSport = Color(0xFF6EE7B7),
    momentCreation = Color(0xFFFDBA74),
    momentOther = Color(0xFFD6D3D1),
    syncPending = Color(0xFF9CA3AF),
    syncSyncing = Color(0xFF60A5FA),
    syncSynced = Color(0xFF4ADE80),
    syncFailed = Color(0xFFF87171),
    photoPlaceholder = Color(0xFF2A2521),
    onPhotoPlaceholder = Color(0xFF8A817A),
    dashedBorder = Color(0xFF8A817A),
    // 深色下 primary 本身就是高亮可读色，直接复用即可（对深面 9.4:1）。
    textAccent = Color(0xFFFFB08C),
    mediaScrim = Color(0xFF000000),
    onMedia = Color(0xFFF5F1EE),
)

val LocalDinnerColors = staticCompositionLocalOf { LightDinnerColors }
