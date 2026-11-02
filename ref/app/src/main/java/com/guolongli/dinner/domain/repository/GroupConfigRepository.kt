package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.GroupConfig

/**
 * 群组门禁文件（`config/passwd.json`）的读写。
 *
 * 这个文件的全部意义是「谁能改别人的东西、谁能加人」：
 * - 管理员口令 [com.guolongli.dinner.domain.model.GroupConfig.adminPassword]
 *   由管理员显式输入后校验，通过才允许改 / 删别人的记录、改入群密码、转让管理权；
 * - 动态入群密码 [com.guolongli.dinner.domain.model.GroupConfig.joinPassword]
 *   是加人的唯一入口。
 *
 * 两条硬约束（AGENTS.md 群组门禁）：
 * 1. 每次读口令都必须**先读云端**，不得拿本机缓存的值当裁决依据；
 * 2. 每次写都必须**写后回读比对**，不一致就抛错，不许把「本地以为写成功」当成成功。
 *
 * 口令明文存储、禁改哈希：共享网盘目录里成员本来就能读到这个文件，
 * 哈希不会带来真实的边界，只会让「输对了吗」这件事变得难解释。
 */
interface GroupConfigRepository {

    /**
     * 读某个群的 passwd.json。
     *
     * 返回 null 表示云端确实没有这个文件（还没建组）；读失败必须抛
     * [com.guolongli.dinner.core.error.DinnerException]，不得压成 null——
     * 「文件在网盘里看得见、应用说没有」会让人完全无从下手。
     */
    suspend fun read(groupId: String): GroupConfig?

    /** 写口令文件，并在写后回读确认云端内容与本次一致。失败抛错。 */
    suspend fun write(config: GroupConfig): Result<Unit>

    /**
     * 校验入群密码：先从云端读最新口令，再比对 [input]。
     *
     * 只比 join_password，不绑电话、不验管理员口令。
     * 读不到口令文件时返回失败，不得当成「密码不对」糊过去。
     */
    suspend fun verifyJoinPassword(groupId: String, input: String): Result<Unit>

    /**
     * 换入群密码：本地生成 → 推云端 → 再从云端 read() 回读刷新。
     *
     * 返回**云端读回来的**新密码，调用方必须显示这个值，不得显示本地生成的那个
     * （两者不一致时说明云端没写进去，界面上显示本地值就是撒谎）。
     */
    suspend fun rotateJoinPassword(groupId: String): Result<String>

    /** 只改管理员口令（建组时设置，或管理员在解锁后主动重设），写后回读确认。 */
    suspend fun updateAdminPassword(groupId: String, newPassword: String): Result<GroupConfig>
}
