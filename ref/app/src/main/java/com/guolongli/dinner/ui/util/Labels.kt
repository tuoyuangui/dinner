package com.guolongli.dinner.ui.util

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.SyncState

/** 记录类型中文名：餐别 + 生活场景。新增枚举值必须在此补文案。 */
fun mealLabelOf(type: MealType): String = when (type) {
    MealType.BREAKFAST -> "早餐"
    MealType.LUNCH -> "午餐"
    MealType.DINNER -> "晚餐"
    MealType.SNACK -> "加餐"
    MealType.SCENERY -> "风景"
    MealType.STUDY -> "学习"
    MealType.SPORT -> "运动"
    MealType.CREATION -> "创作"
    MealType.OTHER -> "其他"
}

/** 同步状态的中文名。UI 必须如实展示，不得假装成功。 */
fun syncLabelOf(state: SyncState): String = when (state) {
    SyncState.PENDING -> "待上传"
    SyncState.SYNCING -> "上传中"
    SyncState.SYNCED -> "已同步"
    SyncState.FAILED -> "上传失败"
}

/**
 * 把异常翻译成用户能看懂的文案。
 *
 * 绝不把原始异常文本直接抛给用户；网盘错误码保留在括号里，
 * 便于用户反馈问题时定位，但不暴露堆栈或 URL。
 */
fun Throwable.toUserText(): String = when (this) {
    is DinnerException.NetworkUnavailable -> "网络不可用，请检查连接后重试"
    is DinnerException.AuthRequired -> "网盘授权已失效，请到「我的 - 网盘授权」重新授权"
    is DinnerException.FrequencyLimited -> "请求太频繁了，稍等一会儿再试"
    is DinnerException.Remote -> "网盘出错了（$errno），请稍后重试"
    is DinnerException.Validation -> message ?: "输入有误，请检查后重试"
    is DinnerException.Local -> message ?: "本地读写失败，请重试"
    else -> "出了点问题，请稍后重试"
}
