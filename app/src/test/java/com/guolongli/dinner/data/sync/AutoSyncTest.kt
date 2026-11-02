package com.guolongli.dinner.data.sync

import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.testing.FakeLocalStore
import com.guolongli.dinner.testing.FakeRemoteStore
import com.guolongli.dinner.testing.draftRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动同步的「什么时候跑、跑几次、结果记在哪」——这三件事最容易写错，
 * 而且错了不会报错，只会悄悄多传或少传，所以单独测。
 */
class AutoSyncTest {

    private class MemoryPrefs(
        override var autoEnabled: Boolean = true,
        override var intervalMinutes: Int = 30,
        override var wifiOnly: Boolean = true,
        override var lastSyncAt: Long = 0L,
        override var lastSummary: String = "",
        override var lastError: String = "",
        override var lastAuthRequired: Boolean = false,
        override var lastMediaRestoreAt: Long = 0L,
    ) : SyncPrefs

    private var clock = 1_000_000L

    private fun build(
        prefs: MemoryPrefs = MemoryPrefs(),
        local: FakeLocalStore = FakeLocalStore(),
        remote: FakeRemoteStore = FakeRemoteStore(),
        authorized: Boolean = true,
        onChange: () -> Unit = {},
    ): Triple<AutoSync, FakeLocalStore, FakeRemoteStore> {
        val engine = SyncEngine(local, remote, now = { clock })
        val auto = AutoSync(
            engine = engine,
            prefs = prefs,
            authorized = { authorized },
            now = { clock },
            onChange = onChange,
            minGapMs = 5_000L,
            freshMs = 60_000L,
        )
        return Triple(auto, local, remote)
    }

    @Test
    fun `关掉自动同步后自动触发不碰网盘`() = runTest {
        val prefs = MemoryPrefs(autoEnabled = false)
        val (auto, _, remote) = build(prefs)

        val report = auto.run(SyncReason.DATA_CHANGED)

        assertNull(report)
        assertTrue("不该发出任何请求", remote.calls.isEmpty())
    }

    @Test
    fun `手动同步无视开关`() = runTest {
        val prefs = MemoryPrefs(autoEnabled = false)
        val (auto, _, remote) = build(prefs)

        val report = auto.run(SyncReason.MANUAL)

        assertTrue(report != null)
        assertEquals(listOf("listDocuments"), remote.calls)
    }

    @Test
    fun `两次自动同步之间有限流`() = runTest {
        val prefs = MemoryPrefs()
        val (auto, _, remote) = build(prefs)

        auto.run(SyncReason.DATA_CHANGED)
        val second = auto.run(SyncReason.DATA_CHANGED)

        assertNull("5 秒内不该再来一次", second)
        assertEquals(1, remote.calls.count { it == "listDocuments" })

        // 过了限流窗口就能跑
        clock += 6_000L
        assertTrue(auto.run(SyncReason.DATA_CHANGED) != null)
        assertEquals(2, remote.calls.count { it == "listDocuments" })
    }

    @Test
    fun `刚同步过时回到前台不重复拉`() = runTest {
        val prefs = MemoryPrefs()
        val (auto, _, remote) = build(prefs)

        auto.run(SyncReason.MANUAL)
        clock += 10_000L // 超过限流，但还在「刚同步过」的 1 分钟窗口内

        assertNull(auto.run(SyncReason.STARTUP))
        assertEquals(1, remote.calls.count { it == "listDocuments" })
    }

    @Test
    fun `未授权时直接跳过`() = runTest {
        val (auto, _, remote) = build(authorized = false)

        assertNull(auto.run(SyncReason.DATA_CHANGED, force = true))
        assertTrue(remote.calls.isEmpty())
        assertFalse(auto.state.value.authorized)
    }

    @Test
    fun `同步结果写回设置并通知调度器`() = runTest {
        val prefs = MemoryPrefs()
        val local = FakeLocalStore()
        local.putDocument(draftRecord("rec-1", updatedAt = 2_000L), isDirty = true)
        local.enqueueOp("rec-1", OpKind.UPSERT_RECORD)

        var notified = 0
        val (auto, _, remote) = build(prefs, local) { notified++ }

        val report = auto.run(SyncReason.MANUAL)

        assertEquals(1, report?.pushed)
        assertEquals(remote.calls.count { it == "uploadDocument:rec-1" }, 1)
        assertEquals("上去 1 条 · 下来 0 条", prefs.lastSummary)
        assertEquals(clock, prefs.lastSyncAt)
        assertTrue(prefs.lastError.isEmpty())
        assertEquals(1, notified)
        assertEquals("上去 1 条 · 下来 0 条", auto.state.value.summary)
        assertFalse(auto.state.value.running)
    }

    @Test
    fun `授权失效会记住并写进状态`() = runTest {
        val prefs = MemoryPrefs()
        val remote = FakeRemoteStore().apply {
            listError = NetdiskException(-6, "用户未登录")
        }
        val (auto, _, _) = build(prefs, remote = remote)

        val report = auto.run(SyncReason.PERIODIC)

        assertTrue(report!!.authRequired)
        assertTrue(prefs.lastAuthRequired)
        assertFalse(auto.state.value.authorized)
        assertEquals("授权已失效，请重新授权", prefs.lastSummary)
    }

    @Test
    fun `上传失败会留在状态里供界面展示`() = runTest {
        val prefs = MemoryPrefs()
        val local = FakeLocalStore()
        local.putDocument(draftRecord("rec-2"), isDirty = true)
        local.enqueueOp("rec-2", OpKind.UPSERT_RECORD)
        val remote = FakeRemoteStore().apply {
            uploadError = NetdiskException(105, "文件不存在")
        }
        val (auto, _, _) = build(prefs, local, remote)

        auto.run(SyncReason.MANUAL)

        assertEquals(1, auto.state.value.errors.size)
        assertTrue(prefs.lastError.contains("文件不存在"))
        assertTrue("有错误但授权没问题时，状态不该显示成未授权", auto.state.value.authorized)
    }
}
