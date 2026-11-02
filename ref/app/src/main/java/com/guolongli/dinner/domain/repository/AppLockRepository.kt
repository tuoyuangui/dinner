package com.guolongli.dinner.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * 「打开应用时验证身份」开关。
 *
 * 只存一个布尔值，与 token、成员关系分开：这个开关不涉及任何密钥，
 * 真正的验证交给系统生物识别 / 锁屏凭据，应用自己不保存任何口令。
 */
interface AppLockRepository {

    fun observeEnabled(): Flow<Boolean>

    suspend fun enabled(): Boolean

    suspend fun setEnabled(on: Boolean)
}
