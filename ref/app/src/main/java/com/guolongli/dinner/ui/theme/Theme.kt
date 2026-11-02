package com.guolongli.dinner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * 圆角阶梯，统一由 [DinnerDimens.radiusXs] 等令牌派生，
 * 避免各处手写 CornerRadius。
 */
internal val DinnerShapes = Shapes(
    extraSmall = RoundedCornerShape(DinnerDimens.radiusXs),
    small = RoundedCornerShape(DinnerDimens.radiusSm),
    medium = RoundedCornerShape(DinnerDimens.radiusMd),
    large = RoundedCornerShape(DinnerDimens.radiusMd),
    extraLarge = RoundedCornerShape(DinnerDimens.radiusLg),
)

/**
 * 应用主题。
 *
 * - Android 12 及以上启用 Material You 动态取色。
 * - 12 以下回退到品牌暖橙方案。
 * - 深色模式使用专门调配的方案（见 Color.kt 中的对比度说明），
 *   不是对浅色做反色处理。
 * - 产品语义色（餐别 / 同步状态）通过 [LocalDinnerColors] 下发。
 *   其中 [DinnerColors.textAccent] 是「强调型文字」专用色，浅 / 深两套各自接上：
 *   浅色用主色的压暗版，深色复用 primary。任何把 `colorScheme.primary`
 *   直接当文字色的写法都应改成它（primary 当文字色在浅色底上不达 AA）。
 */
@Composable
fun DinnerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val dinnerColors = if (darkTheme) DarkDinnerColors else LightDinnerColors

    CompositionLocalProvider(LocalDinnerColors provides dinnerColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DinnerTypography,
            shapes = DinnerShapes,
            content = content,
        )
    }
}

/** 便捷访问产品语义色。 */
object DinnerTheme {
    val colors: DinnerColors
        @Composable get() = LocalDinnerColors.current
}
