package com.guolongli.dinner.live

import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.sync.NetdiskRemoteStore
import com.guolongli.dinner.data.sync.RecordDocument
import com.guolongli.dinner.data.sync.SyncEngine
import com.guolongli.dinner.testing.FakeLocalStore
import com.guolongli.dinner.testing.draftRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 端到端同步：两「台」设备通过真实网盘交换记录。
 *
 * 本地库用内存实现（Room 的 SQL 在 JVM 上跑不了），远端就是真的百度网盘，
 * 所以它验证的是**真网络下的整条链路**：token → 建目录 → 上传文档 → 别人列表 → 下载 → 合并。
 */
class LiveSyncTest {

    private fun requireToken() {
        assumeTrue("还没有 token，先跑 DeviceCodeAuthTest", LiveEnv.hasToken())
    }

    @Test
    fun `两台设备经网盘同步一条记录，并传播删除`() = runBlocking {
        requireToken()

        val repository = LiveEnv.repository()
        val remote = NetdiskRemoteStore(repository)
        val id = "live-sync-${System.currentTimeMillis()}"
        val remotePath = "/apps/dinner/records/${RecordDocument.fileName(id)}"

        // ---------- 设备 A：新增一条并同步 ----------
        val deviceA = FakeLocalStore()
        val docA = draftRecord(id, updatedAt = System.currentTimeMillis() - 10_000L, note = "联调：红烧排骨")
        deviceA.putDocument(docA, isDirty = true)
        deviceA.enqueueOp(id, OpKind.UPSERT_RECORD)

        val reportA = SyncEngine(deviceA, remote).sync()
        println("[sync] A 推送 pushed=${reportA.pushed} pending=${reportA.pending} errors=${reportA.errors}")
        assertTrue("A 应该推上去 1 条", reportA.pushed >= 1)
        assertEquals(0, reportA.pending)
        assertTrue("云端应该有这条记录", repository.listAll().any { it.path == remotePath })

        // ---------- 设备 B：空库，同步后应该拉到 A 的记录 ----------
        val deviceB = FakeLocalStore()
        val reportB = SyncEngine(deviceB, remote).sync()
        println("[sync] B 拉取 pulled=${reportB.pulled} skipped=${reportB.skipped}")
        val pulledB = deviceB.documents[id]
        assertNotNull("B 应该拉到 A 的记录", pulledB)
        assertEquals("联调：红烧排骨", pulledB!!.note)
        assertEquals(5, pulledB.rating)

        // ---------- 设备 A：删除（墓碑），再同步 ----------
        deviceA.putDocument(
            pulledB.copy(deleted = true, updatedAt = System.currentTimeMillis()),
            isDirty = true,
        )
        deviceA.enqueueOp(id, OpKind.DELETE_RECORD)
        val reportA2 = SyncEngine(deviceA, remote).sync()
        println("[sync] A 删除 pushed=${reportA2.pushed} errors=${reportA2.errors}")
        assertTrue("删除也应该推成功", reportA2.pushed >= 1)

        // ---------- 设备 B：再同步，应该看到删除标记 ----------
        val reportB2 = SyncEngine(deviceB, remote).sync()
        println("[sync] B 第二次 pulled=${reportB2.pulled}")
        val afterDelete = deviceB.documents[id]
        assertNotNull(afterDelete)
        assertTrue("B 应该收到墓碑，把本地这条标成已删除", afterDelete!!.deleted)

        // ---------- 清理：把联调用的文件从网盘硬删掉 ----------
        runCatching { repository.delete(remotePath) }
            .onSuccess { println("[sync] 已清理 $remotePath") }
            .onFailure { println("[sync] 清理失败（可忽略）：${it.message}") }
        assertTrue("清理后列表里不该再有它", repository.listAll().none { it.path == remotePath })
    }
}
