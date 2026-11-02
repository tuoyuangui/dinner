package com.guolongli.dinner.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.model.BaiduCredentials
import com.guolongli.dinner.domain.repository.AuthRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 网盘授权页不可变 UI 状态。
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val appKey: String = "",
    val secretKey: String = "",
    val fullyConfigured: Boolean = false,
    val isAuthorized: Boolean = false,
    val errorMessage: String? = null,
    /** 正在打开百度授权页（SDK 的 SSO / WebView）。 */
    val launchingAuth: Boolean = false,
    /** 授权成功后自动取回云端历史的结果提示。 */
    val restoreHint: String? = null,
    /** SDK 登录态清理之类的旁路提示，不属于错误。 */
    val authorizeHint: String? = null,
) {
    val canStartAuth: Boolean get() = fullyConfigured && !launchingAuth && !isLoading
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            credentialStore.observeCredentials().collect { creds ->
                _uiState.update {
                    it.copy(
                        appKey = creds?.appKey.orEmpty(),
                        secretKey = creds?.secretKey.orEmpty(),
                        fullyConfigured = creds?.isConfigured == true,
                    )
                }
                // 凭据落盘后立刻注入内存：换 token / 刷新 / 初始化 SDK 都要用它，
                // 不注入的话「填完凭证直接点授权」会报「缺少 AppKey」。
                if (creds != null && creds.isConfigured) {
                    sessionManager.setCredentialsInMemory(creds)
                }
            }
        }
        viewModelScope.launch {
            credentialStore.observeToken().collect { token ->
                _uiState.update {
                    it.copy(isAuthorized = token != null && !token.isExpired())
                }
            }
        }
    }

    /** 保存开放平台凭证，并注入内存供换取/刷新 token 与 SDK 初始化使用。 */
    fun saveCredentials(appKey: String, secretKey: String) {
        val key = appKey.trim()
        val secret = secretKey.trim()
        if (key.isBlank() || secret.isBlank()) {
            _uiState.update { it.copy(errorMessage = "AppKey 与 SecretKey 都不能为空") }
            return
        }
        viewModelScope.launch {
            runCatchingCancellable {
                credentialStore.saveCredentials(BaiduCredentials(appKey = key, secretKey = secret))
                sessionManager.setCredentialsInMemory(BaiduCredentials(appKey = key, secretKey = secret))
            }.onSuccess {
                _uiState.update { it.copy(errorMessage = null) }
            }.onFailure {
                _uiState.update { it.copy(errorMessage = "保存凭证失败，请重试") }
            }
        }
    }

    fun setAppKey(value: String) {
        _uiState.update { it.copy(appKey = value, errorMessage = null) }
    }

    fun setSecretKey(value: String) {
        _uiState.update { it.copy(secretKey = value, errorMessage = null) }
    }

    /**
     * 用百度 OAuth SDK 发起授权。
     *
     * 授权页由 SDK 打开（装了百度 App 走 SSO，否则用 SDK 自带的 WebViewActivity），
     * 结果从宿主 Activity 的 `onActivityResult` 回到 `ActivityOauthLauncher`，
     * 这里挂起等待，拿到 code 后立刻换 token——code 10 分钟内有效，不能拖。
     *
     * 失败必须如实展示：用户取消、没有可用的百度客户端、state 不符、换 token 失败，
     * 都是不同的原因，不能统一糊成一句「授权失败」。
     */
    fun startAuth() {
        val current = _uiState.value
        if (current.launchingAuth || current.isLoading) return
        if (!current.fullyConfigured) {
            _uiState.update { it.copy(errorMessage = "请先填写并保存 AppKey 与 SecretKey") }
            return
        }
        _uiState.update { it.copy(launchingAuth = true, errorMessage = null, authorizeHint = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable { authRepository.authorize().getOrThrow() }
            _uiState.update { it.copy(launchingAuth = false) }
            result.onSuccess { code ->
                exchange(code)
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.toUserText()) }
            }
        }
    }

    /**
     * 重置本次授权会话。
     *
     * 走系统浏览器之后，百度账号的登录态由浏览器自己管，应用这边只是把
     * 挂起的等待点清掉。想换账号时在浏览器里退出百度即可。
     */
    fun clearSdkSession() {
        runCatching { authRepository.clearSdkSession() }
            .onFailure { Logger.w("重置授权会话失败：${it.message}") }
        _uiState.update {
            it.copy(authorizeHint = "已重置授权会话。想换百度账号，请在浏览器里退出后重新授权")
        }
    }

    /**
     * 授权成功后自动拉一次云端记录。
     *
     * 失败不算授权失败——授权本身已经成功，取不回历史是另一件事，
     * 所以这里只给出提示，不把 errorMessage 设成红色错误。
     */
    private suspend fun restoreHistory() {
        // restoreFromRemote 返回 Result<List<MealRecord>>：必须摊平，
        // 双层 Result 下 isFailure 恒假，取回失败会一直显示成「没有记录」。
        val result = runCatchingCancellable { syncRepository.restoreFromRemote().getOrThrow() }
        val count = result.getOrNull()?.size ?: 0
        val hint = when {
            result.isFailure -> "已授权。取回云端历史失败：${result.exceptionOrNull()?.toUserText()}"
            count > 0 -> "已从云端取回 $count 条历史记录"
            else -> "已授权。云端暂时没有可取回的记录"
        }
        _uiState.update { it.copy(restoreHint = hint) }
    }

    private fun exchange(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val tokenResult = authRepository.exchangeCode(code)
            tokenResult
                .mapCatching { token ->
                    runCatchingCancellable { sessionManager.onTokenGranted(token) }.getOrThrow()
                    token
                }
                .onSuccess { _ ->
                    _uiState.update { it.copy(isLoading = false, isAuthorized = true) }
                    // 授权完成顺手把云端历史取回来。
                    //
                    // 这里以前什么都不做：重装 / 换新设备后授权成功，时间线依旧空白，
                    // 只有去「我的 → 从云端补一段」或者重新配对才能看到旧记录——
                    // 用户的理解却是「网盘上不是有吗」，于是判定成「拉不回来」。
                    viewModelScope.launch { restoreHistory() }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = failure.toUserText())
                    }
                }
        }
    }
}

private fun MutableStateFlow<AuthUiState>.update(transform: (AuthUiState) -> AuthUiState) {
    value = transform(value)
}
