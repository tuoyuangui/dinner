package com.guolongli.dinner.data.session

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.remote.TokenCache
import com.guolongli.dinner.domain.model.BaiduCredentials
import com.guolongli.dinner.domain.model.BaiduToken
import com.guolongli.dinner.domain.repository.AuthRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权会话管理：统一决定「何时用哪个 token」。
 *
 * 核心规则（见 AGENTS.md 红线）：
 * - access_token 过期前用 refresh_token 刷新。
 * - 刷新成功后必须用新 refresh_token 覆盖旧值。
 * - 刷新失败必须引导重新授权，不得用旧值循环重试。
 * - 收到鉴权错误立即停用当前 token，先刷新或重新授权。
 *
 * 这是唯一允许写入 [TokenCache] 的地方，保证拦截器里看到的永远是可用 token。
 */
@Singleton
class SessionManager @Inject constructor(
    private val credentialStore: CredentialStore,
    private val authRepository: AuthRepository,
    private val tokenCache: TokenCache,
) {

    private val refreshMutex = Mutex()

    /** 最近一次授权失败的原因，UI 可据此引导用户。 */
    @Volatile
    var lastAuthError: String? = null
        private set

    /**
     * 确保 [TokenCache] 里有一个尽可能新的 access_token。
     * 返回是否可用。
     */
    suspend fun ensureFreshToken(): Boolean = refreshMutex.withLock {
        val credentials = credentialStore.credentials()
        if (credentials == null || !credentials.isConfigured) {
            Logger.i("未配置开放平台凭证")
            tokenCache.clear()
            return@withLock false
        }

        val token = credentialStore.token()
        if (token == null) {
            Logger.i("尚未授权")
            tokenCache.clear()
            return@withLock false
        }

        if (!token.needsRefresh()) {
            tokenCache.update(token.accessToken)
            return@withLock true
        }

        Logger.i("access_token 即将过期，尝试刷新")
        return@withLock doRefresh(credentials, token)
    }

    /**
     * 强制刷新。用于收到鉴权错误后的补救。
     * 刷新失败即清除 token，要求重新授权，绝不重复使用旧值。
     */
    suspend fun forceRefresh(): Boolean = refreshMutex.withLock {
        val credentials = credentialStore.credentials() ?: return@withLock false
        val token = credentialStore.token() ?: return@withLock false
        doRefresh(credentials, token)
    }

    private suspend fun doRefresh(credentials: BaiduCredentials, token: BaiduToken): Boolean {
        authRepository.setCredentials(credentials.appKey, credentials.secretKey)
        val result = authRepository.refresh(token.refreshToken)

        return result.fold(
            onSuccess = { newToken ->
                // 整体覆盖：旧 refresh_token 单次有效，刷新后立即作废。
                credentialStore.saveToken(newToken)
                tokenCache.update(newToken.accessToken)
                lastAuthError = null
                Logger.i("token 刷新成功")
                true
            },
            onFailure = { error ->
                // 刷新失败必须重新授权：清空 token，避免误用旧值。
                credentialStore.clearToken()
                tokenCache.clear()
                lastAuthError = error.message ?: "刷新失败，请重新授权"
                Logger.w("token 刷新失败，已清除本地 token，需要重新授权")
                false
            },
        )
    }

    /**
     * 处理一次业务请求中出现的鉴权错误。
     * 先尝试刷新，成功则返回 true 供调用方重试一次；失败则要求重新授权。
     */
    suspend fun onAuthError(error: DinnerException.AuthRequired): Boolean {
        Logger.w("收到鉴权错误：${error.message}")
        lastAuthError = error.message
        return forceRefresh()
    }

    /** 记录一次新的授权结果，并同步到拦截器缓存。 */
    suspend fun onTokenGranted(token: BaiduToken) {
        credentialStore.saveToken(token)
        tokenCache.update(token.accessToken)
        lastAuthError = null
        Logger.i("授权成功")
    }

    /** 解除授权：先取消同步（由调用方保证），再清空 token，保留开放平台凭证。 */
    suspend fun signOut() {
        credentialStore.clearToken()
        tokenCache.clear()
        lastAuthError = null
        Logger.i("已解除授权")
    }

    /** 当前是否已授权且 token 未过期。 */
    suspend fun isAuthorized(): Boolean {
        val token = credentialStore.token() ?: return false
        return !token.isExpired()
    }

    fun setCredentialsInMemory(credentials: BaiduCredentials) {
        authRepository.setCredentials(credentials.appKey, credentials.secretKey)
    }
}
