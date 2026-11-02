package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.testing.FakeLocalStore
import com.guolongli.dinner.testing.FakeRemoteStore
import com.guolongli.dinner.testing.draftRecord
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 推送（本地 → 网盘）的行为测试。
 * 关注的不是「能传上去」，而是出错时队列会怎么处理：重试、放弃、还是整批停下。
 */
class SyncEnginePushTest {

    private lateinit var local: FakeLocalStore
    private lateinit var remote: FakeRemoteStore
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        local = FakeLocalStore()
        remote = FakeRemoteStore()
    }

    private fun engine(maxAttempts: Int = 5, backoff: (Int) -> Long = { 1_000L shl (it - 1) }) =
        SyncEngine(
            local = local,
            remote = remote,
            now = { clock },
            maxAttempts = maxAttempts,
            backoffMs = backoff,
        )

    @Test
    fun `媒体先传、文档后传，文档里带的是上传后的远端路径`() = runTest {
        val doc = draftRecord("1")
        local.putDocument(doc, isDirty = true)
        local.enqueueOp("1")
        // 媒体必须真的存在：上传前会检查本地文件，文件不在的媒体会被丢掉
        // （见「本地文件已经不在的媒体不再卡住整条记录」那条测试）
        local.mediaQueues["1"] = mutableListOf(
            LocalMedia(0, tempFile("photo-0", "jpg")),
            LocalMedia(1, tempFile("clip-1", "mp4")),
        )

        val result = engine().push()

        assertEquals(1, result.pushed)
        assertEquals(0, result.failed)
        assertEquals(0, result.pending)

        // 顺序：先两段媒体，再一条文档
        val mediaCalls = remote.calls.filter { it.startsWith("uploadMedia") }
        val docCallIndex = remote.calls.indexOfFirst { it.startsWith("uploadDocument") }
        assertEquals(2, mediaCalls.size)
        assertTrue(
            "文档必须排在媒体之后，否则引用的是空路径：${remote.calls}",
            remote.calls.indexOfFirst { it.startsWith("uploadMedia") } < docCallIndex,
        )
        assertTrue("媒体文件名要能追溯记录与位置", mediaCalls[0].endsWith("media-1-0.jpg"))
        assertTrue(mediaCalls[1].endsWith("media-1-1.mp4"))

        // 文档内容里带上了远端路径
        val uploaded = remote.files.values.first().first
        assertTrue(uploaded.contains("/apps/dinner/media/media-1-0.jpg"))
        assertTrue(uploaded.contains("/apps/dinner/media/media-1-1.mp4"))

        // 发件箱清空，记录标成已同步
        assertTrue(local.ops.isEmpty())
        assertEquals("/apps/dinner/records/rec-1.json", local.syncedPaths["1"])
    }

    @Test
    fun `没有媒体时只发一条文档`() = runTest {
        local.putDocument(draftRecord("2"), isDirty = true)
        local.enqueueOp("2")

        engine().push()

        assertEquals(0, remote.mediaUploadCount)
        assertEquals(1, remote.uploadCount)
    }

    @Test
    fun `频控失败后进退避，下次时间没到不会重复打接口`() = runTest {
        local.putDocument(draftRecord("3"), isDirty = true)
        local.enqueueOp("3")
        remote.uploadError = NetdiskException(20012, "request too frequent")

        val first = engine(maxAttempts = 5).push()

        assertEquals(0, first.pushed)
        assertEquals(1, first.deferred)
        assertEquals(1, first.pending)
        assertEquals("队列还在，等下次重试", 1, local.ops.size)
        assertEquals("第 1 次失败后应该是 1 次", 1, local.ops.first().attempts)
        assertTrue("退避窗口要推到未来", local.ops.first().nextAttemptAt > clock)

        // 还没到时间：这轮跳过，不再打接口
        val callsBefore = remote.uploadCount
        val second = engine().push()
        assertEquals(callsBefore, remote.uploadCount)
        assertEquals(1, second.deferred)

        // 时间到了：重试成功
        clock = local.ops.first().nextAttemptAt
        remote.uploadError = null
        val third = engine().push()
        assertEquals(1, third.pushed)
        assertTrue(local.ops.isEmpty())
    }

    @Test
    fun `退避时间按 1s 2s 4s 递增`() = runTest {
        local.putDocument(draftRecord("4"), isDirty = true)
        local.enqueueOp("4")
        remote.uploadError = NetdiskException(20012, "busy")

        val engine = engine(maxAttempts = 10)
        val gaps = mutableListOf<Long>()
        repeat(3) {
            val before = clock
            engine.push()
            gaps += local.ops.first().nextAttemptAt - before
            clock = local.ops.first().nextAttemptAt
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L), gaps)
    }

    @Test
    fun `重试次数用尽后标记失败，不再无限重试`() = runTest {
        local.putDocument(draftRecord("5"), isDirty = true)
        local.enqueueOp("5")
        remote.uploadError = NetdiskException(20012, "busy")

        val engine = engine(maxAttempts = 2)
        engine.push()   // 第 1 次：退避
        clock += 10_000
        val second = engine.push()  // 第 2 次：用尽次数

        assertEquals(1, second.failed)
        assertTrue("队列要清掉，不能卡住后面的人", local.ops.isEmpty())
        assertNotNull("记录标 FAILED 供手动重试", local.failures["5"])
        assertNull(local.syncedPaths["5"])
    }

    @Test
    fun `鉴权失效立刻中止整批，剩下的留在队列里`() = runTest {
        local.putDocument(draftRecord("6"), isDirty = true)
        local.putDocument(draftRecord("7"), isDirty = true)
        local.enqueueOp("6")
        local.enqueueOp("7")
        remote.uploadError = NetdiskException(-6, "invalid access token")

        val result = engine().push()

        assertTrue(result.authRequired)
        assertEquals("只试了第一条就停", 1, remote.uploadCount)
        assertEquals("两条都还在队列里等授权", 2, local.ops.size)
        assertEquals(0, result.failed)
    }

    @Test
    fun `授权失效时不会把记录标成失败`() = runTest {
        local.putDocument(draftRecord("8"), isDirty = true)
        local.enqueueOp("8")
        remote.uploadError = NetdiskException(110, "token expired")

        engine().push()

        assertTrue("授权问题不该算记录本身失败", local.failures.isEmpty())
    }

    @Test
    fun `删除上传的是墓碑文档，不是把云端文件删掉`() = runTest {
        local.putDocument(draftRecord("9", deleted = true), isDirty = true)
        local.enqueueOp("9", kind = OpKind.DELETE_RECORD)
        local.syncedPaths["9"] = "/apps/dinner/records/rec-9.json"

        val result = engine().push()

        assertEquals(1, result.pushed)
        // 云端保留墓碑，别的设备才拉得到「这条被删了」
        assertEquals(emptyList<String>(), remote.deletedPaths)
        val uploaded = remote.files["/apps/dinner/records/rec-9.json"]?.first.orEmpty()
        assertTrue("墓碑文档要带 deleted = true：$uploaded", uploaded.contains("\"deleted\":true"))
    }

    @Test
    fun `删除时本地记录不存在，判失败而不是反复重试`() = runTest {
        local.enqueueOp("10", kind = OpKind.DELETE_RECORD)

        val result = engine().push()

        assertEquals(0, result.pushed)
        assertEquals(1, result.failed)
        assertTrue(local.failures.containsKey("10"))
    }

    @Test
    fun `一条失败不影响后面的记录`() = runTest {
        local.putDocument(draftRecord("11"), isDirty = true)
        local.putDocument(draftRecord("12"), isDirty = true)
        local.enqueueOp("11")
        local.enqueueOp("12")
        // 第一条文档上传报参数错（非瞬时），第二条正常
        var first = true
        val flaky = object : RemoteStore by remote {
            override suspend fun uploadDocument(doc: RecordDocument): String {
                if (first) {
                    first = false
                    throw NetdiskException(31064, "file too large")
                }
                return remote.uploadDocument(doc)
            }
        }
        val engine = SyncEngine(local, flaky, now = { clock }, maxAttempts = 3)

        val result = engine.push()

        assertEquals(1, result.pushed)
        assertEquals(1, result.failed)
        assertTrue("第二条要传上去", local.syncedPaths.containsKey("12"))
        assertNull(local.syncedPaths["11"])
    }

    @Test
    fun `手动重试会清掉退避窗口`() = runTest {
        local.putDocument(draftRecord("13"), isDirty = true)
        local.enqueueOp("13")
        local.ops[0] = local.ops[0].copy(
            attempts = 2,
            nextAttemptAt = clock + 60_000L,
        )

        val result = engine().retryNow("13")

        assertEquals(1, result.pushed)
        assertTrue(local.ops.isEmpty())
    }

    @Test
    fun `本地记录缺失时不会静默成功`() = runTest {
        local.enqueueOp("missing")

        val result = engine().push()

        assertEquals(0, result.pushed)
        assertEquals(1, result.failed)
        assertTrue(local.ops.isEmpty())
    }

    @Test
    fun `本地文件已经不在的媒体让整条记录标失败并保留`() = runTest {
        val doc = draftRecord("2")
        local.putDocument(doc, isDirty = true)
        local.enqueueOp("2")
        local.mediaQueues["2"] = mutableListOf(
            LocalMedia(0, "/sdcard/gone.jpg"),
            LocalMedia(1, tempFile("alive-1", "jpg")),
        )

        val result = engine().push()

        // 缺文件绝不能「静默丢掉这张照片」再继续传：那等于把用户记录悄悄改了，
        // 云端副本也会被覆盖成「无此图」。正确做法是整条记录标失败、原样保留，
        // 让用户重新添加（与 ref 的 SyncRepositoryImpl 一致）。
        assertEquals(0, result.pushed)
        assertEquals(1, result.failed)
        assertTrue("失败记录要从发件箱移除，避免无限重试", local.ops.isEmpty())
        // 没传那张还在的照片，也没传文档
        assertEquals(0, remote.calls.count { it.startsWith("uploadMedia") })
        assertNull(local.syncedPaths["2"])
    }

    @Test
    fun `空文件也按缺文件处理整条标失败`() = runTest {
        val doc = draftRecord("9")
        local.putDocument(doc, isDirty = true)
        local.enqueueOp("9")
        val empty = File.createTempFile("empty", ".jpg").apply { writeBytes(ByteArray(0)); deleteOnExit() }
        local.mediaQueues["9"] = mutableListOf(LocalMedia(0, empty.absolutePath))

        val result = engine().push()

        assertEquals(0, result.pushed)
        assertEquals(1, result.failed)
        assertTrue(local.ops.isEmpty())
    }

    private fun tempFile(prefix: String, ext: String): String =
        File.createTempFile(prefix, ".$ext").apply {
            writeBytes(ByteArray(8))
            deleteOnExit()
        }.absolutePath
}
