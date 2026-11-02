package com.guolongli.dinner.data.remote

import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 上传三段式的回归测试。
 *
 * 这里守的是一个真实踩过的坑：分片上传曾经被写成 `xpan/file?method=superfile2`，
 * 并把 path / uploadid / partseq 当成 @Field 塞进 @Multipart 方法里。
 * Retrofit 在方法第一次被调用时就会抛
 * "Field parameters can only be used with form encoding"，
 * 于是每一段新媒体文件都在第二步失败，用户只看到「同步失败」。
 */
class BaiduUploadMockTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BaiduFileApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/rest/2.0/xpan/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(BaiduFileApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `预上传必须带 autoinit 与 rtype 覆盖标记`() = runBlocking {
        server.enqueue(
            json(
                """{"errno":0,"errmsg":"succ","uploadid":"UP-1","return_type":1,
                    "block_list":[0]}"""
            )
        )

        val response = api.precreate(
            path = "/apps/dinner/data/records.json",
            size = 1024,
            blockList = """["abc"]""",
        )
        assertEquals("UP-1", response.uploadId)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("body 缺少 autoinit：$body", body.contains("autoinit=1"))
        assertTrue("body 缺少 rtype=3：$body", body.contains("rtype=3"))
    }

    @Test
    fun `创建文件必须带 rtype 覆盖标记`() = runBlocking {
        server.enqueue(json("""{"errno":0,"errmsg":"succ","fs_id":1,"path":"/apps/dinner/a.jpg"}"""))

        api.createFile(
            path = "/apps/dinner/a.jpg",
            size = 10,
            blockList = """["abc"]""",
            uploadId = "UP-1",
        )

        val request = server.takeRequest()
        assertEquals("create", request.requestUrl?.queryParameter("method"))
        val body = request.body.readUtf8()
        assertTrue("body 缺少 rtype=3：$body", body.contains("rtype=3"))
        assertTrue("body 缺少 uploadid：$body", body.contains("uploadid=UP-1"))
    }

    @Test
    fun `分片上传打到 PCS 主机且参数在查询串里`() = runBlocking {
        server.enqueue(json("""{"errno":0,"errmsg":"succ","md5":"deadbeef"}"""))

        val url = server.url(
            "/rest/2.0/pcs/superfile2" +
                "?method=upload&type=tmpfile" +
                "&path=%2Fapps%2Fdinner%2Fa.jpg&uploadid=UP-1&partseq=0"
        ).toString()
        val part = MultipartBody.Part.createFormData(
            "file",
            "a.jpg.part0",
            "hello".toRequestBody("application/octet-stream".toMediaType()),
        )

        val response = api.uploadPart(url = url, file = part)
        assertEquals(0, response.errno)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/2.0/pcs/superfile2", request.requestUrl?.encodedPath)
        assertEquals("upload", request.requestUrl?.queryParameter("method"))
        assertEquals("tmpfile", request.requestUrl?.queryParameter("type"))
        assertEquals("/apps/dinner/a.jpg", request.requestUrl?.queryParameter("path"))
        assertEquals("UP-1", request.requestUrl?.queryParameter("uploadid"))
        assertEquals("0", request.requestUrl?.queryParameter("partseq"))

        val body = request.body.readUtf8()
        assertTrue("multipart 里没有 file 字段：$body", body.contains("""name="file""""))
        assertTrue("缺少文件名：$body", body.contains("filename=\"a.jpg.part0\""))
    }

    @Test
    fun `分片 URL 对中文与空格做编码`() {
        val url = BaiduFileDataSource.shardUrl(
            host = "https://d.pcs.baidu.com/",
            remotePath = "/apps/我的 晚餐/a.jpg",
            uploadId = "UP-1",
            partSeq = 3,
        )

        assertTrue(url.startsWith("https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?"))
        assertTrue("path 未编码：$url", url.contains("path=%2Fapps%2F"))
        assertTrue("partseq 丢失：$url", url.contains("partseq=3"))
        assertTrue("type 缺失：$url", url.contains("type=tmpfile"))
        assertTrue("method 缺失：$url", url.contains("method=upload"))
    }

    @Test
    fun `31061 文件已存在不能当成鉴权失败`() {
        val ex = BaiduErrorMapper.mapErrno(31061, "文件已存在")
        assertTrue("31061 被误判为鉴权失败：$ex", ex is com.guolongli.dinner.core.error.DinnerException.Remote)
        assertEquals(31061, (ex as com.guolongli.dinner.core.error.DinnerException.Remote).errno)
    }

    @Test
    fun `-6 token 无效必须归为鉴权失败`() {
        val ex = BaiduErrorMapper.mapErrno(-6, "身份验证失败")
        assertTrue("-6 未触发重新授权：$ex", ex is com.guolongli.dinner.core.error.DinnerException.AuthRequired)
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
