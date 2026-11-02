package com.guolongli.dinner.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌唯一来源。
 *
 * 所有 composable 只能引用这里的令牌，禁止出现裸数字字面量。
 * 间距遵循 8dp 网格：4 / 8 / 16 / 24 / 32。
 * 正文与任何文本都不得小于 14sp（可访问性红线）。
 */
object DinnerDimens {

    // ---------- 间距（8dp 网格） ----------
    val spaceNone = 0.dp
    val spaceXxs = 4.dp
    val spaceXs = 8.dp
    val spaceSm = 16.dp
    val spaceMd = 24.dp
    val spaceLg = 32.dp
    val spaceXl = 40.dp
    val spaceXxl = 48.dp
    val spaceXxxl = 56.dp
    val spaceHuge = 64.dp

    /** 列表底部留白，保证最后一张卡片不被悬浮按钮遮挡。 */
    val spaceListBottom = 96.dp

    /** 内容区左右留白。 */
    val screenPadding = 16.dp

    // ---------- 圆角 ----------
    val radiusXs = 4.dp
    val radiusSm = 8.dp
    val radiusMd = 16.dp
    val radiusLg = 24.dp
    val radiusFull = 999.dp

    /** 卡片圆角：比按钮更圆，形成层次。 */
    val radiusCard = 20.dp

    /** 浮层（底部导航、悬浮条）圆角。 */
    val radiusFloating = 28.dp

    // ---------- 描边 ----------
    val borderThin = 1.dp
    val borderMedium = 2.dp

    // ---------- 尺寸 ----------
    /** 点击区最小边长，可访问性要求。 */
    val touchTargetMin = 48.dp
    val topBarHeight = 56.dp
    val iconSm = 18.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconXl = 32.dp
    val avatarSm = 32.dp
    val avatarMd = 40.dp
    val avatarLg = 64.dp
    val avatarXl = 88.dp
    val spinnerSm = 20.dp
    val spinnerMd = 32.dp
    val progressStroke = 2.dp

    /** 骨架屏：一条文字占位条的高度。 */
    val skeletonLine = 12.dp

    /** 骨架屏：媒体占位块的高度（4:3 大图在本机常见宽度下的取值）。 */
    val skeletonPhotoHeight = 180.dp

    /** 视频播放徽标直径，需大于 48dp 的可点击区下限。 */
    val videoPlayBadgeSize = 56.dp

    /** 悬浮按钮高度 / 底部导航栏高度。 */
    val fabHeight = 56.dp
    val bottomBarHeight = 64.dp

    /** 语义小圆点直径（餐别色 / 同步状态色）。不是间距，别拿 spaceXs 顶替。 */
    val dotSize = 8.dp

    /** 装饰圆直径：大号用于空态 / 错误态图标底与摘要卡光斑。 */
    val decorCircleLg = 64.dp

    /** 装饰圆直径：中号用于摘要卡右上角光斑与危险确认对话框的图标底。 */
    val decorCircleMd = 56.dp

    /** 时间线首屏摘要卡的最小高度。 */
    val heroMinHeight = 132.dp

    /** 评论面板列表最大高度，超出后内部滚动，避免顶掉输入框。 */
    val commentListMaxHeight = 320.dp

    // ---------- 月历 ----------
    /** 月历单元格：整格即点击区，边长不小于 44dp，配合间距达到可点标准。 */
    val calendarCell = 44.dp

    /** 月历里「这天有记录」的圆点直径。 */
    val calendarDot = 6.dp

    /** 月历顶部年月标题行高度。 */
    val calendarHeader = 48.dp

    // ---------- 列表 ----------
    /** 记录列表里的缩略图边长。 */
    val listThumb = 56.dp

    /** 摘要卡里的入口胶囊高度。 */
    val pillHeight = 32.dp

    // ---------- 字号（正文不得小于 14sp） ----------
    val fontCaption = 14.sp
    val fontBody = 16.sp
    val fontBodyLarge = 18.sp
    val fontTitleSmall = 18.sp
    val fontTitle = 20.sp
    val fontTitleLarge = 22.sp
    val fontHeadline = 28.sp
    val fontStat = 30.sp

    // ---------- 行高 ----------
    val lineHeightTight = 20.sp
    val lineHeightSnug = 24.sp
    val lineHeightBody = 26.sp
    val lineHeightRelaxed = 28.sp
    val lineHeightLoose = 34.sp
    val lineHeightHeadline = 38.sp

    // ---------- 字距 ----------
    val letterSpacingNone = 0.sp
    val letterSpacingLabel = 0.5.sp
    val letterSpacingWide = 1.sp

    /** 数字 / 标识类文本的字距（统计数字、编号），比正文松一点，读数不粘连。 */
    val letterSpacingCode = 1.5.sp

    // ---------- 阴影 / 海拔 ----------
    val elevationFlat = 0.dp
    val elevationRaised = 1.dp
    val elevationFloating = 4.dp
    val elevationModal = 8.dp

    // ---------- 比例 ----------
    /** 照片容器固定 4:3，配合 ContentScale.Crop，保证不拉伸。 */
    const val photoAspectRatio = 4f / 3f

    /**
     * 「容器跟随图片真实比例」时的上下限。
     *
     * 下限 0.55 约等于 9:16 竖屏照片，上限 1.9 约等于 16:9 横屏。
     * 超出这个范围的多半是长截图或全景图，跟着图片走会把时间线拉得七零八落。
     */
    const val mediaAspectMin = 0.55f
    const val mediaAspectMax = 1.9f

    // ---------- 动画时长（毫秒） ----------
    const val animFastMs = 150
    const val animStandardMs = 240
    const val animSlowMs = 400
    const val animShimmerMs = 900

    // ---------- 透明度 ----------
    const val alphaDisabled = 0.38f
    const val alphaSubtle = 0.10f
    const val alphaTint = 0.14f
    const val alphaScrim = 0.96f

    /** 媒体角标 / 播放按钮底色：压在照片上仍需保证前景白字可读。 */
    const val alphaOverlay = 0.72f

    /** 柔和遮罩：用于装饰圆、图标底等不希望抢视线的层次。 */
    const val alphaOverlaySoft = 0.55f

    // ---------- 业务限制 ----------
    /** 评分星数（1..5）。 */
    const val starCount = 5
    /** 卡片评论预览里单条正文最多显示的字符数，超出补省略号。 */
    const val commentPreviewChars = 40
    /** 备注最大长度。 */
    const val noteMaxLength = 300
    /** 备注输入框最小行数。 */
    const val noteMinLines = 4
    /** 卡片备注最大行数，超出省略，避免单卡过高。 */
    const val noteMaxLines = 6
    /** 卡片上评论预览最多显示几条。 */
    const val commentPreviewCount = 2
    /** 单条评论正文最多几行：预览区不是阅读区，超过就省略。 */
    const val commentTextMaxLines = 2
    /** 单人昵称最大长度（成员名单）。 */
    const val memberNameMaxLength = 16
    /** 缩放上限（照片查看器）。 */
    const val photoMaxScale = 5f
}
