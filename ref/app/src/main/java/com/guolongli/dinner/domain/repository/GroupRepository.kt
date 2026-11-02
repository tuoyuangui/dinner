package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.model.GroupIdentity
import kotlinx.coroutines.flow.Flow

/**
 * 一次建组的结果。
 *
 * 三件事必须分开说：身份变成谁、有没有推上云盘、入群密码是多少。
 * 合成一句「创建成功」会让用户在数据没同步上时无从判断卡在哪一步。
 */
data class GroupSetup(
    val identity: GroupIdentity,
    /** 本机是否已授权。未授权时群组只落本地，云端目录要等授权后才建得出来。 */
    val authorized: Boolean = false,
    /** 这次建组推上云盘了吗。false 必须如实展示，不能说「已创建」。 */
    val synced: Boolean = false,
    /** 管理员口令（调用方刚输入的明文，建组成功后立刻用它在内存里解锁）。 */
    val adminPassword: String = "",
    /** 动态入群密码：只有真的写到云端并回读确认过才有值，否则是 null。 */
    val joinPassword: String? = null,
) {
    val message: String
        get() = buildString {
            append("已创建群组（管理员 ${identity.phoneMask}）")
            if (synced) append("，passwd.json 已写入网盘") else append("，尚未同步：完成授权后会自动推送")
        }
}

/** 一次入群的结果。 */
data class GroupJoin(
    val identity: GroupIdentity,
    val members: Int,
) {
    val message: String
        get() = "已加入群组（管理员 ${identity.phoneMask}），当前共 $members 人"
}

/**
 * 群组（家庭组）身份与入群流程。
 *
 * 数据隔离靠一个约定：**所有同步路径都在 `group_<管理员手机号>/` 之下**。
 * - 建组 = 用自己的号码拼出 groupId，把路径切过去，写 passwd.json；
 * - 加入 = 填对管理员手机号（定位目录）+ 云端 `join_password`（验证身份）。
 *
 * 两道门各管一件事（见 AGENTS.md「群组门禁」）：
 * - 入群只比对云端 `passwd.json` 的 `join_password`；`admin_phone` 只用于定位目录，
 *   不绑定加入者的电话、不验管理员口令；
 * - 管理员口令只管「改 / 删别人的东西、换入群密码、转让管理权」，
 *   由管理员在应用里显式输入，通过后 `AdminSession` 在内存里记下解锁态。
 *
 * 必须如实承认的边界：没有服务端就没有真正的鉴权。云端数据在同一个网盘账号下，
 * **能不能授权这个网盘账号**才是真正的门；口令挡住的是「误操作」和
 * 「拿到手机号就想顺手改别人东西」，不是攻击者。
 */
interface GroupRepository {

    /** 当前所属群组 id（`group_<管理员手机号>`）；null 表示还没建 / 还没加入。 */
    fun observeGroupId(): Flow<String?>

    suspend fun groupId(): String?

    /** 读云端 passwd.json（不做口令校验，供展示群组信息与门禁状态）。 */
    suspend fun config(groupId: String): Result<GroupConfig?>

    /**
     * 建组：用手机号拼出 groupId，切路径，写 passwd.json（管理员口令 + 入群密码），推送一次。
     *
     * 建组者设定的管理员口令只在本机显式输入时校验；建组成功后可立刻在内存里解锁，
     * 但仅限本次运行（[com.guolongli.dinner.data.session.AdminSession] 不落盘）。
     *
     * @param rawPhone 建组者（管理员）手机号，决定 `group_<号码>/` 目录名
     * @param rawAdminPassword 建组者设定的管理员口令（明文）
     * @throws com.guolongli.dinner.core.error.DinnerException.Validation 号码或口令不合法
     */
    suspend fun createGroup(
        rawPhone: String,
        rawAdminPassword: String,
    ): Result<GroupSetup>

    /**
     * 加入群组：填对管理员手机号 + 云端动态入群密码，就写进名单、切群组、拉数据。
     *
     * 没有申请、没有审批；**只验 join_password**，不绑电话、不验管理员口令。
     *
     * @param rawPhone 自己的手机号，只作为成员 id
     * @param adminPhone 管理员手机号，用来拼出目标群组目录
     * @param joinPassword 管理员转告的动态入群密码
     */
    suspend fun joinGroup(
        rawPhone: String,
        adminPhone: String,
        joinPassword: String,
    ): Result<GroupJoin>

    /**
     * 管理员登录：显式输入管理员口令，先从云端读 passwd.json 再比对。
     *
     * 通过后 [com.guolongli.dinner.data.session.AdminSession] 在内存里解锁本次运行；
     * 失败必须抛错并保持未解锁，不得「输错了也放行」。
     */
    suspend fun unlockAdmin(groupId: String, adminPassword: String): Result<Unit>

    /** 退出管理员（只锁内存里的解锁态，不影响群组关系）。 */
    fun lockAdmin()

    /**
     * 换入群密码：本地生成 → 推云端 → 再从云端 read() 刷新。
     *
     * 只有已解锁的管理员能调用（后端会 `requireAdminUnlocked`）。
     * 返回云端读回来的新密码。
     */
    suspend fun rotateJoinPassword(groupId: String): Result<String>

    /** 当前群组在云端读到的 passwd.json，供界面显示口令卡片（默认遮明文）。 */
    suspend fun currentConfig(): Result<GroupConfig?>

    /**
     * 转让管理权。**后端强制要求本进程已验过管理员口令**，未解锁一律拒绝。
     *
     * @return 新管理员的成员 id
     */
    suspend fun transferAdmin(memberId: String): Result<String>

    /**
     * 判定这次能不能改 / 删这条内容。
     *
     * 自己的内容永远允许；别人的内容要求「名单口径上是管理员」**且**本次运行
     * 已验过管理员口令。三处界面判定（时间线 / 详情 / 编辑）都走这一个入口，
     * 免得三处各判一次、判出三个结论。
     */
    suspend fun canModifyContent(
        authorId: String,
        myId: String,
        amIAdmin: Boolean,
    ): Boolean
}
