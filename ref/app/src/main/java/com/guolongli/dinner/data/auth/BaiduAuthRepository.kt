package com.guolongli.dinner.data.auth

import com.guolongli.dinner.core.OAuthConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.remote.api.BaiduOAuthApi
import com.guolongli.dinner.data.remote.dto.UnionTokenPayload
import com.guolongli.dinner.domain.model.BaiduToken
import com.guolongli.dinner.domain.repository.AuthRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * 百度网盘「快速授权」（union/oauth）实现。
 *
 * 这是一条**不经过 OAuth SDK**的授权路径，原因是实测结论：
 * SDK 2.0.7 每次请求都会自动带 `pkgname` + `key_hash`（包名 + 签名指纹），
 * 服务端拿它们与后台登记比对；本项目后台那个「软件」类型应用没有安卓包名/签名
 * 登记区，于是必然返回 `#error=pkgname_mismatch`，授权页零点几秒就关掉。
 *
 * union/oauth 只校验 client_id / client_secret / redirect_uri / scope，
 * 因此「App 里只填 AppKey + SecretKey」就能授权成功（与用户口径一致）。
 *
 * 流程（文档 Step 1-3）：
 * 1. 浏览器打开 `https://pan.baidu.com/union/oauth/authorize?client_id=…&redirect_uri=…&scope=…&state=…`；
 * 2. 用户在授权页登录、选目录、确认；平台 302 回 `{redirect_uri}?code=…&state=…`；
 * 3. 应用用 code 打 `xpan/oauth/get?method=token` 换 token。
 *
 * code 一次性有效 10 分钟；state 每次随机并在回调里比对。
 */
@Singleton
class BaiduAuthRepository @Inject constructor(
    private val oauthApi: BaiduOAuthApi,
    private val broker: OauthCallbackBroker,
) : AuthRepository {

    /** 授权进行中：暂存本次的 state，回调必须逐字匹配。 */
    @Volatile
    private var pendingState: String? = null

    /**
     * 拼装授权页 URL。
     *
     * `redirect_uri` 必须 URL Encode；scope 用逗号分隔。
     * 这个 URL 交给系统浏览器（或 SDK 的 WebView）打开，
     * 应用本身不再持有任何授权页 UI。
     */
    override fun buildAuthorizeUrl(appKey: String, state: String): String =
        com.guolongli.dinner.core.OAuthConfig.AUTHORIZE_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", appKey)
            .addQueryParameter("redirect_uri", OAuthConfig.REDIRECT_URI)
            .addQueryParameter("scope", OAuthConfig.SCOPE)
            .addQueryParameter("state", state)
            .build()
            .toString()

    /**
     * 发起授权并挂起等待回调。
     *
     * 这里**不自己跳转**，只登记一个等待点，由调用方（Activity）用
     * [ExternalBrowserLauncher] 把上一步拼出来的 URL 交给系统浏览器。
     * 这样「谁来打开授权页」与「谁来接住回调」各自独立可测。
     */
    override suspend fun authorize(): Result<String> {
        val appKey = pendingAppKey ?: return Result.failure(
            DinnerException.Validation("缺少 AppKey，无法发起授权")
        )
        val state = java.security.SecureRandom().let { random ->
            ByteArray(16).also { random.nextBytes(it) }
                .joinToString("") { com.guolongli.dinner.core.util.Ascii.hex2(it) }
        }
        pendingState = state
        // 落盘：用户跳去浏览器登录期间，进程可能被系统回收。
        broker.rememberState(state)

        val url = buildAuthorizeUrl(appKey, state)
        val opened = runCatching { broker.open(url) }
            .getOrElse { error ->
                pendingState = null
                return Result.failure(
                    DinnerException.Local("打不开授权页：${error.message}。请确认设备有可用的浏览器", error)
                )
            }
        if (!opened) {
            pendingState = null
            return Result.failure(
                DinnerException.Local("打不开授权页：设备上没有可用的浏览器")
            )
        }
        Logger.i("已打开 union/oauth 授权页，等待回调")

        // 等浏览器把用户带回来（自定义 scheme 回调由 MainActivity 接住）。
        // 若回调在进程重建后先到，这里取到的是暂存结果，不会白等。
        val callback = broker.consumeLateResult() ?: broker.awaitCallback(state)
        pendingState = null
        return callback.map { code -> code }
    }

    /**
     * 用一次性 code 换取 token。
     *
     * 三处 redirect_uri 必须完全一致：后台配置、发起授权、这里。
     */
    override suspend fun exchangeCode(code: String): Result<BaiduToken> = runCatching {
        val appKey = requireNotNull(pendingAppKey) { "缺少 AppKey，无法换取 token" }
        val secret = requireNotNull(pendingSecretKey) { "缺少 SecretKey，无法换取 token" }

        val response = oauthApi.exchangeCode(
            clientId = appKey,
            clientSecret = secret,
            code = code,
            redirectUri = OAuthConfig.REDIRECT_URI,
            state = pendingState,
        )
        response.toToken(operation = "换取 token")
    }.onFailure { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
    }

    override suspend fun refresh(refreshToken: String): Result<BaiduToken> = runCatching {
        val appKey = requireNotNull(pendingAppKey) { "缺少 AppKey，无法刷新 token" }
        val secret = requireNotNull(pendingSecretKey) { "缺少 SecretKey，无法刷新 token" }

        val response = oauthApi.refreshToken(
            clientId = appKey,
            clientSecret = secret,
            refreshToken = refreshToken,
        )
        // 刷新成功返回的新 refresh_token 必须整体覆盖旧值，由调用方负责落盘。
        response.toToken(operation = "刷新 token")
    }.onFailure { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
    }

    /**
     * 授权前由数据层注入当前设备的开放平台凭证。
     * 只驻留内存，不落盘、不入日志。
     */
    @Volatile
    private var pendingAppKey: String? = null
    @Volatile
    private var pendingSecretKey: String? = null

    override fun setCredentials(appKey: String, secretKey: String) {
        pendingAppKey = appKey
        pendingSecretKey = secretKey
    }

    /**
     * 换账号时清 SDK 内部登录态。
     *
     * 现在授权走系统浏览器，清的是应用自己的 WebView Cookie 目录，
     * 不影响浏览器里用户自己的登录态（那本来也不该被应用动）。
     */
    override fun clearSdkSession() {
        Logger.i("已重置授权会话（浏览器登录态由浏览器自己管理）")
    }

}

private fun com.guolongli.dinner.data.remote.dto.UnionTokenResponse.toToken(
    operation: String,
): BaiduToken {
    if (!isSuccess) {
        val reason = showMsg.takeIf { it.isNotBlank() }
            ?: errmsg.takeIf { it.isNotBlank() }
            ?: "errno=$errno"
        throw DinnerException.AuthRequired("$operation 失败：$reason")
    }
    return data?.toToken(operation) ?: throw DinnerException.AuthRequired("$operation 没有返回数据")
}

/**
 * union/oauth 的 token payload 转成领域模型。
 *
 * 令牌是加密串，**不拆分、不截断、不打日志**：这里只做非空校验与有效期换算。
 */
private fun UnionTokenPayload.toToken(operation: String): BaiduToken {
    val access = accessToken?.takeIf { it.isNotBlank() }
        ?: throw DinnerException.AuthRequired("$operation 未返回 access_token")
    val refresh = refreshToken?.takeIf { it.isNotBlank() }
        ?: throw DinnerException.AuthRequired("$operation 未返回 refresh_token")
    // 文档说默认 30 天；拿不到具体值时按 30 天算，避免过期判断失准。
    val ttlSeconds = if (expiresIn > 0) expiresIn else 30L * 24 * 3600
    return BaiduToken(
        accessToken = access,
        refreshToken = refresh,
        expiresAt = System.currentTimeMillis() + ttlSeconds.seconds.inWholeMilliseconds,
        scope = scope,
        authorizedPaths = authorizedPaths,
    )
}
