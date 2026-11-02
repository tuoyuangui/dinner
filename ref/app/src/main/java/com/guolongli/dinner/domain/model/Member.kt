package com.guolongli.dinner.domain.model

import kotlinx.serialization.Serializable

/**
 * 一位家庭成员（饭搭子）。
 *
 * [id] 是各端自行生成、长期稳定的标识（写入 MealRecord.createdBy），
 * 不依赖网盘账号，也不参与 token 交换。
 * [avatarPath] 为网盘上的头像路径，按 id 命名，避免多人互相覆盖。
 */
@Serializable
data class Member(
    val id: String,
    val name: String,
    val avatarPath: String? = null,
    val joinedAt: Long = 0L,
)

/**
 * 家庭组状态。
 *
 * 不再假设「只有两个人」：成员是一份列表，上限 [MAX_MEMBERS]。
 * 关系只落本地与网盘快照，不涉及 token 传输。
 */
data class GroupState(
    val members: List<Member> = emptyList(),
    val myId: String = "",
    val boundAt: Long? = null,
    /** 管理员 id。为空时回退为最早加入的那位（老数据与首次建组都适用）。 */
    val adminId: String? = null,
) {
    val memberCount: Int get() = members.size

    val isFull: Boolean get() = memberCount >= MAX_MEMBERS

    /** 除我之外是否还有其他成员。 */
    val hasOthers: Boolean get() = members.any { it.id != myId }

    /**
     * 当前生效的管理员。
     *
     * 没有显式指定时按「最早加入」兜底，再兜底到自己：
     * 一个人用的时候，我就是管理员，否则界面上会出现「无人可管理」的空洞状态。
     */
    val effectiveAdminId: String
        get() = adminId?.takeIf { id -> members.any { it.id == id } }
            ?: members.minByOrNull { it.joinedAt }?.id
            ?: myId

    val admin: Member? get() = memberOf(effectiveAdminId)

    val amIAdmin: Boolean get() = effectiveAdminId == myId

    fun isAdmin(id: String): Boolean = effectiveAdminId == id

    /**
     * 能否修改这条内容。
     *
     * 规则：自己的随便改；管理员可以改所有人的（AGENTS 里「管理员可修改所有数据」）；
     * 其他人只能看。
     */
    fun canModify(authorId: String): Boolean = authorId == myId || amIAdmin

    fun memberOf(id: String): Member? = members.firstOrNull { it.id == id }

    /**
     * 展示用昵称：查不到就回退为「成员」，绝不显示裸 id。
     *
     * 成员存在但 name 是空串也必须回退：远端 members.json 可能是老版本写的，
     * 里面没有 name 字段，读出来就是空——直接显示会变成一张卡片上挂个空白。
     */
    fun nameOf(id: String): String =
        memberOf(id)?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_MEMBER_NAME

    companion object {
        /** 人数上限。超过后拒绝邀请，避免一份 records.json 无限膨胀。 */
        const val MAX_MEMBERS = 10

        const val DEFAULT_MEMBER_NAME = "成员"
    }
}
