package com.guolongli.dinner.domain.model

/**
 * 记录类型。
 *
 * 前四项是餐别（BREAKFAST / LUNCH / DINNER / SNACK），后五项是生活场景
 * （SCENERY 风景 / STUDY 学习 / SPORT 运动 / CREATION 创作 / OTHER 其他），
 * 让「记一顿」不只装得下吃饭这一件事。
 *
 * 持久化时存 name()，因此新增值只能追加在末尾，不得插入或改名——
 * 旧数据靠 name 反查，插入会让历史记录串味。
 */
enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    SCENERY,
    STUDY,
    SPORT,
    CREATION,
    OTHER,
    ;

    /** 是否为餐别。用于文案（「这一顿是」/「这一刻是」）与卡片分区。 */
    val isMeal: Boolean
        get() = when (this) {
            BREAKFAST, LUNCH, DINNER, SNACK -> true
            SCENERY, STUDY, SPORT, CREATION, OTHER -> false
        }

    companion object {
        val DEFAULT: MealType = DINNER

        fun fromStorage(raw: String?): MealType =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
