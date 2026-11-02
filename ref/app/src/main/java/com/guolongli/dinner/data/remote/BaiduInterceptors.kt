package com.guolongli.dinner.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Token 缓存。
 *
 * OkHttp 拦截器无法调用 suspend 函数，因此由数据层在每次授权 / 刷新成功后
 * 把最新的 access_token 写入这里，拦截器同步读取。
 * 该缓存只保存 access_token，绝不保存 refresh_token。
 */
class TokenCache {
    @Volatile
    private var token: String? = null

    fun current(): String = token.orEmpty()

    fun hasToken(): Boolean = !token.isNullOrBlank()

    fun update(newToken: String) {
        token = newToken
    }

    fun clear() {
        token = null
    }
}

/**
 * 统一注入 access_token 与 User-Agent。
 *
 * - access_token 一律放查询参数，业务代码禁止散落字符串拼接。
 * - User-Agent 固定 pan.baidu.com：下载大于约 20 MB 文件时百度要求携带。
 * - 未授权时不注入空 token，让请求以「缺少凭证」的形式被服务端拒绝，
 *   而不是带着 `access_token=` 空值发出，便于错误归因。
 */
class BaiduAuthInterceptor(
    private val tokenCache: TokenCache,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.url.newBuilder()
        if (tokenCache.hasToken()) {
            builder.addQueryParameter("access_token", tokenCache.current())
        }

        val request = original.newBuilder()
            .url(builder.build())
            .header("User-Agent", com.guolongli.dinner.core.BaiduApi.USER_AGENT)
            .build()

        return chain.proceed(request)
    }
}

/**
 * 把非 2xx 响应折算成 [DinnerException]。
 *
 * **这是整条鉴权补救链路的前提，缺了它「收到鉴权错误就刷新 token」等于没接线。**
 *
 * Retrofit 在非 2xx 时抛的是 `HttpException`，不是 `DinnerException`：
 * - `BaiduFileDataSource.upload` 里 `catch (e: DinnerException.AuthRequired)`
 *   抓不到 `HttpException`，401 / 403 会被当成普通上传失败记进 FAILED；
 * - `SessionManager.onAuthError(...)` 根本不会被调用，token 永远不会刷新，
 *   用户只能看到「上传失败」，重试多少次都一样，直到手动去重新授权。
 *
 * 实测：OAuth 换 token 失败是 HTTP 400 + `{"error":"invalid_grant"}`；
 * 网盘接口正常时是 HTTP 200 带 `errno`。所以两类都要认：
 * 响应体里有 `errno` 就按 errno 归因，有 OAuth 风格 `error` 就按 error 归因。
 *
 * 注意**不能**把 4xx 以外的状态码也折成鉴权失败：5xx 是服务端故障，重试即可。
 */
class ErrorMappingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        // 读响应体要先把 buffer 复制出来，原 body 已经被这个方法消费过，
        // 直接 peek 会让下游拿不到内容。这里只在错误分支复制、且限制长度，
        // 错误响应体很小（一个 errno 或一句 error_description）。
        val body = runCatching {
            response.peekBody(MAX_ERROR_BODY_BYTES).string()
        }.getOrNull()
        val mapped = BaiduErrorMapper.mapHttpError(response.code, body)
        response.close()
        throw mapped
    }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 4L * 1024L
    }
}

/**
 * 只读拦截器：用于 OAuth 换 token 这类不需要 access_token 的请求，
 * 避免把 token 拼进不该拼接的 URL。
 */
class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", com.guolongli.dinner.core.BaiduApi.USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
