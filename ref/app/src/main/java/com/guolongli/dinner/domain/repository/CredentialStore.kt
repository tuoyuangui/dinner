package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.BaiduCredentials
import com.guolongli.dinner.domain.model.BaiduToken
import kotlinx.coroutines.flow.Flow

/**
 * 百度 OAuth 凭证与 token 的本地保管。
 *
 * 实现必须用 Android Keystore 加密，且 token 按设备隔离，不得跨账号或跨设备复用。
 */
interface CredentialStore {
    fun observeCredentials(): Flow<BaiduCredentials?>

    suspend fun credentials(): BaiduCredentials?

    /** 写入开放平台凭证（由用户在应用内填入）。 */
    suspend fun saveCredentials(credentials: BaiduCredentials)

    fun observeToken(): Flow<BaiduToken?>

    suspend fun token(): BaiduToken?

    /**
     * 保存新 token。实现必须整体覆盖旧值——refresh_token 单次有效，
     * 刷新后旧值即作废，绝不允许保留以便复用。
     */
    suspend fun saveToken(token: BaiduToken)

    /** 清除 token，用于重新授权或解除授权。保留开放平台凭证。 */
    suspend fun clearToken()
}
