package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.Member
import kotlinx.coroutines.flow.Flow

/**
 * 家庭组成员关系存储。
 *
 * 只存放成员名单与本机身份，与网盘 token 完全分开存储。
 * 成员上限由 [GroupState.MAX_MEMBERS] 约束，超限的加入请求必须被拒绝。
 */
interface PairingRepository {

    fun observe(): Flow<GroupState>

    suspend fun current(): GroupState

    /** 新增或更新一位成员（自己改昵称、同步远端名单都走这里）。满员时拒绝新增。 */
    suspend fun upsertMember(member: Member): Result<GroupState>

    /** 移除成员。只能移除自己（退出）或由本机发起的移出。 */
    suspend fun removeMember(id: String): Result<GroupState>

    /** 用远端名单覆盖本地成员列表（合并后结果，调用方负责去重与截断）。 */
    suspend fun replaceMembers(members: List<Member>)

    /**
     * 指定管理员。
     *
     * 只有当前管理员可以调用（权限判断在 ViewModel 做）；目标必须是名单里的成员。
     * 管理员身份会随 members.json 同步给所有成员。
     */
    suspend fun setAdmin(memberId: String): Result<GroupState>

    /** 合并远端管理员设置（远端优先，作为共享真相）。 */
    suspend fun adoptRemoteAdmin(memberId: String?)

    /**
     * 身份迁移：把名单里的 [fromId] 换成 [toId]。
     *
     * 用于「绑定手机号后 id 变化」：历史记录要跟着改名，名单里的自己也得改名，
     * 否则同一个人会以两个身份同时出现在成员列表里。若 [fromId] 正好是管理员，
     * 管理权一并转给 [toId]。
     */
    suspend fun migrateMemberId(fromId: String, toId: String): Result<GroupState>

    /** 解除整个组关系：清空成员名单，不影响网盘授权。 */
    suspend fun unbind()
}
