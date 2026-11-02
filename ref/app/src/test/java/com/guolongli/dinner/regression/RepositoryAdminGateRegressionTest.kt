package com.guolongli.dinner.regression

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.local.db.dao.MealRecordDao
import com.guolongli.dinner.data.local.db.entity.MealRecordEntity
import com.guolongli.dinner.data.repository.MealRepositoryImpl
import com.guolongli.dinner.data.mapper.toDomain
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.GroupConfigRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「后端仍必须 `requireAdminUnlocked()`」在数据层的落点（AGENTS 群组门禁）。
 *
 * 早先权限只在 ViewModel 判，仓储层完全不设防：任何绕过界面的调用路径
 * （深链、后台任务、将来新增的快捷入口）都能改 / 删别人的记录，
 * 管理员口令对「改别人的东西」于是完全不起作用。
 *
 * 这里固定三条：
 * 1. 写自己的记录：永远放行，普通成员不受影响；
 * 2. 改 / 删别人的记录：管理员但未解锁 → 拒绝并抛错；
 * 3. 改 / 删别人的记录：管理员且已解锁（且云端口令未变）→ 放行。
 */
class RepositoryAdminGateRegressionTest {

    private val myId = "13800138000"
    private val otherId = "13900139000"
    private val groupId = "group_$myId"

    private class RecordingDao(
        private val existing: List<MealRecordEntity>,
    ) : MealRecordDao {
        var softDeleteCalls = 0
        var upsertCalls = 0

        override suspend fun getById(id: String): MealRecordEntity? =
            existing.firstOrNull { it.id == id }

        override fun observeById(id: String): Flow<MealRecordEntity?> = flowOf(null)
        override fun observeVisible(): Flow<List<MealRecordEntity>> = flowOf(existing)
        override fun observeAll(): Flow<List<MealRecordEntity>> = flowOf(existing)
        override suspend fun countByState(state: String): Long = 0
        override suspend fun upsert(entity: MealRecordEntity) { upsertCalls += 1 }
        override suspend fun upsertAll(entities: List<MealRecordEntity>) = Unit
        override suspend fun updateSyncState(id: String, state: String, updatedAt: Long) = Unit
        override suspend fun updateMedia(id: String, mediaJson: String) = Unit
        override suspend fun setMarked(id: String, marked: Boolean) = Unit
        override suspend fun softDelete(id: String, deletedAt: Long) { softDeleteCalls += 1 }
        override suspend fun localRemove(id: String, removedAt: Long) = Unit
        override suspend fun clearLocalRemoved(id: String) = Unit
        override suspend fun clearLocalRemovedInRange(from: Long, to: Long): Int = 0
        override suspend fun hardDelete(id: String) = Unit
        override suspend fun hardDeleteAll(ids: List<String>) = Unit
        override suspend fun purgeCandidates(cutoff: Long): List<MealRecordEntity> = emptyList()
        override suspend fun rangeAll(from: Long, to: Long): List<MealRecordEntity> = emptyList()
        override suspend fun rewriteAuthor(from: String, to: String, now: Long): Int = 0
        override suspend fun recoverStuckSyncing(): Int = 0
        override suspend fun getAll(): List<MealRecordEntity> = existing
        override suspend fun countAll(): Long = existing.size.toLong()
        override suspend fun countMine(author: String): Long =
            existing.count { it.createdBy == author }.toLong()
        override suspend fun pendingUpload(states: List<String>, limit: Int): List<MealRecordEntity> =
            emptyList()
    }

    private fun entity(id: String, author: String) = MealRecordEntity(
        id = id,
        timestamp = 1,
        mealType = MealType.DEFAULT.name,
        note = "",
        createdBy = author,
        syncState = SyncState.SYNCED.name,
        updatedAt = 1,
        deletedAt = null,
        rating = null,
    )

    private fun pairing(adminId: String): PairingRepository = object : PairingRepository {
        private val state = GroupState(
            members = listOf(
                Member(id = myId, name = "我", joinedAt = 1),
                Member(id = otherId, name = "别人", joinedAt = 2),
            ),
            myId = myId,
            adminId = adminId,
        )
        override fun observe() = flowOf(state)
        override suspend fun current() = state
        override suspend fun upsertMember(member: Member) = Result.success(state)
        override suspend fun removeMember(id: String) = Result.success(state)
        override suspend fun replaceMembers(members: List<Member>) = Unit
        override suspend fun setAdmin(memberId: String) = Result.success(state)
        override suspend fun adoptRemoteAdmin(memberId: String?) = Unit
        override suspend fun migrateMemberId(fromId: String, toId: String) = Result.success(state)
        override suspend fun unbind() = Unit
    }

    /** 云端口令固定，用来验证「解锁后放行」。 */
    private fun groupConfig(password: String): GroupConfigRepository = object : GroupConfigRepository {
        override suspend fun read(groupId: String) = GroupConfig(
            groupId = groupId,
            adminPhone = myId,
            adminPassword = password,
            joinPassword = "J8k2Qm",
            adminId = myId,
        )
        override suspend fun write(config: GroupConfig) = Result.success(Unit)
        override suspend fun verifyJoinPassword(groupId: String, input: String) = Result.success(Unit)
        override suspend fun rotateJoinPassword(groupId: String) =
            Result.failure<String>(IllegalStateException("不测换码"))
        override suspend fun updateAdminPassword(groupId: String, newPassword: String) =
            Result.failure<GroupConfig>(IllegalStateException("不测改口令"))
    }

    private fun storage(): StorageConfigRepository = object : StorageConfigRepository {
        private var gid: String? = groupId
        override fun observePaths() = flowOf(NetdiskPaths(NetdiskPaths.DEFAULT_ROOT, gid))
        override suspend fun paths() = NetdiskPaths(NetdiskPaths.DEFAULT_ROOT, gid)
        override suspend fun setRoot(raw: String) = Result.success(NetdiskPaths())
        override suspend fun resetToDefault() = Unit
        override fun observeGroupId() = flowOf(gid)
        override suspend fun groupId(): String? = gid
        override suspend fun setGroupId(id: String?) { gid = id }
        override fun observeCompressMedia() = flowOf(false)
        override suspend fun compressMedia() = false
        override suspend fun setCompressMedia(enabled: Boolean) = Unit
        override fun observeRetentionDays() = flowOf(30)
        override suspend fun retentionDays() = 30
        override suspend fun setRetentionDays(days: Int) = Unit
        override fun observeNetworkRetries() = flowOf(1)
        override suspend fun networkRetries() = 1
        override suspend fun setNetworkRetries(times: Int) = Unit
    }

    private fun repo(
        dao: RecordingDao,
        adminId: String,
        session: AdminSession,
    ): MealRepositoryImpl = MealRepositoryImpl(
        dao,
        pairing(adminId),
        AdminGate(session, groupConfig("admin-pw"), storage()),
    )

    @Test
    fun `写自己的记录永远放行`() = runBlocking {
        val dao = RecordingDao(listOf(entity("mine", myId)))
        val repo = repo(dao, adminId = otherId, session = AdminSession())

        // 不是管理员、也没解锁，但写的是自己的记录：必须允许。
        repo.upsert(entity("mine", myId).toDomain())
        assertTrue(dao.upsertCalls > 0)

        repo.softDelete("mine", 1)
        assertTrue(dao.softDeleteCalls > 0)
    }

    @Test
    fun `管理员未解锁时改别人的记录被拒绝`() = runBlocking {
        val dao = RecordingDao(listOf(entity("others", otherId)))
        // 名单口径上是管理员，但本次运行没验过口令。
        val repo = repo(dao, adminId = myId, session = AdminSession())

        val result = runCatching {
            repo.softDelete("others", 1)
        }
        assertTrue("未解锁的管理员不得删别人的记录", result.isFailure)
        assertTrue(result.exceptionOrNull() is DinnerException.Validation)
        assertTrue(
            "错误必须点明要先去验口令",
            result.exceptionOrNull()!!.message!!.contains("管理员口令"),
        )
        // 一次都没真的删。
        assertFalse(dao.softDeleteCalls > 0)
    }

    @Test
    fun `普通成员既不能改也不能删别人的记录`() = runBlocking {
        val dao = RecordingDao(listOf(entity("others", otherId)))
        val repo = repo(dao, adminId = otherId, session = AdminSession())

        val del = runCatching { repo.softDelete("others", 1) }
        assertTrue(del.isFailure)
        assertTrue(dao.softDeleteCalls == 0)
    }

    @Test
    fun `管理员验过口令后可以删别人的记录`() = runBlocking {
        val dao = RecordingDao(listOf(entity("others", otherId)))
        val session = AdminSession()
        // 与云端口令一致才算真的解锁。
        session.unlock(groupId, myId, "admin-pw")
        val repo = repo(dao, adminId = myId, session = session)

        repo.softDelete("others", 1)
        assertTrue("已解锁的管理员应能删别人的记录", dao.softDeleteCalls > 0)
    }

    @Test
    fun `云端口令被换掉后本机解锁态立刻失效`() = runBlocking {
        val dao = RecordingDao(listOf(entity("others", otherId)))
        val session = AdminSession()
        // 本机记的是旧口令。
        session.unlock(groupId, myId, "old-pw")
        val repo = repo(dao, adminId = myId, session = session)

        val result = runCatching { repo.softDelete("others", 1) }
        assertTrue("云端口令已变，旧解锁态必须作废", result.isFailure)
        assertTrue(dao.softDeleteCalls == 0)
        // 作废后内存里的解锁态也被清掉。
        assertFalse(session.isUnlocked(groupId))
    }

    @Test
    fun `新记录（库里没有）不受管理员门禁影响`() = runBlocking {
        val dao = RecordingDao(emptyList())
        val repo = repo(dao, adminId = otherId, session = AdminSession())

        repo.upsert(entity("brand-new", myId).toDomain())
        assertTrue("新记录应当能写", dao.upsertCalls > 0)
    }
}
