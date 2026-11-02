package com.guolongli.dinner.domain.model

/** 创建群组的结果。[groupId] = `group_<管理员手机号>`。 */
data class GroupIdentity(
    val groupId: String,
    val phoneMask: String,
)
