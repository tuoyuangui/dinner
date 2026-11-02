package com.guolongli.dinner.data.netdisk

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 网盘接口层测试：用 MockWebServer 顶替 pan.baidu.com，
 * 断言的是「发出去的请求长什么样」和「返回怎么解析」，这两件事错了前面所有逻辑都白做。
 */
class NetdiskApiTest {

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

    private fun api() = NetdiskApi(
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        panHost = host(),
        pcsHost = host(),
    )

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun `预上传把 path size block_list 放进表单并带回 uploadid`() = runTest {
        server.enqueue(json("""{"errno":0,"uploadid":"up-123","return_type":1,"block_list":[0]}"""))

        val result = api().precreate(
            token = "tk",
            path = "/apps/dinner/records/rec-a.json",
            size = 42,
            isDir = false,
            blockList = listOf("md5-1", "md5-2"),
        )

        assertEquals("up-123", result.optString("uploadid"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(
            "method 与 access_token 应该在 query 上，实际：${request.path}",
            request.path!!.startsWith("/rest/2.0/xpan/file?method=precreate&access_token=tk"),
        )
        val body = request.body.readUtf8()
        assertTrue("表单里要有 path：$body", body.contains("path=") && body.contains("rec-a.json"))
        assertTrue("表单里要有 size：$body", body.contains("size=42"))
        assertTrue("表单里要有 isdir：$body", body.contains("isdir=0"))
        assertTrue("block_list 是 JSON 数组：$body", body.contains("block_list"))
        assertTrue(body.contains("md5-1"))
        assertTrue("上传必须带 pan.baidu.com 的 UA", request.getHeader("User-Agent") == "pan.baidu.com")
    }

    @Test
    fun `建目录用 isdir=1 且 size 为 0`() = runTest {
        server.enqueue(json("""{"errno":0,"uploadid":"up-dir"}"""))

        api().precreate(
            token = "tk",
            path = "/apps/dinner/records",
            size = 0,
            isDir = true,
            blockList = emptyList(),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("isdir=1"))
        assertTrue(body.contains("size=0"))
    }

    @Test
    fun `建目录走 create，带 isdir=1 与空 block_list，默认不带 uploadid`() = runTest {
        server.enqueue(json("""{"errno":0,"fs_id":7,"isdir":1}"""))

        api().createDir("tk", "/apps/dinner/records")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(
            "建目录要打 create：${request.path}",
            request.path!!.startsWith("/rest/2.0/xpan/file?method=create&access_token=tk"),
        )
        val body = request.body.readUtf8()
        assertTrue("isdir=1：$body", body.contains("isdir=1"))
        assertTrue("size=0：$body", body.contains("size=0"))
        assertTrue("block_list 传空数组：$body", body.contains("block_list"))
        assertTrue("默认不带 uploadid（文档的建目录示例就没有它）：$body", !body.contains("uploadid"))
        assertTrue("默认不带 rtype，冲突即失败，避免造重复目录：$body", !body.contains("rtype"))
    }

    @Test
    fun `建目录兜底时可以带上 uploadid 与 rtype=3`() = runTest {
        server.enqueue(json("""{"errno":0,"fs_id":7,"isdir":1}"""))

        api().createDir("tk", "/apps/dinner/records", uploadId = "up-dir", rtype = 3)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("uploadid=up-dir"))
        assertTrue(body.contains("rtype=3"))
    }

    @Test
    fun `递归列表带 recursion=1，分页参数不丢`() = runTest {
        server.enqueue(
            json(
                """{"errno":0,"list":[
                   {"fs_id":11,"path":"/apps/dinner/records/rec-a.json","server_filename":"rec-a.json","size":10,"isdir":0,"server_mtime":100},
                   {"fs_id":12,"path":"/apps/dinner/records","server_filename":"records","size":0,"isdir":1,"server_mtime":90}
                ]}""",
            ),
        )

        val result = run {
            val repositoryLikeParse = api().listAll("tk", "/apps/dinner", recursion = true, start = 0, limit = 500)
            repositoryLikeParse.optJSONArray("list")!!
        }

        assertEquals(2, result.length())
        assertEquals(11L, result.getJSONObject(0).getLong("fs_id"))

        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/rest/2.0/xpan/multimedia?method=listall"))
        assertTrue("递归标志：$path", path.contains("recursion=1"))
        assertTrue("limit 要带上：$path", path.contains("limit=500"))
        assertTrue("按时间倒序：$path", path.contains("order=time") && path.contains("desc=1"))
    }

    @Test
    fun `文件信息把 fsids 拼成 JSON 数组并要 dlink`() = runTest {
        server.enqueue(json("""{"errno":0,"info":[{"fs_id":7,"dlink":"https://d.pcs.baidu.com/dfile?x=1"}]}"""))

        val info = api().fileMetas("tk", listOf(7L), dlink = true)
        assertEquals("https://d.pcs.baidu.com/dfile?x=1", info.getJSONArray("info").getJSONObject(0).getString("dlink"))

        val path = server.takeRequest().path!!
        // 文档：按 fsid 批量查询必须走 xpan/multimedia，打到 xpan/file 会 404 / 返回空
        assertTrue("filemetas 必须命中 xpan/multimedia：$path", path.startsWith("/rest/2.0/xpan/multimedia?method=filemetas"))
        assertTrue("fsids 必须是数组：$path", path.contains("fsids=%5B7%5D"))
        assertTrue(path.contains("dlink=1"))
    }

    @Test
    fun `下载必须带 UA，否则百度会 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("binary-bytes"))
        val target = File.createTempFile("dl-", ".bin")

        api().download("${host()}/dfile?x=1", "tk", target)

        val request = server.takeRequest()
        assertEquals("pan.baidu.com", request.getHeader("User-Agent"))
        assertTrue("dlink 后面要拼 access_token：${request.path}", request.path!!.contains("access_token=tk"))
        assertEquals("binary-bytes", target.readText())
        target.delete()
    }

    @Test
    fun `下载跟 302 时逐跳补 access_token，UA 不丢`() = runTest {
        // 文档：dlink 可能 302 到 CDN，客户端应跟随跳转并保留鉴权参数。
        // 第一跳 Location 里故意不带 token——第二跳必须补上。
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "${host()}/cdn/file.bin"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("REDIRECTED"))
        val target = File.createTempFile("dl302-", ".bin")

        api().download("${host()}/dfile?x=1", "tk", target)

        assertEquals("REDIRECTED", target.readText())
        val hop1 = server.takeRequest()
        val hop2 = server.takeRequest()
        assertTrue("第一跳带 token：${hop1.path}", hop1.path!!.contains("access_token=tk"))
        assertTrue("第二跳也要带 token：${hop2.path}", hop2.path!!.contains("access_token=tk"))
        assertEquals("每一跳都要带 UA", "pan.baidu.com", hop2.getHeader("User-Agent"))
        target.delete()
    }

    @Test
    fun `下载响应是带 errno 的 JSON 时抛业务错误，不把错误写成文件`() = runTest {
        // 文档：「下载失败且返回 JSON 时」带 errno——31360 就是链接过期
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"errno":31360,"errmsg":"dlink expired"}"""),
        )
        val target = File.createTempFile("dlerr-", ".bin")

        try {
            api().download("${host()}/dfile?x=1", "tk", target)
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(31360, e.errno)
            assertTrue("链接过期可重试（重新 filemetas 拿新 dlink）", e.isTransient())
        }
        assertTrue("错误响应绝不能落盘成“文件”", !target.exists() || target.readText().isBlank())
        target.delete()
    }

    @Test
    fun `下载真 JSON 文件（无 errno 字段）不误判为错误`() = runTest {
        // 记录快照就是 JSON 正文；判据必须是「有没有 errno 字段」而不是 Content-Type
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"rec-1","text":"晚饭"}"""),
        )
        val target = File.createTempFile("dljson-", ".json")

        api().download("${host()}/dfile?x=1", "tk", target)

        assertEquals("""{"id":"rec-1","text":"晚饭"}""", target.readText())
        target.delete()
    }

    @Test
    fun `HTML 错误页按网络故障处理，可重试`() = runTest {
        // 网关拦下请求时回 HTML 而不是 JSON；以前 JSONObject() 直接抛天书异常
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html>502 Bad Gateway</html>"),
        )
        try {
            api().quota("tk")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(-130, e.errno)
            assertTrue("网关故障值得重试", e.isTransient())
        }
    }

    @Test
    fun `删除用 filelist 数组`() = runTest {
        server.enqueue(json("""{"errno":0}"""))
        api().delete("tk", listOf("/apps/dinner/records/rec-a.json"))

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("method=delete"))
        val body = request.body.readUtf8()
        assertTrue("filelist 是 JSON 数组：$body", body.contains("filelist="))
        assertTrue(body.contains("rec-a.json"))
    }

    @Test
    fun `errno 20012 是频控，应当允许重试`() = runTest {
        server.enqueue(json("""{"errno":20012,"errmsg":"request too frequent"}"""))
        try {
            api().quota("tk")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(20012, e.errno)
            assertTrue(e.isTransient())
            assertTrue("频控不算鉴权错误", !e.isAuthError())
        }
    }

    @Test
    fun `errno 111 是 token 过期，不该当频控重试`() = runTest {
        server.enqueue(json("""{"errno":111,"errmsg":"access token expired"}"""))
        try {
            api().list("tk", "/apps/dinner")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(111, e.errno)
            assertTrue("111 是鉴权类，必须回去刷新 token", e.isAuthError())
            assertTrue("鉴权错误重试再多也没用", !e.isTransient())
        }
    }

    @Test
    fun `errno 负 6 是鉴权失效，不该重试`() = runTest {
        server.enqueue(json("""{"errno":-6,"errmsg":"invalid access token"}"""))
        try {
            api().list("tk", "/apps/dinner")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(-6, e.errno)
            assertTrue(e.isAuthError())
            assertTrue("鉴权错误不是瞬时故障", !e.isTransient())
        }
    }

    @Test
    fun `error_code 也能当错误码识别`() = runTest {
        server.enqueue(json("""{"error_code":110,"error_msg":"token expired"}"""))
        try {
            api().quota("tk")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(110, e.errno)
            assertTrue(e.isAuthError())
        }
    }

    @Test
    fun `空响应体不当作成功，且算网络故障可重试`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        try {
            api().quota("tk")
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            // -130（网络故障）而不是 -1（参数错误）：前者值得退避重试，后者重试也没用
            assertEquals(-130, e.errno)
            assertTrue(e.isTransient())
        }
    }

    @Test
    fun `单步上传带 ondup=overwrite，重传不会留下两份`() = runTest {
        server.enqueue(json("""{"errno":0,"path":"/apps/dinner/media/a.jpg"}"""))

        api().uploadSmall(host(), "tk", "/apps/dinner/media/a.jpg", "img".toByteArray())

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/rest/2.0/pcs/file?method=upload"))
        assertTrue(
            "冲突时要覆盖，否则固定路径重传会失败：${request.path}",
            request.path!!.contains("ondup=overwrite"),
        )
        assertTrue("路径要 URL 编码后进 query：${request.path}", request.path!!.contains("apps"))
        assertEquals("pan.baidu.com", request.getHeader("User-Agent"))
        val body = request.body.readUtf8()
        assertTrue("文件本体走 multipart 的 file 字段：$body", body.contains("name=\"file\""))
    }

    @Test
    fun `上传域名按 host、servers、bak_servers 顺序给出候选`() = runTest {
        server.enqueue(
            json(
                """{"error_code":0,"host":"c1.pcs.baidu.com",
                   "servers":[{"server":"c2.pcs.baidu.com"},{"server":"https://c3.pcs.baidu.com/"}],
                   "bak_servers":[{"server":"c4.pcs.baidu.com"}]}""",
            ),
        )

        val hosts = api().locateUploadHosts("tk", "/apps/dinner/a.jpg", "up-1")

        assertEquals(
            listOf(
                "https://c1.pcs.baidu.com",
                "https://c2.pcs.baidu.com",
                "https://c3.pcs.baidu.com",
                "https://c4.pcs.baidu.com",
            ),
            hosts,
        )
    }

    @Test
    fun `删除失败走 error_code，不能被当成成功`() = runTest {
        server.enqueue(json("""{"error_code":31024,"error_msg":"no permission"}"""))
        try {
            api().delete("tk", listOf("/apps/dinner/records/a.json"))
            fail("应该抛 NetdiskException")
        } catch (e: NetdiskException) {
            assertEquals(31024, e.errno)
        }
    }
}
