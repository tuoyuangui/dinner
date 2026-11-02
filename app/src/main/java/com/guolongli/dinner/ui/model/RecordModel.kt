package com.guolongli.dinner.ui.model

import androidx.compose.ui.graphics.Color
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagBlueFg
import com.guolongli.dinner.ui.theme.TagGreenBg
import com.guolongli.dinner.ui.theme.TagGreenFg
import com.guolongli.dinner.ui.theme.TagNeutralBg
import com.guolongli.dinner.ui.theme.TagNeutralFg
import com.guolongli.dinner.ui.theme.TagOrangeBg
import com.guolongli.dinner.ui.theme.TagOrangeFg
import com.guolongli.dinner.ui.theme.TagRedBg
import com.guolongli.dinner.ui.theme.TagRedFg

/**
 * 九种记录类型，各自带一套标签配色（色值来自 ui/theme/Color.kt）。
 *
 * 配色用 lambda 惰性求值而不是构造参数：颜色现在是跟随主题的 getter，
 * 构造期（枚举类加载）取值会把浅色值焊死在枚举里，切到深色也不变。
 */
enum class RecordType(
    val label: String,
    private val bg: () -> Color,
    private val fg: () -> Color,
) {
    BREAKFAST("早餐", { TagOrangeBg }, { TagOrangeFg }),
    LUNCH("午餐", { TagOrangeBg }, { TagOrangeFg }),
    DINNER("晚餐", { TagRedBg }, { TagRedFg }),
    SNACK("加餐", { TagOrangeBg }, { TagOrangeFg }),
    SCENERY("风景", { TagGreenBg }, { TagGreenFg }),
    STUDY("学习", { TagBlueBg }, { TagBlueFg }),
    SPORT("运动", { TagGreenBg }, { TagGreenFg }),
    CREATE("创作", { TagBlueBg }, { TagBlueFg }),
    OTHER("其他", { TagNeutralBg }, { TagNeutralFg });

    /** 取色放访问期：组合里读到主题状态，切深浅色自动跟着换 */
    val tagBg: Color get() = bg()
    val tagFg: Color get() = fg()
}

/** 一条记录里的媒体：照片或视频（视频有封面与时长角标）。 */
data class MediaUiModel(
    /** 本机私有目录里的文件，能直接显示；为空说明只有云端那一份 */
    val localPath: String? = null,
    val remotePath: String? = null,
    val isVideo: Boolean = false,
    val durationLabel: String? = null,
    /** 所属记录 id：点开未下载的格子时按需取回要用 */
    val recordId: String = "",
    /** 在这条记录里的序号（0..8），与云端文件名的序号一致 */
    val position: Int = 0,
)

data class CommentUiModel(
    val authorInitial: String,
    val authorColor: Color,
    val text: String,
    val id: String = "",
    /** 评论作者 id：自己的可以删，管理员解锁后可以删所有人的 */
    val authorId: String = "",
)

data class RecordUiModel(
    /** 记录 id，与云端文件名 rec-<id>.json 对齐，所以是字符串 */
    val id: String,
    val authorId: String = "",
    val authorName: String,
    val authorInitial: String,
    val authorColor: Color,
    val type: RecordType,
    /** 记录时刻：按天分组、日历定位都用它 */
    val createdAt: Long = 0L,
    val timeLabel: String,
    val note: String,
    val media: List<MediaUiModel> = emptyList(),
    val rating: Int = 0,
    val starred: Boolean = false,
    val comments: List<CommentUiModel> = emptyList(),
    /** 作者头像的本地文件；没有就用 authorInitial + authorColor 兜底 */
    val authorAvatarPath: String? = null,
    /** 同步状态文字：只有「待上传 / 失败」才显示，已同步不打扰 */
    val syncLabel: String? = null,
    /** 地点：一句人话地址（可能为空串），没有记位置时为 null */
    val place: String? = null,
    /** 「纬度, 经度」，没有位置时为 null */
    val coordinate: String? = null,
) {
    /** 卡片上那一行位置文案；没记位置就没有这一行 */
    val locationLabel: String?
        get() {
            val name = place?.takeIf { it.isNotBlank() }
            return name ?: coordinate
        }
}

data class MemberUiModel(
    val name: String,
    val initial: String,
    val color: Color,
    val isAdmin: Boolean = false,
    val joinedAt: String,
    val avatarPath: String? = null,
    /** 是不是本机这台设备的主人 */
    val isMe: Boolean = false,
    val id: String = "",
)

/** 「我的」顶部三个可点的统计 */
data class StatUiModel(
    val value: Int,
    val label: String,
    val accent: Color? = null,
    /** 点进去看哪一批记录 */
    val scope: StatScope = StatScope.ALL,
)

/**
 * 统计数字点进去之后要看的记录范围。
 * 放在模型层而不是页面里，是因为「已同步」的判定依赖仓储对同步状态的解释。
 */
enum class StatScope(val label: String) {
    ALL("全部记录"),
    MINE("我记的"),
    SYNCED("已同步"),
}
