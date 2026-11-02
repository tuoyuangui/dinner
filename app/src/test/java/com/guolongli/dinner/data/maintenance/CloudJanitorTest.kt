package com.guolongli.dinner.data.maintenance

import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.testing.FakeTokenSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 云端整理的边界测试。
 *
 * 这一组用例真正要守住的是「别删活数据」，所以每个断言都写成
 * 「谁被删了 / 谁一个字没动」，而不是数一数总共删了几个。
 */
class CloudJanitorTest {

    private lateinit var server: MockWebServer

    /** 固定「现在」，让 30 天的截止线可以精确算出来，不依赖运行时刻 */
    private val nowMs = 1_800_000_000_000L

    /** 云端文件：路径 → (内容, 服务端修改时间秒) */
    private val cloud = linkedMapOf<String, Pair<String, Long>>()

    /** 被删掉的路径，按发生顺序 */
    private val deleted = mutableListOf<String>()

    /** 被真正下载过的文件，用来验证「新文件连下载都不该发生」 */
    private val downloaded = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = panDispatcher()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ 场景

    @Test
    fun `到期墓碑被清掉，活着的老记录一个字不动`() = runTest {
        val old = secondsAgo(40)
        put(RECORDS, "rec-tomb.json", doc("tomb", deleted = true), old)
        put(RECORDS, "rec-live.json", doc("live", deleted = false), old)
        put(MEDIA, "media-tomb-0.jpg", "photo", old)
        put(MEDIA, "media-live-0.jpg", "photo", old)

        val report = janitor().run()

        assertEquals("整理报错", null, report.error)
        assertEquals(1, report.tombs)
        assertEquals("活记录的附件不能删", 1, report.media)
        assertTrue(report.ok)
        assertTrue("墓碑该删：$deleted", deleted.contains("$RECORDS/rec-tomb.json"))
        assertTrue("活记录不该删", !deleted.contains("$RECORDS/rec-live.json"))
        assertTrue("活记录的附件不该删", !deleted.contains("$MEDIA/media-live-0.jpg"))
        assertTrue("墓碑对应附件该删", deleted.contains("$MEDIA/media-tomb-0.jpg"))
    }

    @Test
    fun `没到期的墓碑只读不删，附件也跟着留着`() = runTest {
        val fresh = secondsAgo(3)
        put(RECORDS, "rec-fresh-tomb.json", doc("fresh-tomb", deleted = true), fresh)
        put(MEDIA, "media-fresh-tomb-0.jpg", "photo", fresh)

        val report = janitor().run()

        assertEquals(0, report.tombs)
        assertEquals(0, report.media)
        assertTrue("刚删的记录还要给别的设备看墓碑，不能清：$deleted", deleted.isEmpty())
        assertTrue(
            "新文件连下载都该跳过：$downloaded",
            downloaded.none { it.contains("rec-fresh-tomb") },
        )
    }

    @Test
    fun `记录彻底不存在时，孤儿附件才删`() = runTest {
        val old = secondsAgo(60)
        put(RECORDS, "rec-live.json", doc("live", deleted = false), old)
        put(MEDIA, "media-gone-0.jpg", "orphan", old)
        put(MEDIA, "media-gone-1.mp4", "orphan", old)
        put(MEDIA, "media-live-1.jpg", "photo", old)

        val report = janitor().run()

        assertEquals("两份孤儿都要清", 2, report.media)
        assertEquals(0, report.tombs)
        assertTrue(deleted.contains("$MEDIA/media-gone-0.jpg"))
        assertTrue(deleted.contains("$MEDIA/media-gone-1.mp4"))
        assertFalse(deleted.contains("$MEDIA/media-live-1.jpg"))
    }

    @Test
    fun `修改时间拿不到的文件一律当新文件跳过`() = runTest {
        // server_mtime = 0 表示接口没给时间：宁可少清一个，也不要猜
        put(RECORDS, "rec-nomtime.json", doc("nomtime", deleted = true), 0L)
        put(MEDIA, "media-nomtime-0.jpg", "photo", 0L)

        val report = janitor().run()

        assertEquals(0, report.tombs)
        assertEquals(0, report.media)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `单次上限生效，剩下的留到下次`() = runTest {
        val old = secondsAgo(40)
        repeat(5) { index ->
            put(RECORDS, "rec-tomb-$index.json", doc("tomb-$index", deleted = true), old)
        }

        val report = janitor(maxDeletes = 2).run()

        assertEquals(2, report.tombs)
        assertTrue("超出上限要记账，别悄悄丢掉", report.deferred > 0)
        assertFalse(report.ok)
        assertTrue(report.summary().contains("下次"))
    }

    @Test
    fun `整理一次不算失败时，错误要带回上层`() = runTest {
        // 目录不存在 → list 返回空，语义上是「没有东西要整理」，不算错
        val report = janitor().run()
        assertTrue(report.ok)
        assertEquals(0, report.tombs + report.media)
        assertNull(report.error)
        assertTrue(report.summary().contains("没有需要整理"))
    }

    // ------------------------------------------------------------------ 工具

    private fun janitor(maxDeletes: Int = 40) = CloudJanitor(
        netdisk = { repository() },
        now = { nowMs },
        maxDeletes = maxDeletes,
    )

    private fun repository() = NetdiskRepository(
        auth = FakeTokenSource("tk"),
        api = NetdiskApi(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            panHost = host(),
            pcsHost = host(),
        ),
        retryTimes = { 1 },
        retryDelayMs = { 0L },
    )

    private fun host() = server.url("/").toString().trimEnd('/')

    private fun secondsAgo(days: Long) = nowMs / 1000 - days * 86_400L

    private fun put(dir: String, name: String, content: String, modifiedAt: Long) {
        cloud["$dir/$name"] = content to modifiedAt
    }

    private fun doc(id: String, deleted: Boolean) = JSONObject()
        .put("id", id)
        .put("authorId", "me")
        .put("type", "DINNER")
        .put("createdAt", 1_700_000_000_000L)
        .put("updatedAt", 1_700_000_000_000L)
        .put("deleted", deleted)
        .toString()

    private fun fsIdOf(path: String): Long =
        (cloud.keys.toList().indexOf(path) + 1).toLong()

    private fun listJson(dir: String): String {
        val array = JSONArray()
        cloud.filterKeys { it.substringBeforeLast('/') == dir }.forEach { (path, value) ->
            array.put(
                JSONObject()
                    .put("path", path)
                    .put("server_filename", path.substringAfterLast('/'))
                    .put("fs_id", fsIdOf(path))
                    .put("size", value.first.length)
                    .put("isdir", 0)
                    .put("server_mtime", value.second),
            )
        }
        return JSONObject().put("errno", 0).put("list", array).toString()
    }

    /**
     * 一个够用的网盘替身：list 按 dir 回对应目录、filemetas 回 dlink、delete 记账。
     * 用 Dispatcher 而不是按序 enqueue，是因为「谁先被下」不该成为断言的一部分。
     */
    private fun panDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return MockResponse().setResponseCode(400)

            if (url.encodedPath.startsWith("/dl/")) {
                val name = url.encodedPath.removePrefix("/dl/")
                val entry = cloud.entries.firstOrNull { it.key.endsWith("/$name") }
                    ?: return MockResponse().setResponseCode(404)
                downloaded += entry.key
                return MockResponse().setResponseCode(200).setBody(entry.value.first)
            }

            return when (url.queryParameter("method")) {
                "list" -> {
                    val dir = url.queryParameter("dir").orEmpty()
                    MockResponse().setResponseCode(200).setBody(listJson(dir))
                }

                "filemetas" -> {
                    val ids = url.queryParameter("fsids").orEmpty().trim('[', ']')
                    val id = ids.substringBefore(',').toLongOrNull() ?: 0L
                    val path = cloud.keys.toList().getOrNull((id - 1).toInt())
                    val info = JSONArray()
                    if (path != null) {
                        info.put(
                            JSONObject()
                                .put("fs_id", id)
                                .put("dlink", host() + "/dl/" + path.substringAfterLast('/')),
                        )
                    }
                    MockResponse().setResponseCode(200)
                        .setBody(JSONObject().put("errno", 0).put("info", info).toString())
                }

                "delete" -> {
                    // 表单体的 filelist 是 JSON 数组再整体 urlencode 的，得先解码
                    val raw = request.body.readUtf8().substringAfter("filelist=", "[]")
                    val json = URLDecoder.decode(raw, "UTF-8")
                    val list = JSONArray(json)
                    val paths = (0 until list.length()).map { list.optString(it) }
                    deleted += paths
                    paths.forEach { cloud.remove(it) }
                    MockResponse().setResponseCode(200)
                        .setBody("""{"errno":0,"request_id":1}""")
                }

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private companion object {
        const val RECORDS = "/apps/dinner/records"
        const val MEDIA = "/apps/dinner/media"
    }
}
