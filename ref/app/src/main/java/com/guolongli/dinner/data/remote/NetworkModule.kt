package com.guolongli.dinner.data.remote

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.api.BaiduMultimediaApi
import com.guolongli.dinner.data.remote.api.BaiduOAuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * 网络层构造。
 *
 * - access_token 注入、User-Agent、错误码归因全部收口在这里，
 *   业务代码不得自行拼接 base url 或 token 查询参数。
 * - 下载大文件需要独立 client：百度要求 User-Agent: pan.baidu.com，且超时更宽松。
 */
object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val contentType = "application/json".toMediaType()

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message ->
            // 统一走 redact，token 绝不进入日志。
            com.guolongli.dinner.core.log.Logger.d(message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

    /**
     * 网盘文件接口用 client：注入 access_token 与 User-Agent，并把非 2xx 折成 [DinnerException]。
     *
     * [ErrorMappingInterceptor] 必须挂在**最外层**（最后 `addInterceptor`）：它在
     * `chain.proceed` 之后处理响应，放在鉴权拦截器后面才能看到最终发出的请求的返回。
     * 少了它，401 / 403 会以 Retrofit 的 `HttpException` 冒到业务层，
     * `catch (e: DinnerException.AuthRequired)` 抓不到，token 永远不会被刷新。
     */
    fun panClient(tokenCache: TokenCache): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(BaiduAuthInterceptor(tokenCache))
            .addInterceptor(loggingInterceptor())
            .addInterceptor(ErrorMappingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    /** OAuth 用 client：不带 access_token，只带 User-Agent。 */
    fun oauthClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(loggingInterceptor())
            .addInterceptor(ErrorMappingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** 下载用 client：长超时，且固定 pan.baidu.com 的 User-Agent。 */
    fun downloadClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(ErrorMappingInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

    fun panRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BaiduApi.PAN_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

    fun oauthRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BaiduApi.OAUTH_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

    fun fileApi(retrofit: Retrofit): BaiduFileApi = retrofit.create(BaiduFileApi::class.java)

    fun multimediaApi(retrofit: Retrofit): BaiduMultimediaApi =
        retrofit.create(BaiduMultimediaApi::class.java)

    fun oauthApi(retrofit: Retrofit): BaiduOAuthApi = retrofit.create(BaiduOAuthApi::class.java)
}
