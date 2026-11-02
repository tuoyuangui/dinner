package com.guolongli.dinner.regression

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.group.CloudTextFile
import com.guolongli.dinner.data.group.GroupConfigRepositoryImpl
import com.guolongli.dinner.data.group.PasswdDto
import com.guolongli.dinner.data.group.toDomain
import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `passwd.json` 的两条硬约束（AGENTS「群组门禁」）：
 *
 * 1. **写后必须回读比对**。百度上传是分片 + create 两步，任一步失败都可能留下
 *    「本地以为写成功、云端还是旧值」的状态。口令是一次承诺，云端不一致就必须抛错。
 * 2. **校验前必须先读云端**。管理员在另一台设备换了入群密码，本机拿旧值放行，
 *    等于那道门不存在。
 *
 * 这里用一个可以摆布「云端那份到底是什�么」的假网盘来固定这两条语义，
 * 不依赖真实百度网盘，也不依赖任何网络。
 */
class PasswdWriteBackRegressionTest {

    private val groupId = "group_13800138000"

    /**
     * 假网盘：只保留一份内容。
     *
     * [tamperOnWrite] 用来模拟「上传接口回成功、但云端落下的不是这份」——
     * 这正是写后回读要抓的失败。
     */
    private class FakeCloud(
        private val tamperOnWrite: ((String) -> String)? = null,
    ) : CloudTextFile {
        var content: String? = null
        var writeCount = 0
        var readCount = 0

        override fun parentOf(path: String): String = path.substringBeforeLast('/')

        override suspend fun read(path: String): String? {
            readCount += 1
            return content
        }

        override suspend fun write(path: String, content: String) {
            writeCount += 1
            this.content = tamperOnWrite?.invoke(content) ?: content
        }
    }

    private class FakeStorage(var gid: String?) : StorageConfigRepository {
        private var root: String = NetdiskPaths.DEFAULT_ROOT
        override fun observePaths(): Flow<NetdiskPaths> = flowOf(NetdiskPaths(root, gid))
        override suspend fun paths(): NetdiskPaths = NetdiskPaths(root, gid)
        override suspend fun setRoot(raw: String): Result<NetdiskPaths> {
            root = raw
            return Result.success(NetdiskPaths(root, gid))
        }
        override suspend fun resetToDefault() { root = NetdiskPaths.DEFAULT_ROOT }
        override fun observeGroupId(): Flow<String?> = flowOf(gid)
        override suspend fun groupId(): String? = gid
        override suspend fun setGroupId(id: String?) { gid = id }
        override fun observeCompressMedia(): Flow<Boolean> = flowOf(false)
        override suspend fun compressMedia(): Boolean = false
        override suspend fun setCompressMedia(enabled: Boolean) = Unit
        override fun observeRetentionDays(): Flow<Int> = flowOf(30)
        override suspend fun retentionDays(): Int = 30
        override suspend fun setRetentionDays(days: Int) = Unit
        override fun observeNetworkRetries(): Flow<Int> = flowOf(1)
        override suspend fun networkRetries(): Int = 1
        override suspend fun setNetworkRetries(times: Int) = Unit
    }

    private fun repository(cloud: FakeCloud): GroupConfigRepositoryImpl =
        GroupConfigRepositoryImpl(cloud, FakeStorage(groupId))

    private fun config(
        adminPassword: String = "admin-口令",
        joinPassword: String = "J8k2Qm",
    ) = GroupConfig(
        groupId = groupId,
        adminPhone = "13800138000",
        adminPassword = adminPassword,
        joinPassword = joinPassword,
        adminId = "13800138000",
        createdAt = 1_000L,
    )

    @Test
    fun `写入成功时云端内容与本次一致`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)

        val result = repo.write(config())
        assertTrue(result.isSuccess)

        // 写后回读：云端那份必须真的是这次写进去的内容。
        val readBack = repo.read(groupId)
        assertTrue(readBack != null)
        assertTrue(readBack!!.matchesAdminPassword("admin-口令"))
        assertTrue(readBack.matchesJoinPassword("J8k2Qm"))
        assertEquals(1, cloud.writeCount)
    }

    @Test
    fun `上传接口回成功但云端落的是旧值时必须抛错`() = runBlocking {
        // join_password 被悄悄写回旧值：这正是「本地以为写成功」的现场。
        val cloud = FakeCloud(tamperOnWrite = { text ->
            text.replace("\"join_password\":\"J8k2Qm\"", "\"join_password\":\"OLD-PW\"")
        })
        val repo = repository(cloud)

        val result = repo.write(config(joinPassword = "J8k2Qm"))
        assertTrue("回读不一致必须失败，不能假装写成功", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is DinnerException.Local)
        assertTrue(
            "错误必须说明是回读不一致，而不是含糊的失败",
            error!!.message!!.contains("不一致"),
        )
    }

    @Test
    fun `管理员口令被云端改掉时同样必须抛错`() = runBlocking {
        val cloud = FakeCloud(tamperOnWrite = { text ->
            text.replace("\"admin_password\":\"admin-口令\"", "\"admin_password\":\"SOMEONE-ELSE\"")
        })
        val repo = repository(cloud)

        val result = repo.write(config())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DinnerException.Local)
    }

    @Test
    fun `云端文件不存在时读出 null，而不是当成读失败`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)

        // 还没建组：目录里确实没有 passwd.json。
        val read = repo.read(groupId)
        assertNull(read)
        // 但 write 之后就能读到。
        assertTrue(repo.write(config()).isSuccess)
        assertTrue(repo.read(groupId) != null)
    }

    @Test
    fun `空口令写不进去`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)

        val noAdmin = repo.write(config(adminPassword = ""))
        assertTrue(noAdmin.isFailure)
        val noJoin = repo.write(config(joinPassword = ""))
        assertTrue(noJoin.isFailure)
        // 一次都没真的写出去。
        assertEquals(0, cloud.writeCount)
    }

    @Test
    fun `换入群密码必须先从云端读一次，再返回云端那份`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)
        assertTrue(repo.write(config(joinPassword = "OLD-PW")).isSuccess)

        val before = cloud.readCount
        val rotated = repo.rotateJoinPassword(groupId)
        assertTrue(rotated.isSuccess)
        val newPassword = rotated.getOrThrow()

        // rotateJoinPassword 的语义：先 read 云端取基线 -> 生成 -> 写 -> 再 read 回读。
        assertTrue("必须至少读过一次云端（取基线 + 回读）", cloud.readCount > before)
        // 返回的是云端读回来的那份，而不是本地生成后没落地的值。
        val fromCloud = repo.read(groupId)!!
        assertTrue(fromCloud.matchesJoinPassword(newPassword))
        // 旧密码立刻作废。
        assertTrue(!fromCloud.matchesJoinPassword("OLD-PW"))
    }

    @Test
    fun `校验入群密码只认云端 join_password`() = runBlocking {
        val cloud = FakeCloud(tamperOnWrite = null)
        val repo = repository(cloud)
        assertTrue(repo.write(config(adminPassword = "admin-口令", joinPassword = "J8k2Qm")).isSuccess)

        // 正确入群密码：通过。
        assertTrue(repo.verifyJoinPassword(groupId, "J8k2Qm").isSuccess)
        // 拿管理员口令当入群密码：必须被拒（两道门各管一件事）。
        assertTrue(repo.verifyJoinPassword(groupId, "admin-口令").isFailure)
        // 错的入群密码：失败且原因明确。
        val wrong = repo.verifyJoinPassword(groupId, "wrong")
        assertTrue(wrong.isFailure)
        assertTrue(wrong.exceptionOrNull()!!.message!!.contains("密码"))
    }

    @Test
    fun `云端没有口令文件时校验入群密码必须失败而不是放行`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)
        val result = repo.verifyJoinPassword(groupId, "anything")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DinnerException.Validation)
    }

    @Test
    fun `口令在云端被换掉后 old 值不再通过`() = runBlocking {
        val cloud = FakeCloud()
        val repo = repository(cloud)
        assertTrue(repo.write(config(joinPassword = "OLD-PW")).isSuccess)
        assertTrue(repo.verifyJoinPassword(groupId, "OLD-PW").isSuccess)

        // 另一台设备换了密码：云端那份变成 NEW-PW。
        cloud.content = cloud.content!!.replace("\"join_password\":\"OLD-PW\"", "\"join_password\":\"NEW-PW\"")
        // 本机没有缓存可用，必须重新读云端才判得出。
        assertTrue(repo.verifyJoinPassword(groupId, "OLD-PW").isFailure)
        assertTrue(repo.verifyJoinPassword(groupId, "NEW-PW").isSuccess)
    }

    @Test
    fun `落盘结构是明文口令，且能原样反解`() {
        val encoded = PasswdDto(
            groupId = groupId,
            adminPhone = "13800138000",
            adminPassword = "admin-口令",
            joinPassword = "J8k2Qm",
            adminId = "13800138000",
        ).toDomain(groupId)
        // 明文比较必须成立（这就是「禁改哈希」的反证）。
        assertTrue(encoded.matchesAdminPassword("admin-口令"))
        assertTrue(encoded.matchesJoinPassword("J8k2Qm"))
        assertTrue(!encoded.matchesAdminPassword("任意其他口令"))
    }
}
