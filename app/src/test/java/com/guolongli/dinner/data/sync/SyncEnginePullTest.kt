package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.testing.FakeLocalStore
import com.guolongli.dinner.testing.FakeRemoteStore
import com.guolongli.dinner.testing.draftRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 拉取（网盘 → 本地）的行为测试。
 * 这里最值钱的一条是「本地更新就别下载」——不分大小每次全量下载，接口配额撑不过一天。
 */
class SyncEnginePullTest {

    private lateinit var local: FakeLocalStore
    private lateinit var remote: FakeRemoteStore
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        local = FakeLocalStore()
        remote = FakeRemoteStore()
    }

    private fun engine() = SyncEngine(local, remote, now = { clock })

    @Test
    fun `远端有新记录就收下来`() = runTest {
        remote.putDocument(draftRecord("a", updatedAt = 5_000L, note = "妈妈做的红烧肉"))

        val result = engine().pull()

        assertEquals(1, result.applied)
        assertEquals(0, result.stale)
        assertEquals(1, remote.downloadCount)
        val stored = local.documents["a"]
        assertTrue("内容要落地", stored!!.note == "妈妈做的红烧肉")
        assertEquals("/apps/dinner/records/rec-a.json", local.syncedPaths["a"])
    }

    @Test
    fun `本地更新时连下载都不做`() = runTest {
        local.putDocument(draftRecord("b", updatedAt = 9_000L, note = "本地改过"), isDirty = true)
        // 服务端 mtime 秒 = 8，本地 9000ms 也大于它
        remote.putDocument(draftRecord("b", updatedAt = 3_000L, note = "远端旧版"), modifiedAtSeconds = 8)

        val result = engine().pull()

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        assertEquals("省掉一次下载才算做对", 0, remote.downloadCount)
        assertEquals("本地内容不能被覆盖", "本地改过", local.documents["b"]!!.note)
    }

    @Test
    fun `远端更新就覆盖本地`() = runTest {
        local.putDocument(draftRecord("c", updatedAt = 1_000L, note = "旧"), isDirty = false)
        remote.putDocument(draftRecord("c", updatedAt = 2_000L, note = "新"), modifiedAtSeconds = 2)

        val result = engine().pull()

        assertEquals(1, result.applied)
        assertEquals("新", local.documents["c"]!!.note)
    }

    @Test
    fun `时间一样且本地干净时只算跳过，不算应用`() = runTest {
        local.putDocument(draftRecord("d", updatedAt = 4_000L), isDirty = false)
        remote.putDocument(draftRecord("d", updatedAt = 4_000L), modifiedAtSeconds = 4)

        val result = engine().pull()

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        assertEquals(1, remote.downloadCount)
    }

    @Test
    fun `时间一样但本地还有待传改动时保留本地`() = runTest {
        local.putDocument(draftRecord("e", updatedAt = 4_000L, note = "本地"), isDirty = true)
        remote.putDocument(draftRecord("e", updatedAt = 4_000L, note = "远端"), modifiedAtSeconds = 4)

        val result = engine().pull()

        assertEquals(1, result.stale)
        assertEquals("本地", local.documents["e"]!!.note)
    }

    @Test
    fun `墓碑能传开，删掉的记录不会被旧版本复活`() = runTest {
        local.putDocument(draftRecord("f", updatedAt = 1_000L), isDirty = false)
        remote.putDocument(draftRecord("f", updatedAt = 6_000L, deleted = true), modifiedAtSeconds = 6)

        val result = engine().pull()

        assertEquals(1, result.applied)
        assertTrue("远端已经删了，本地也要标记删除", local.documents["f"]!!.deleted)
    }

    @Test
    fun `本地删得更晚就不理会远端的旧版本`() = runTest {
        local.putDocument(draftRecord("g", updatedAt = 9_000L, deleted = true), isDirty = true)
        remote.putDocument(draftRecord("g", updatedAt = 2_000L, deleted = false), modifiedAtSeconds = 2)

        val result = engine().pull()

        assertEquals(0, result.applied)
        assertTrue(local.documents["g"]!!.deleted)
    }

    @Test
    fun `坏文件跳过，不影响其它记录`() = runTest {
        remote.files["/apps/dinner/records/rec-broken.json"] = "{ not json" to 1
        remote.putDocument(draftRecord("h", updatedAt = 5_000L), modifiedAtSeconds = 5)

        val result = engine().pull()

        assertEquals(1, result.applied)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `不是记录文件的一律忽略`() = runTest {
        remote.files["/apps/dinner/records/note.txt"] = "随便写的" to 1
        remote.files["/apps/dinner/records/rec-ok.json"] =
            draftRecord("ok", updatedAt = 1L).toJson() to 1

        val result = engine().pull()

        assertEquals(1, result.applied)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `列举失败时给出鉴权标记与错误信息`() = runTest {
        remote.listError = NetdiskException(-6, "invalid access token")

        val result = engine().pull()

        assertTrue(result.authRequired)
        assertEquals(0, result.applied)
        assertFalse(result.errors.isEmpty())
    }

    @Test
    fun `单个文件下载失败只记一笔，不中断整轮`() = runTest {
        remote.putDocument(draftRecord("i", updatedAt = 5_000L), modifiedAtSeconds = 5)
        remote.downloadError = NetdiskException(20012, "too frequent")

        val result = engine().pull()

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        // 频控会翻译成给人看的一句话，不再是原始 errno
        assertTrue(
            "错误里要有可读的原因：${result.errors}",
            result.errors.first().contains("频繁") || result.errors.first().contains("稍后重试"),
        )
    }

    @Test
    fun `同步是先拉后推`() = runTest {
        local.putDocument(draftRecord("j", updatedAt = 9_000L), isDirty = true)
        local.enqueueOp("j")
        remote.putDocument(draftRecord("k", updatedAt = 5_000L), modifiedAtSeconds = 5)

        val report = engine().sync()

        assertEquals(1, report.pulled)
        assertEquals(1, report.pushed)
        assertEquals(0, report.pending)
        assertTrue(report.ok)

        val order = remote.calls.filter {
            it.startsWith("listDocuments") || it.startsWith("downloadDocument") || it.startsWith("uploadDocument")
        }
        assertEquals(
            listOf("listDocuments", "downloadDocument:/apps/dinner/records/rec-k.json", "uploadDocument:j"),
            order,
        )
    }

    @Test
    fun `授权失效时同步不做推送`() = runTest {
        local.putDocument(draftRecord("l", updatedAt = 9_000L), isDirty = true)
        local.enqueueOp("l")
        remote.listError = NetdiskException(-6, "invalid access token")

        val report = engine().sync()

        assertTrue(report.authRequired)
        assertEquals(0, report.pushed)
        assertEquals("记录还在队列里等授权", 1, local.ops.size)
        assertEquals(0, remote.uploadCount)
    }

    @Test
    fun `同步报告能拼出给人看的一句话`() = runTest {
        remote.putDocument(draftRecord("m", updatedAt = 5_000L), modifiedAtSeconds = 5)
        val report = engine().sync()

        assertEquals(1, report.pulled)
        assertTrue(report.summary().contains("下来 1 条"))
        assertNull(report.errors.firstOrNull())
    }
}
