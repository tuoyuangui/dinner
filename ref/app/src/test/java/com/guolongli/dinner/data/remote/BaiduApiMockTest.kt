package com.guolongli.dinner.data.remote

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 用 MockWebServer 验证网盘接口的序列化解析与错误映射，不依赖真实网盘。
 */
class BaiduApiMockTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BaiduFileApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val contentType = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/rest/2.0/xpan/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        api = retrofit.create(BaiduFileApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list 解析成功`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"errno":0,"errmsg":"succ",
                        "list":[{"fs_id":123,"path":"/apps/dinner/meals/2026/10/a.jpg",
                        "md5":"abc","size":100,"server_mtime":1729500000}],
                        "has_more":0,"cursor":0}"""
                )
        )

        val response = api.list(dir = "/apps/dinner/meals/2026/10")
        assertTrue(response.isSuccess)
        assertEquals(1, response.list.size)
        assertEquals("/apps/dinner/meals/2026/10/a.jpg", response.list[0].path)
        assertEquals(123L, response.list[0].fsId)
    }

    @Test
    fun `errno 非零映射为 Remote 异常`() {
        val ex = BaiduErrorMapper.mapErrno(12, "系统繁忙")
        assertTrue(ex is DinnerException.Remote)
        assertEquals(12, (ex as DinnerException.Remote).errno)
    }

    @Test
    fun `频控 20012 映射为 FrequencyLimited`() {
        val ex = BaiduErrorMapper.mapErrno(BaiduApi.ERRNO_FREQUENCY_LIMIT, "freq")
        assertTrue(ex is DinnerException.FrequencyLimited)
    }

    @Test
    fun `鉴权 errno 映射为 AuthRequired`() {
        val ex = BaiduErrorMapper.mapErrno(110, "access token invalid")
        assertTrue(ex is DinnerException.AuthRequired)
    }

    @Test
    fun `nas 接口报的 20017 也归为鉴权失败`() {
        // 真实接口实测：GET /rest/2.0/xpan/nas?method=uinfo 传失效 token 返回
        // {"errno":20017,"show_msg":"nas token无效"}。它同样是登录态失效，
        // 归错类的话用户只会看到一句原始报错，界面也不给重新授权的入口。
        val ex = BaiduErrorMapper.mapErrno(BaiduApi.ERRNO_NAS_TOKEN_INVALID, "nas token无效")
        assertTrue(ex is DinnerException.AuthRequired)
    }

    @Test
    fun `文件已存在不归为鉴权失败`() {
        // 覆盖写同名文件会撞 31061，它跟授权毫无关系
        val ex = BaiduErrorMapper.mapErrno(BaiduApi.ERRNO_FILE_EXIST, "文件已存在")
        assertTrue(ex is DinnerException.Remote)
    }

    @Test
    fun `HTTP 401 映射为 AuthRequired`() {
        val ex = BaiduErrorMapper.mapHttpError(401, "{}")
        assertTrue(ex is DinnerException.AuthRequired)
    }

    @Test
    fun `TokenCache 更新与清空`() {
        val cache = TokenCache()
        assertTrue(!cache.hasToken())
        assertEquals("", cache.current())
        cache.update("tok")
        assertTrue(cache.hasToken())
        assertEquals("tok", cache.current())
        cache.clear()
        assertTrue(!cache.hasToken())
    }
}