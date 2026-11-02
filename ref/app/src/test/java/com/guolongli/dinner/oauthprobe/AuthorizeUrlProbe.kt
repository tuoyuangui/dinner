package com.guolongli.dinner.oauthprobe

import com.guolongli.dinner.core.OAuthConfig
import com.guolongli.dinner.data.remote.api.BaiduOAuthApi
import com.guolongli.dinner.data.remote.dto.UnionTokenPayload
import com.guolongli.dinner.data.remote.dto.UnionTokenResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权配置探针（不是回归用例）：把「SDK 初始化用到的参数」与「换 token 用到的参数」
 * 在同一个地方对齐，避免它们各自漂移。
 *
 * 为什么需要它：授权页由百度 OAuth SDK 承载，应用不再自己拼授权 URL，
 * 于是 `redirect_uri` / `scope` 变成两处消费——SDK 的 `AuthInfo` 与换 token 的请求。
 * 这两处一旦不一致，表现是「授权页能打开、登录也成功，但换 token 时报
 * redirect_uri 不匹配」，排查起来完全看不出问题在哪。
 *
 * 这个探针不碰网络：只断言常量关系，并把当前授权配置打出来供人工核对
 * （百度开放平台后台必须登记同一个 redirect_uri）。
 */
class AuthorizeUrlProbe {

    private class StubOAuthApi : BaiduOAuthApi {
        override suspend fun exchangeCode(
            method: String,
            clientId: String,
            clientSecret: String,
            code: String,
            redirectUri: String,
            state: String?,
        ): UnionTokenResponse = error("probe 不换 token")

        override suspend fun refreshToken(
            method: String,
            clientId: String,
            clientSecret: String,
            refreshToken: String,
        ): UnionTokenResponse = error("probe 不刷新 token")
    }

    @Test
    fun `SDK 与换 token 用的是同一套授权参数`() {
        // SDK 初始化（AuthInfo）读的是 redirect_uri，换 token 读的是同一个常量。
        // 走 union/oauth 后回调是自定义 scheme，由 MainActivity 接住。
        assertEquals("dinner://oauth/callback", OAuthConfig.REDIRECT_URI)
        assertEquals("dinner", OAuthConfig.REDIRECT_SCHEME)
        assertEquals("oauth", OAuthConfig.REDIRECT_HOST)

        // union/oauth 的 scope 是目录级读写的两项；老的 basic,netdisk 不适用。
        val scopes = OAuthConfig.SCOPE.split(',').map { it.trim() }
        assertTrue(scopes.contains("nd_open_read"))
        assertTrue(scopes.contains("nd_open_write"))

        // 授权页与换 token 都在 pan.baidu.com 下（不是 openapi）。
        assertTrue(OAuthConfig.AUTHORIZE_ENDPOINT.startsWith("https://pan.baidu.com/union/oauth/"))
        assertTrue(OAuthConfig.TOKEN_ENDPOINT.startsWith("https://pan.baidu.com/xpan/oauth/"))
    }

    @Test
    fun `换 token 的 DTO 能解析成功响应`() {
        val ok = UnionTokenResponse(
            errno = 0,
            data = UnionTokenPayload(
                accessToken = "xpan-token",
                refreshToken = "xpan-refresh",
                expiresIn = 3600,
                scope = "nd_open_read,nd_open_write",
                authorizedPaths = listOf("/apps/dinner/group_13800138000"),
            ),
        )
        assertTrue(ok.isSuccess)
        assertEquals(3600L, ok.data?.expiresIn)
        // 缺 data / 缺 token 都不能算成功，否则会在别处抛「未返回 access_token」。
        assertTrue(!UnionTokenResponse(errno = 0, data = null).isSuccess)
        assertTrue(!UnionTokenResponse(errno = 2, data = UnionTokenPayload(accessToken = "x")).isSuccess)
    }

    @Test
    fun `Stub 实现与真实 OAuth 接口签名一致`() {
        // 这里的价值是**编译期**的：StubOAuthApi 实现 BaiduOAuthApi，
        // 接口一旦增删方法或改参数名，这个文件会编译失败，
        // 提醒去更新探针，而不是运行时静默跳过。
        // 运行期只断言换 token / 刷新的两个方法名仍然存在，避免接口被改名后无人察觉。
        val methods = BaiduOAuthApi::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(methods.contains("exchangeCode"))
        assertTrue(methods.contains("refreshToken"))
    }
}
