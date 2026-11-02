package com.guolongli.dinner.data.auth

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 设备码：给用户看的一串短码 + 一个确认页地址 */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val qrCodeUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/**
 * 设备码（device code）授权。
 *
 * 比「授权页 + 回调拿 code」更适合手机：应用只显示一串 8 位短码和一个网址，
 * 用户在任意设备上打开网址、输入短码、点同意，应用这边轮询就能拿到 token，
 * 不用在两个页面之间复制那串 32 位的授权码。
 *
 * 百度的轮询有个怪癖：**未授权时返回 200 + 空响应体**，不是标准的 authorization_pending，
 * 所以「空 body」也按「继续等」处理。
 */
class DeviceCodeClient(
    private val client: OkHttpClient = NetdiskApi.defaultClient(),
) {

    suspend fun request(appKey: String, scope: String = BaiduConfig.SCOPE): DeviceCode {
        val url = "${BaiduConfig.DEVICE_CODE_URL}?response_type=device_code" +
            "&client_id=" + appKey.urlEncoded() +
            "&scope=" + scope.urlEncoded()
        val body = get(url)
        val json = parse(body)
        val deviceCode = json.optString("device_code")
        if (deviceCode.isBlank()) throw NetdiskException(-1, "没有拿到 device_code")
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = json.optString("user_code"),
            verificationUrl = json.optString("verification_url").ifBlank { "https://openapi.baidu.com/device" },
            qrCodeUrl = json.optString("qrcode_url"),
            expiresInSeconds = json.optLong("expires_in", 300L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(2L),
        )
    }

    /**
     * 轮询一次。还没授权返回 null；授权成功返回 token；过期 / 拒绝直接抛。
     */
    suspend fun tryToken(appKey: String, appSecret: String, deviceCode: String): TokenInfo? {
        val url = "${BaiduConfig.DEVICE_TOKEN_URL}?grant_type=device_token" +
            "&code=" + deviceCode.urlEncoded() +
            "&client_id=" + appKey.urlEncoded() +
            "&client_secret=" + appSecret.urlEncoded()
        val body = get(url)
        if (body.isBlank()) return null // 未授权时百度返回空 body

        val json = JSONObject(body)
        val token = json.optString("access_token")
        if (token.isNotBlank()) {
            return TokenInfo(
                accessToken = token,
                refreshToken = json.optString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 2_592_000L),
                scope = json.optString("scope"),
            )
        }

        val error = json.optString("error")
        return when (error) {
            "", "authorization_pending", "authorization_waiting", "slow_down" -> null
            "expired_token" -> throw NetdiskException(-6, "设备码已过期，请重新获取")
            "access_denied" -> throw NetdiskException(-6, "已取消授权")
            else -> throw NetdiskException(
                json.optInt("error_code", -6),
                json.optString("error_description").ifBlank { error },
            )
        }
    }

    /**
     * 一直等到用户授权完成，最长等 [timeoutMs]。
     * 超过设备码有效期就抛 -6，让界面重新取一个。
     */
    suspend fun awaitToken(
        appKey: String,
        appSecret: String,
        deviceCode: DeviceCode,
        timeoutMs: Long = deviceCode.expiresInSeconds * 1000L,
        onWait: (elapsedMs: Long) -> Unit = {},
    ): TokenInfo {
        val started = System.currentTimeMillis()
        while (true) {
            tryToken(appKey, appSecret, deviceCode.deviceCode)?.let { return it }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > timeoutMs) throw NetdiskException(-6, "等待授权超时，请重新获取设备码")
            onWait(elapsed)
            delay(deviceCode.intervalSeconds * 1000L)
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetdiskApi.USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }

    private fun parse(body: String): JSONObject {
        if (body.isBlank()) throw NetdiskException(-1, "设备码接口返回为空")
        val json = JSONObject(body)
        val error = json.optString("error")
        if (error.isNotBlank()) {
            throw NetdiskException(
                json.optInt("error_code", -6),
                json.optString("error_description").ifBlank { error },
            )
        }
        return json
    }
}
