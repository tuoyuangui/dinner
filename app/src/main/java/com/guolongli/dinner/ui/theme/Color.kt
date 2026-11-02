package com.guolongli.dinner.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * 色板：浅色取自画布节点 fills / strokes 的十六进制值；深色按同一语义映射一套暗色值。
 * 不要在页面里写裸色值，一律从这里引用。
 *
 * 主题切换机制：每个颜色都是「读 [DinnerThemeState.isDark] 的 getter」。
 * 组合期（Composable 里）读到它就订阅了这个状态——切主题整棵界面树自动重绘；
 * 数据层（Flow 映射、枚举）里读它拿到的是求值当时的颜色，也能正常工作。
 */

/** 当前是否深色。由 DinnerTheme 按设置写入，界面从这里取色 */
object DinnerThemeState {
    val isDark = mutableStateOf(false)
}

private fun pick(light: Color, dark: Color): Color =
    if (DinnerThemeState.isDark.value) dark else light

// —— 品牌 & 底色 ——
val BrandBlue: Color get() = pick(Color(0xFF0052D9), Color(0xFF4D8DFF))
val PageBackground: Color get() = pick(Color(0xFFEEEEEE), Color(0xFF121212))
val SurfaceWhite: Color get() = pick(Color(0xFFFFFFFF), Color(0xFF1E1E1E))
val SurfaceMuted: Color get() = pick(Color(0xFFF3F3F3), Color(0xFF2A2A2A))   // 筛选胶囊底、导航胶囊底、输入框底
val SurfaceInset: Color get() = pick(Color(0xFFF2F3FF), Color(0xFF1A2440))   // 品牌色浅底（角色标签、次级按钮）

// —— 文本 ——
val TextPrimary: Color get() = pick(Color(0xFF1A1A1A), Color(0xFFF2F2F2))
val TextSecondary: Color get() = pick(Color(0xFF666666), Color(0xFFB3B3B3))
val TextTertiary: Color get() = pick(Color(0xFF999999), Color(0xFF8A8A8A))
val TextDisabled: Color get() = pick(Color(0xFFBDBDBD), Color(0xFF5C5C5C))   // 列表右箭头

// —— 分隔与描边 ——
val DividerColor: Color get() = pick(Color(0xFFEEEEEE), Color(0xFF2A2A2A))
val BorderColor: Color get() = pick(Color(0xFFE8E8E8), Color(0xFF333333))    // 卡片描边
val BorderStrongColor: Color get() = pick(Color(0xFFDDDDDD), Color(0xFF444444)) // 胶囊/圆形按钮描边、未点亮星星
val OnBrand = Color(0xFFFFFFFF)

// —— 记录类型标签（九种类型归到四套语义色）——
val TagRedBg: Color get() = pick(Color(0xFFFFF0ED), Color(0xFF3A221F))
val TagRedFg: Color get() = pick(Color(0xFFD54941), Color(0xFFFF8D85))
val TagOrangeBg: Color get() = pick(Color(0xFFFFF1E9), Color(0xFF3A2A1C))
val TagOrangeFg: Color get() = pick(Color(0xFFE37318), Color(0xFFFFB27A))
val TagGreenBg: Color get() = pick(Color(0xFFE3F9E9), Color(0xFF16301F))
val TagGreenFg: Color get() = pick(Color(0xFF2BA471), Color(0xFF5FD99F))
val TagBlueBg: Color get() = pick(Color(0xFFF2F3FF), Color(0xFF1A2440))
val TagBlueFg: Color get() = pick(Color(0xFF0052D9), Color(0xFF7FA8FF))
val TagNeutralBg: Color get() = pick(Color(0xFFF3F3F3), Color(0xFF2A2A2A))
val TagNeutralFg: Color get() = pick(Color(0xFF666666), Color(0xFFB3B3B3))

// —— 成员头像底色（两套主题共用：饱和色在深浅底上都成立）——
val AvatarBlue = Color(0xFF0052D9)
val AvatarOrange = Color(0xFFE37318)
val AvatarGreen = Color(0xFF2BA471)

// —— 评分 ——
val StarActive = Color(0xFFE37318)
val StarInactive: Color get() = pick(Color(0xFFDDDDDD), Color(0xFF444444))

// —— 开关 ——
val SwitchOnTrack: Color get() = BrandBlue
val SwitchOffTrack: Color get() = pick(Color(0xFFC6C6C6), Color(0xFF4A4A4A))
val SwitchThumb: Color get() = pick(Color(0xFFFFFFFF), Color(0xFFE0E0E0))

// —— 媒体占位（真实图片接入前的灰度底）——
val MediaPlaceholderTop: Color get() = pick(Color(0xFFEDEDED), Color(0xFF2A2A2A))
val MediaPlaceholderBottom: Color get() = pick(Color(0xFFDCDCDC), Color(0xFF222222))
val MediaGlyph = Color(0xFFFFFFFF)
val BadgeScrim = Color(0x80000000)
