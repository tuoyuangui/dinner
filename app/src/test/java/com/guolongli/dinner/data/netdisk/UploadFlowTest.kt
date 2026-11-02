package com.guolongli.dinner.data.netdisk

import com.guolongli.dinner.testing.FakeTokenSource
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
 * 上传流程测试：预上传 → 取上传域名 → 逐片 superfile2 → create 合并。
 * 把 CHUNK_SIZE 调到 4 字节，一个 10 字节的文件就能覆盖「多分片」这条最容易错的路径。
 */
class UploadFlowTest {

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

    private fun repository(
        retryTimes: Int = 3,
        chunkSize: Int = 4,
        singleStepMaxSize: Long = 0,
    ) = NetdiskRepository(
        auth = FakeTokenSource("tk"),
        api = NetdiskApi(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            panHost = host(),
            pcsHost = host(),
        ),
        chunkSize = chunkSize,
        // 默认关掉单步上传：这里要覆盖的是多分片路径，10 字节文件在真机上会走单步
        singleStepMaxSize = singleStepMaxSize,
        retryTimes = { retryTimes },
        retryDelayMs = { 0L },
    )

    private fun response(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** 预上传 + 上传域名，这两步每个上传都要走 */
    private fun enqueuePrelude(uploadId: String = "up-1") {
        server.enqueue(response("""{"errno":0,"uploadid":"$uploadId","return_type":1}"""))
        server.enqueue(
            response("""{"errno":0,"servers":[{"server":"${host()}"}]}"""),
        )
    }

    private fun tempFile(content: String, suffix: String = ".txt"): File =
        File.createTempFile("upload-", suffix).apply { writeText(content) }

    @Test
    fun `10 字节文件按 4 字节切片，三片各自上传后用 md5 合并`() = runTest {
        val file = tempFile("0123456789")
        enqueuePrelude()
        server.enqueue(response("""{"errno":0,"md5":"m0"}"""))
        server.enqueue(response("""{"errno":0,"md5":"m1"}"""))
        server.enqueue(response("""{"errno":0,"md5":"m2"}"""))
        server.enqueue(response("""{"errno":0,"fs_id":123}"""))

        val remotePath = repository().uploadFile(file, "rec-a.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/rec-a.json", remotePath)

        val precreate = server.takeRequest()
        assertTrue(precreate.path!!.contains("method=precreate"))
        val precreateBody = precreate.body.readUtf8()
        assertTrue("预上传要带 size=10：$precreateBody", precreateBody.contains("size=10"))
        assertTrue("预上传带三个分片的 md5：$precreateBody", precreateBody.contains("block_list"))

        val locate = server.takeRequest()
        assertTrue(
            "上传域名必须动态取，不能写死：${locate.path}",
            locate.path!!.contains("method=locateupload"),
        )
        assertTrue(locate.path!!.contains("uploadid=up-1"))

        val partSeqs = mutableListOf<String>()
        repeat(3) {
            val slice = server.takeRequest()
            assertTrue(slice.path!!.contains("method=upload"))
            assertTrue("走的是分片接口 superfile2：${slice.path}", slice.path!!.contains("superfile2"))
            partSeqs += Regex("partseq=(\\d+)").find(slice.path!!)!!.groupValues[1]
            assertTrue("分片必须带 uploadid：${slice.path}", slice.path!!.contains("uploadid=up-1"))
        }
        assertEquals(listOf("0", "1", "2"), partSeqs)

        val create = server.takeRequest()
        assertTrue(create.path!!.contains("method=create"))
        val createBody = create.body.readUtf8()
        assertTrue("合并时用各片返回的 md5：$createBody", createBody.contains("m0") && createBody.contains("m2"))
        assertTrue(createBody.contains("uploadid=up-1"))
        assertTrue("不是目录：$createBody", createBody.contains("isdir=0"))

        file.delete()
    }

    @Test
    fun `秒传直接结束，不再传任何分片`() = runTest {
        val file = tempFile("same-content")
        server.enqueue(response("""{"errno":0,"return_type":2,"uploadid":"up-fast"}"""))

        val remotePath = repository().uploadFile(file, "rec-dup.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/rec-dup.json", remotePath)
        assertEquals("只应该发一次预上传", 1, server.requestCount)
        file.delete()
    }

    @Test
    fun `频控 20012 会自动重试，第二次成功后正常返回`() = runTest {
        val file = tempFile("retry-me")
        server.enqueue(response("""{"errno":20012,"errmsg":"request too frequent"}"""))
        // 第二次预上传变成秒传，省掉后面的分片
        server.enqueue(response("""{"errno":0,"return_type":2,"uploadid":"up-2"}"""))

        val remotePath = repository(retryTimes = 3).uploadFile(file, "rec-retry.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/rec-retry.json", remotePath)
        assertEquals("一次失败 + 一次成功", 2, server.requestCount)
        file.delete()
    }

    @Test
    fun `鉴权错误不重试，直接抛出来让用户重新授权`() = runTest {
        val file = tempFile("auth-error")
        server.enqueue(response("""{"errno":-6,"errmsg":"invalid access token"}"""))
        server.enqueue(response("""{"errno":0,"return_type":2}"""))

        try {
            repository(retryTimes = 3).uploadFile(file, "rec-auth.json", "/apps/dinner/records")
            fail("应该抛鉴权错误")
        } catch (e: NetdiskException) {
            assertTrue(e.isAuthError())
        }
        assertEquals("鉴权失败不该重试，只应发一次", 1, server.requestCount)
        file.delete()
    }

    @Test
    fun `建目录直接 create，不预上传，且不会重复建`() = runTest {
        // 第一次：list 目标目录本身回 -9（目录不存在）→ 建
        // 注意「不存在」只认 -9 / 31066，不能靠「父目录里没找到」推断
        server.enqueue(response("""{"errno":-9,"errmsg":"file does not exist"}"""))
        server.enqueue(response("""{"errno":0,"fs_id":9,"isdir":1}"""))

        val repository = repository()
        assertTrue("第一次应该真的建了目录", repository.ensureDir("/apps/dinner/records"))

        val list = server.takeRequest()
        assertTrue("要 list 目录自己：${list.path}", list.path!!.contains("method=list"))
        assertTrue("只取一条就够：${list.path}", list.path!!.contains("limit=1"))

        val create = server.takeRequest()
        assertTrue("建目录要用 create：${create.path}", create.path!!.contains("method=create"))
        val body = create.body.readUtf8()
        assertTrue("建目录 isdir=1：$body", body.contains("isdir=1"))
        assertTrue("建目录 size=0：$body", body.contains("size=0"))
        assertTrue("block_list 要传空数组：$body", body.contains("block_list"))
        assertEquals("共两次请求：list + create", 2, server.requestCount)

        // 第二次：同一个实例里已经确认过存在，连请求都不该发
        assertEquals("已存在就不该再建", false, repository.ensureDir("/apps/dinner/records"))
        assertEquals("缓存命中，不该再有请求", 2, server.requestCount)
    }

    @Test
    fun `建目录回 errno=2 时换参数重来，最终用预上传拿 uploadid 建成`() = runTest {
        // 真实故障复现：新账号云端还没有 /apps/dinner，服务端对「不带 uploadid 建目录」
        // 一律回 errno=2（参数错误），三套参数里只有最后一套能成。
        server.enqueue(response("""{"errno":-9}"""))
        server.enqueue(response("""{"errno":2,"errmsg":"param error"}"""))
        server.enqueue(response("""{"errno":2,"errmsg":"param error"}"""))
        server.enqueue(response("""{"errno":0,"uploadid":"up-dir"}"""))
        server.enqueue(response("""{"errno":0,"fs_id":9,"isdir":1}"""))

        val repository = repository()
        assertTrue(repository.ensureDir("/apps/dinner"))

        val paths = (1..5).map { server.takeRequest().path.orEmpty() }
        assertEquals("list + create + create + precreate + create", 5, paths.size)
        assertTrue(paths[0].contains("method=list"))
        assertTrue(paths[1].contains("method=create"))
        assertTrue(paths[2].contains("method=create"))
        assertTrue(paths[3].contains("method=precreate"))
        assertTrue(paths[4].contains("method=create"))
    }

    @Test
    fun `建目录回「已存在」算成功，不往上抛`() = runTest {
        server.enqueue(response("""{"errno":-9}"""))
        server.enqueue(response("""{"errno":-8,"errmsg":"file already exists"}"""))

        val repository = repository()
        assertTrue(repository.ensureDir("/apps/dinner"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `容量不足这类错误不换参数硬试，直接抛出`() = runTest {
        server.enqueue(response("""{"errno":-9}"""))
        server.enqueue(response("""{"errno":-10,"errmsg":"capacity limit"}"""))

        try {
            repository().ensureDir("/apps/dinner")
            fail("容量不足必须抛出来")
        } catch (e: NetdiskException) {
            assertEquals(-10, e.errno)
        }
        assertEquals("只该试一次 create", 2, server.requestCount)
    }

    @Test
    fun `目录已存在时一次 list 就返回，不产生写请求`() = runTest {
        server.enqueue(
            response("""{"errno":0,"list":[{"path":"/apps/dinner/records","isdir":1,"server_filename":"records"}]}"""),
        )
        val repository = repository()
        assertEquals(false, repository.ensureDir("/apps/dinner/records"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `列目录出错时不上抛成「目录不存在」，避免误建`() = runTest {
        // 鉴权失败 ≠ 目录不存在。早先的实现会去 create，叠上 rtype=1 就造出重复目录
        server.enqueue(response("""{"errno":-6,"errmsg":"invalid access token"}"""))
        try {
            repository().ensureDir("/apps/dinner/records")
            fail("鉴权错误必须抛出来")
        } catch (e: NetdiskException) {
            assertTrue(e.isAuthError())
        }
        assertEquals("不该发任何写请求", 1, server.requestCount)
    }

    @Test
    fun `小文件走单步上传，只发两次请求`() = runTest {
        val file = tempFile("tiny")
        // locateupload → 单步上传
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":0,"path":"/apps/dinner/records/tiny.json","md5":"abc"}"""))

        val remotePath = repository(singleStepMaxSize = 4L * 1024 * 1024)
            .uploadFile(file, "tiny.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/tiny.json", remotePath)
        assertEquals("省掉预上传与分片，两次就够", 2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("method=locateupload"))
        val upload = server.takeRequest()
        assertTrue("走单步上传接口：${upload.path}", upload.path!!.contains("/rest/2.0/pcs/file?method=upload"))
        assertTrue(upload.path!!.contains("ondup=overwrite"))
        file.delete()
    }

    @Test
    fun `内容没变就不重传，云端 md5 是混淆值时照传`() = runTest {
        val file = tempFile("snapshot")
        val realMd5 = java.security.MessageDigest.getInstance("MD5")
            .digest("snapshot".toByteArray()).joinToString("") { "%02x".format(it) }

        // 云端已经是同内容：list 回的 md5 与本地一致 → 跳过上传
        server.enqueue(
            response(
                """{"errno":0,"list":[{"path":"/apps/dinner/records/s.json","isdir":0,"md5":"$realMd5"}]}""",
            ),
        )
        assertEquals(false, repository().uploadIfChanged(file, "s.json", "/apps/dinner/records"))
        assertEquals("比对一致就不该再传", 1, server.requestCount)

        // 云端给的是混淆值（掺了 s/m/j）→ 不能当校验和，必须照传
        server.enqueue(
            response(
                """{"errno":0,"list":[{"path":"/apps/dinner/records/s.json","isdir":0,"md5":"dfd45931bs0000000000000000000000"}]}""",
            ),
        )
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":0,"path":"/apps/dinner/records/s.json"}"""))

        assertEquals(
            "md5 不可信时必须照传（覆盖写是幂等的）",
            true,
            repository(singleStepMaxSize = 4L * 1024 * 1024)
                .uploadIfChanged(file, "s.json", "/apps/dinner/records"),
        )
        file.delete()
    }

    @Test
    fun `单步上传的占位 uploadid 被拒后，改用真实预上传任务`() = runTest {
        val file = tempFile("tiny2")
        // 真机复现：locateupload 收到 md5 顶替的 uploadid → errno=2（参数错误）
        server.enqueue(response("""{"error_code":2,"error_msg":"param error"}"""))
        server.enqueue(response("""{"errno":0,"uploadid":"up-real","return_type":1}"""))
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":0,"path":"/apps/dinner/records/tiny2.json"}"""))

        val remotePath = repository(singleStepMaxSize = 4L * 1024 * 1024)
            .uploadFile(file, "tiny2.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/tiny2.json", remotePath)
        assertEquals(4, server.requestCount)
        val firstLocate = server.takeRequest()
        assertTrue(firstLocate.path!!.contains("method=locateupload"))
        val precreate = server.takeRequest()
        assertTrue("补一次预上传拿真 uploadid：${precreate.path}", precreate.path!!.contains("method=precreate"))
        assertTrue("文件要算好分片 md5", precreate.body.readUtf8().contains("block_list"))
        val secondLocate = server.takeRequest()
        assertTrue("第二次要带真 uploadid：${secondLocate.path}", secondLocate.path!!.contains("uploadid=up-real"))
        assertTrue(server.takeRequest().path!!.contains("method=upload"))
        file.delete()
    }

    @Test
    fun `占位 uploadid 在上传阶段才被拒时，同样回退真实预上传`() = runTest {
        val file = tempFile("tiny3")
        // 比 locate 阶段被拒更隐蔽的坑：locateupload 照样返回域名，
        // 直到 POST 文件服务端才认出 uploadid 是假的（31355 参数异常）。
        // 只兜 locate 一处的话，这里就永远失败——「小文件传不上去」的根因。
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":31355,"errmsg":"uploadid invalid"}"""))
        server.enqueue(response("""{"errno":0,"uploadid":"up-real","return_type":1}"""))
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":0,"path":"/apps/dinner/records/tiny3.json"}"""))

        val remotePath = repository(singleStepMaxSize = 4L * 1024 * 1024)
            .uploadFile(file, "tiny3.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/tiny3.json", remotePath)
        assertEquals(5, server.requestCount)
        val locate1 = server.takeRequest()
        assertTrue(locate1.path!!.contains("method=locateupload"))
        val upload1 = server.takeRequest()
        assertTrue(upload1.path!!.contains("method=upload"))
        val precreate = server.takeRequest()
        assertTrue("上传被拒后补真实预上传：${precreate.path}", precreate.path!!.contains("method=precreate"))
        val locate2 = server.takeRequest()
        assertTrue("第二次 locate 要带真 uploadid：${locate2.path}", locate2.path!!.contains("uploadid=up-real"))
        val upload2 = server.takeRequest()
        assertTrue(upload2.path!!.contains("method=upload"))
        file.delete()
    }

    @Test
    fun `只上传预上传 block_list 点名的分片`() = runTest {
        val file = tempFile("0123456789") // 10 字节按 4 字节切 → 3 片
        // 文档（分片上传注意事项）：只上传预上传响应 block_list 指定的分片
        server.enqueue(response("""{"errno":0,"uploadid":"up-1","return_type":1,"block_list":[1]}"""))
        server.enqueue(response("""{"errno":0,"servers":[{"server":"${host()}"}]}"""))
        server.enqueue(response("""{"errno":0,"md5":"m1"}""")) // 只有 1 号片要传
        server.enqueue(response("""{"errno":0,"fs_id":9}"""))

        repository(chunkSize = 4, singleStepMaxSize = 0)
            .uploadFile(file, "ten.bin", "/apps/dinner/media")

        assertEquals("precreate + locate + 一片 + create", 4, server.requestCount)
        server.takeRequest() // precreate
        server.takeRequest() // locate
        val slice = server.takeRequest()
        assertTrue("只传点名的 1 号片：${slice.path}", slice.path!!.contains("partseq=1"))
        val create = server.takeRequest()
        assertTrue(create.path!!.contains("method=create"))
        val body = create.body.readUtf8()
        assertTrue("create 的 block_list 仍是全量 3 片", body.contains("block_list="))
        file.delete()
    }

    @Test
    fun `上传回 31064 说明父目录没了，自动重建目录后重传成功`() = runTest {
        val file = tempFile("lost-dir")
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":31064,"errmsg":"upload path error"}"""))
        // 重建 /apps/dinner
        server.enqueue(response("""{"errno":-9}"""))
        server.enqueue(response("""{"errno":0,"fs_id":1,"isdir":1}"""))
        // 重建 /apps/dinner/records
        server.enqueue(response("""{"errno":-9}"""))
        server.enqueue(response("""{"errno":0,"fs_id":2,"isdir":1}"""))
        // 重传
        server.enqueue(response("""{"error_code":0,"host":"${host()}"}"""))
        server.enqueue(response("""{"errno":0,"path":"/apps/dinner/records/lost.json"}"""))

        val remotePath = repository(singleStepMaxSize = 4L * 1024 * 1024)
            .uploadFile(file, "lost.json", "/apps/dinner/records")

        assertEquals("/apps/dinner/records/lost.json", remotePath)
        assertEquals(8, server.requestCount)
        file.delete()
    }

    @Test
    fun `云端还没有应用目录时 listAll 当作空列表，不报错`() = runTest {
        server.enqueue(response("""{"errno":31066,"errmsg":"file does not exist"}"""))

        assertEquals(emptyList<RemoteFile>(), repository().listAll())
        assertTrue(server.takeRequest().path!!.contains("method=listall"))
    }
}
