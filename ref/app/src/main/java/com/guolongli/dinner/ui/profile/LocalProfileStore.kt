package com.guolongli.dinner.ui.profile

import kotlinx.coroutines.flow.Flow

/**
 * 本地身份与昵称。
 *
 * 纯本地数据，不涉及网盘、不涉及 token。
 *
 * - [observeAuthorId] 返回本机稳定不变的标识，写入 `MealRecord.createdBy`，
 *   也是成员列表里「我」的主键。绑定手机号后它由号码派生（见
 *   [com.guolongli.dinner.core.identity.PhoneIdentity]），因此重装、换机
 *   只要重新填入同一个号码，就能拿回同一身份；未绑定时退回设备级随机标识。
 * - 其他成员的昵称不再从这里读：成员是一份列表，见
 *   [com.guolongli.dinner.domain.model.GroupState]。
 */
interface LocalProfileStore {

    /** 本机作者标识，写入 MealRecord.createdBy。 */
    fun observeAuthorId(): Flow<String>

    /** 自己的昵称，用于卡片作者名与头像首字母。 */
    fun observeMyName(): Flow<String>

    suspend fun saveMyName(name: String)

    /** 已绑定的手机号（归一化后的明文，只存本机）；未绑定为 null。 */
    fun observePhone(): Flow<String?>

    /**
     * 绑定手机号：把作者标识切换成由号码派生的稳定 id。
     *
     * @return 切换前的旧 id；若本来就是这个号码，返回 null（无需迁移）。
     */
    suspend fun bindPhone(normalizedPhone: String): String?

    /**
     * 已退役的旧 id。
     *
     * 身份迁移后，远端 members.json 里可能还留着旧 id，其他设备也可能仍在
     * 引用它。合并远端名单时把这些 id 过滤掉，才不会出现「同一个人两条记录」。
     */
    fun observeRetiredIds(): Flow<Set<String>>
}
