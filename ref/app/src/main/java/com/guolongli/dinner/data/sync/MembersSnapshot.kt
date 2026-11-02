package com.guolongli.dinner.data.sync

import com.guolongli.dinner.domain.model.Member
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 成员名单快照，落在 `<根目录>/group_<管理员手机号>/config/members.json`。
 *
 * 单独一个文件而不是塞进 records.json：改昵称、换头像、加人都不必重写
 * 整份记录快照，减少一次上传量（百度接口频率有限）。
 */
@Serializable
data class MembersSnapshot(
    @SerialName("schema") val schema: Int = CURRENT_SCHEMA,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("members") val members: List<MemberDto> = emptyList(),
    /**
     * 管理员 id。
     *
     * 缺省（null）时由客户端回落到「最早加入的成员」，
     * 不会出现「没有管理员」的状态。
     */
    @SerialName("admin_id") val adminId: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class MemberDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("joined_at") val joinedAt: Long = 0,
)

fun MemberDto.toDomain(): Member = Member(
    id = id,
    name = name,
    avatarPath = avatarPath,
    joinedAt = joinedAt,
)

fun Member.toDto(): MemberDto = MemberDto(
    id = id,
    name = name,
    avatarPath = avatarPath,
    joinedAt = joinedAt,
)
