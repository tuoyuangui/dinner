package com.guolongli.dinner.data.netdisk

import com.guolongli.dinner.data.auth.TokenSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载链路对照「查询文件信息」文档：
 * - filemetas（fsids 批量）必须走 xpan/multimedia，响应里的 dlink 在 `list` 数组里
 *   （老接口 / 个别版本塞在 `info` 数组，两种都要认）。
 * - 早先的实现既打错了端点（xpan/file）、又错读了 `info` 数组，结果是
 *   「同步回来了却怎么都打不开照片」。这里用 MockWebServer 跑真实 fileMetas + download。
 */
class NetdiskRepositoryDownloadTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun host() = server.url("/").toString().trimEnd('/')

    private fun repo(): NetdiskRepository {
        val api = NetdiskApi(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            panHost = host(),
            pcsHost = host(),
        )
        return NetdiskRepository(object : TokenSource {
            override suspend fun token(): String = "tk"
        }, api)
    }

    private fun entry() = RemoteFile(
        fsId = 7L,
        path = "/apps/dinner/media/a.jpg",
        name = "a.jpg",
        size = 10,
        isDir = false,
        modifiedAt = 0,
    )

    @Test
    fun `按文档的 list 数组取 dlink 并下载`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"errno":0,"list":[{"fs_id":7,"dlink":"${host()}/dfile?x=1"}]}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("FILE-BYTES"))

        val target = File.createTempFile("dl-", ".bin")
        try {
            val result = repo().download(entry(), target)
            assertEquals("FILE-BYTES", result.readText())
        } finally {
            target.delete()
        }

        // 第一步必须打到 xpan/multimedia
        val first = server.takeRequest()
        assertTrue(
            "filemetas 必须命中 xpan/multimedia：${first.path}",
            first.path!!.startsWith("/rest/2.0/xpan/multimedia?method=filemetas"),
        )
        // 第二步是 dlink 下载，且带 pan.baidu.com 的 UA
        val second = server.takeRequest()
        assertEquals("pan.baidu.com", second.getHeader("User-Agent"))
        assertTrue("dlink 要带 access_token：${second.path}", second.path!!.contains("access_token=tk"))
    }

    @Test
    fun `兼容老接口 info 数组`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"errno":0,"info":[{"fs_id":7,"dlink":"${host()}/dfile?x=2"}]}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("FILE-BYTES"))

        val target = File.createTempFile("dl-", ".bin")
        try {
            repo().download(entry(), target)
        } finally {
            target.delete()
        }

        val req = server.takeRequest()
        assertTrue("老接口 info 也应能取到 dlink：${req.path}", req.path!!.startsWith("/rest/2.0/xpan/multimedia?method=filemetas"))
    }

    @Test
    fun `拿不到 dlink 如实报错`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"errno":0,"list":[{"fs_id":7}]}"""),
        )

        val target = File.createTempFile("dl-", ".bin")
        var threw = false
        try {
            try {
                repo().download(entry(), target)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("dlink 缺失应当抛错而不是下个空文件", threw)
        } finally {
            target.delete()
        }
    }

    @Test
    fun `条目级 errno 透出来，42214 这类可重试`() = runTest {
        // 文档（查询文件信息）：批量查询时先判断顶层 errno，再按 list 条目处理结果。
        // 顶层 errno=0 但条目 errno=42214（文件基础信息查询失败）也是失败，不能装没事。
        // 42214 可重试 → retry 会打 3 次，这里排 3 份同样的响应。
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"errno":0,"list":[{"fs_id":7,"errno":42214,"errmsg":"meta query failed"}]}"""),
            )
        }

        val target = File.createTempFile("dl-", ".bin")
        try {
            repo().download(entry(), target)
            org.junit.Assert.fail("条目失败必须抛错")
        } catch (e: NetdiskException) {
            assertEquals(42214, e.errno)
            assertTrue("文档口径可重试", e.isTransient())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `多条目时严格按 fs_id 取 dlink，不下错文件`() = runTest {
        // 混进一个带 dlink 的“邻居”条目：早先的实现会顺手拿它的 dlink，
        // 把 B 文件下成 A 文件——比下载失败更难发现
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """{"errno":0,"list":[
                        {"fs_id":99,"dlink":"${host()}/wrong.bin"},
                        {"fs_id":7,"dlink":"${host()}/right.bin"}
                    ]}""",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("RIGHT"))

        val target = File.createTempFile("dl-", ".bin")
        try {
            repo().download(entry(), target)
            assertEquals("必须下 fs_id 匹配的那份", "RIGHT", target.readText())
        } finally {
            target.delete()
        }
    }
}
