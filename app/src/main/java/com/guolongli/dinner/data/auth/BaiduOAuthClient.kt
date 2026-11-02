package com.guolongli.dinner.data.auth

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** 一次授权拿到的凭据 */
data class TokenInfo(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val scope: String,
)

/**
 * 百度 OAuth 2.0（授权码模式）。
 * 授权页 → 用户登录同意 → 拿到 code → 换 token；token 快过期时用 refresh_token 续。
 */
class BaiduOAuthClient(
    private val client: OkHttpClient = NetdiskApi.defaultClient(),
) {

    /** 拼授权页地址；scope 用 basic,netdisk 才能读写网盘 */
    fun authorizeUrl(appKey: String, redirectUri: String, scope: String = BaiduConfig.SCOPE): String =
        buildString {
            append(BaiduConfig.AUTHORIZE_URL)
            append("?response_type=code")
            append("&client_id=").append(appKey.urlEncoded())
            append("&redirect_uri=").append(redirectUri.urlEncoded())
            append("&scope=").append(scope.urlEncoded())
            append("&display=mobile")
        }

    suspend fun exchangeCode(
        appKey: String,
        appSecret: String,
        code: String,
        redirectUri: String,
    ): TokenInfo = requestToken(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to appKey,
            "client_secret" to appSecret,
            "redirect_uri" to redirectUri,
        ),
    )

    suspend fun refresh(
        appKey: String,
        appSecret: String,
        refreshToken: String,
    ): TokenInfo = requestToken(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to appKey,
            "client_secret" to appSecret,
        ),
    )

    private suspend fun requestToken(params: Map<String, String>): TokenInfo {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${v.urlEncoded()}" }
        val request = Request.Builder()
            .url("${BaiduConfig.TOKEN_URL}?$query")
            .header("User-Agent", "pan.baidu.com")
            .get()
            .build()

        val body = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }
        if (body.isBlank()) throw NetdiskException(-1, "换取 token 失败：响应为空")

        val json = JSONObject(body)
        val error = json.optString("error")
        if (error.isNotBlank()) {
            // invalid_grant / invalid_client 之类，直接把百度的描述抛出来
            val desc = json.optString("error_description").ifBlank { error }
            throw NetdiskException(json.optInt("error_code", -6), desc)
        }

        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) throw NetdiskException(-6, "换取 token 失败：没有 access_token")
        return TokenInfo(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token"),
            expiresInSeconds = json.optLong("expires_in", 2_592_000L),
            scope = json.optString("scope"),
        )
    }
}

internal fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")
