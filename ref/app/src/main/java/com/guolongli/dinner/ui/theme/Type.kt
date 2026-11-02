package com.guolongli.dinner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 字体阶梯。所有尺寸来自 [DinnerDimens]，不在此处出现裸数值。
 * 正文最小 14sp，行高偏宽松以适配中文阅读。
 */
internal val DinnerTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = DinnerDimens.fontHeadline,
        lineHeight = DinnerDimens.lineHeightHeadline,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DinnerDimens.fontTitleLarge,
        lineHeight = DinnerDimens.lineHeightRelaxed,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DinnerDimens.fontTitle,
        lineHeight = DinnerDimens.lineHeightBody,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DinnerDimens.fontTitleLarge,
        lineHeight = DinnerDimens.lineHeightRelaxed,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DinnerDimens.fontTitle,
        lineHeight = DinnerDimens.lineHeightBody,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = DinnerDimens.fontTitleSmall,
        lineHeight = DinnerDimens.lineHeightSnug,
        letterSpacing = DinnerDimens.letterSpacingLabel,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = DinnerDimens.fontBodyLarge,
        lineHeight = DinnerDimens.lineHeightRelaxed,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = DinnerDimens.fontBody,
        lineHeight = DinnerDimens.lineHeightBody,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = DinnerDimens.fontCaption,
        lineHeight = DinnerDimens.lineHeightTight,
        letterSpacing = DinnerDimens.letterSpacingNone,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = DinnerDimens.fontBody,
        lineHeight = DinnerDimens.lineHeightSnug,
        letterSpacing = DinnerDimens.letterSpacingLabel,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = DinnerDimens.fontCaption,
        lineHeight = DinnerDimens.lineHeightTight,
        letterSpacing = DinnerDimens.letterSpacingLabel,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = DinnerDimens.fontCaption,
        lineHeight = DinnerDimens.lineHeightTight,
        letterSpacing = DinnerDimens.letterSpacingWide,
    ),
)
