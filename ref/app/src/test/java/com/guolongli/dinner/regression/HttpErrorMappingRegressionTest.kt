package com.guolongli.dinner.regression

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.remote.BaiduAuthInterceptor
import com.guolongli.dinner.data.remote.ErrorMappingInterceptor
import com.guolongli.dinner.data.remote.NetworkModule
import com.guolongli.dinner.data.remote.TokenCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归：非 2xx 响应必须折成 [DinnerException]，不能漏成 OkHttp / Retrofit 的原始异常。
 *
 * 背景（实测确认的坑）：`BaiduErrorMapper.mapHttpError` 早就写好了，
 * 但**生产代码里从来没有人调用它**——它只出现在单测里。
 *
 * 后果是整条「鉴权失效自动补救」链路其实是断的：
 * - Retrofit 在非 2xx 时抛的是 `HttpException`；
 * - `SyncRepositoryImpl.uploadRecord` 的 `catch (e: DinnerException.AuthRequired)`
 *   抓不到它，401 / 403 被当成普通失败记进 FAILED；
 * - `SessionManager.onAuthError(...)` 因此永远不被调用，token 不会刷新；
 * - 用户看到的是「上传失败」，重试多少次都一样，直到自己去重新授权。
 *
 * 现在 `NetworkModule` 的三个 client 都挂了 [ErrorMappingInterceptor]，
 * 这里用 MockWebServer 钉住：401 必须变成 `AuthRequired`，5xx 不能误判成鉴权。
 */
class HttpErrorMappingRegressionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 用与生产一致的构造方式：鉴权拦截器 + 错误映射拦截器。
        client = OkHttpClient.Builder()
            .addInterceptor(BaiduAuthInterceptor(TokenCache()))
            .addInterceptor(ErrorMappingInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(): Request = Request.Builder().url(server.url("/rest/2.0/xpan/file")).build()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `401 折成 AuthRequired，才能触发刷新或重新授权`() = runBlocking {
        enqueue(401, """{"error":"invalid_token"}""")

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("401 没有折成 DinnerException：$thrown", thrown is DinnerException)
        assertTrue("401 必须是 AuthRequired：$thrown", thrown is DinnerException.AuthRequired)
    }

    @Test
    fun `403 折成 AuthRequired`() = runBlocking {
        enqueue(403, """{"errno":-6,"errmsg":"身份验证失败"}""")

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("403 必须是 AuthRequired：$thrown", thrown is DinnerException.AuthRequired)
    }

    @Test
    fun `响应体带 errno 时按 errno 归因而非只看状态码`() = runBlocking {
        // 31061「文件已存在」不是鉴权错误，不能被状态码带偏。
        enqueue(400, """{"errno":31061,"errmsg":"文件已存在"}""")

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("31061 必须是 Remote：$thrown", thrown is DinnerException.Remote)
        assertEquals(31061, (thrown as DinnerException.Remote).errno)
    }

    @Test
    fun `频控错误码折成 FrequencyLimited`() = runBlocking {
        enqueue(403, """{"errno":20012,"errmsg":"请求过于频繁"}""")

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("20012 必须是 FrequencyLimited：$thrown", thrown is DinnerException.FrequencyLimited)
    }

    @Test
    fun `5xx 不能误判成鉴权失败`() = runBlocking {
        // 服务端故障重试即可，说成「授权失效」会把用户赶去重新授权，白折腾一次。
        enqueue(500, "internal error")

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("5xx 折成了鉴权失败：$thrown", thrown !is DinnerException.AuthRequired)
        // 但仍然要是一个可翻译的 DinnerException，不能把原始异常漏给业务层。
        assertTrue("5xx 没有折成 DinnerException：$thrown", thrown is DinnerException)
    }

    @Test
    fun `OAuth 换 token 的 400 invalid_grant 折成 AuthRequired`() = runBlocking {
        // 实测：用无效 code 换 token 返回 HTTP 400 + invalid_grant。
        // 这条必须让界面引导重新授权，而不是报一句看不懂的原始错误。
        enqueue(
            400,
            """{"error":"invalid_grant","error_description":"invalid code , expired or revoked"}""",
        )

        val thrown = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertTrue("invalid_grant 必须是 AuthRequired：$thrown", thrown is DinnerException.AuthRequired)
    }

    @Test
    fun `2xx 响应原样放行，不做任何改写`() = runBlocking {
        enqueue(200, """{"errno":0,"errmsg":"succ","list":[]}""")

        val response = client.newCall(request()).execute()
        response.use {
            assertEquals(200, it.code)
            assertTrue("成功响应体被吞掉了", it.body!!.string().contains("errno"))
        }
    }

    @Test
    fun `生产 client 上确实挂了错误映射拦截器`() {
        // 直接检查装配结果，防止以后有人把它从 NetworkModule 里删掉而测试仍全绿。
        val pan = NetworkModule.panClient(TokenCache())
        val oauth = NetworkModule.oauthClient()
        val download = NetworkModule.downloadClient()
        listOf("pan" to pan, "oauth" to oauth, "download" to download).forEach { (name, client) ->
            assertTrue(
                "$name client 缺少 ErrorMappingInterceptor",
                client.interceptors.any { it is ErrorMappingInterceptor },
            )
        }
    }
}
