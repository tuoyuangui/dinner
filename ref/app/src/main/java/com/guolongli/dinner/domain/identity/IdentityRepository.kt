package com.guolongli.dinner.domain.identity

import kotlinx.coroutines.flow.Flow

/**
 * 绑定结果，用来如实告诉用户「这次绑定到底做了什么」。
 *
 * 三件事要分开说：迁了多少条旧记录、从云端拉回多少条、有没有成功推上去。
 * 混成一句「绑定成功」会让用户在历史没回来时无从判断是没绑上还是没同步。
 */
data class IdentityBinding(
    val phoneMask: String,
    val migratedRecords: Int,
    val restoredRecords: Int,
    val pushed: Boolean,
)

/**
 * 身份（手机号）绑定。
 *
 * 为什么需要它：authorId 决定「我是谁」，写进每条记录的 createdBy。
 * 应用卸载会清掉本地数据，于是随机 identity 会换一个，用户看起来就成了新成员，
 * 历史记录还在网盘上却不再属于「我」。
 *
 * 用手机号派生 id 之后，重装、换机只要再填一次同一个号码，
 * createdBy 就对上了，「我记的」、头像、管理员身份都会回来。
 *
 * 局限必须说清：没有服务端，因此**无法验证号码归属**（发不出短信验证码）。
 * 在两人共用网盘账号的前提下，身份的可信度本来就等于「能不能授权这个账号」，
 * 手机号只是把「同一个人」这个语义固定下来，它不是安全边界。
 */
interface IdentityRepository {

    /** 已绑定的手机号掩码（138****8000）；未绑定为 null。 */
    fun observePhoneMask(): Flow<String?>

    /** 是否已经用手机号绑定身份。 */
    fun observeBound(): Flow<Boolean>

    /**
     * 绑定手机号。
     *
     * 顺序很重要：先把旧身份的记录 / 成员条目 / 头像迁到新 id，
     * 再从云端拉回属于新 id 的历史，最后推一次同步让其他设备看到新身份。
     */
    suspend fun bind(rawPhone: String): Result<IdentityBinding>
}
