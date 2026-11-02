package com.guolongli.dinner.data.repository

import com.guolongli.dinner.data.local.db.dao.MealRecordDao
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.data.local.db.entity.MealRecordEntity
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 FAILED 记录在同步队列中的隔离（红线）：
 * - 自动同步（includeFailed=false）只取 PENDING，不得把 FAILED 反复拉起重试；
 * - 手动同步（includeFailed=true）才纳入 FAILED，供用户显式重试。
 *
 * 通过 fake DAO 记录「待上传队列按哪些状态查询」，断言 states 拼接正确。
 */
class MealRepositoryPendingUploadTest {

    /** 把查询时传入的 states 原样记下来，逐项锁定查询语义。 */
    private class RecordingDao : MealRecordDao {
        var lastStates: List<String> = emptyList()
        var lastLimit: Int = 0

        override suspend fun pendingUpload(states: List<String>, limit: Int): List<MealRecordEntity> {
            lastStates = states
            lastLimit = limit
            return listOf(
                MealRecordEntity(
                    id = "pending-1",
                    timestamp = 1,
                    mealType = MealType.DEFAULT.name,
                    note = "",
                    createdBy = "dev-test-01",
                    syncState = SyncState.PENDING.name,
                    updatedAt = 1,
                    deletedAt = null,
                    rating = null,
                )
            )
        }

        override suspend fun getById(id: String): MealRecordEntity? = null
        override fun observeById(id: String): Flow<MealRecordEntity?> = flowOf(null)
        override fun observeVisible(): Flow<List<MealRecordEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<MealRecordEntity>> = flowOf(emptyList())
        override suspend fun countByState(state: String): Long = 0
        override suspend fun upsert(entity: MealRecordEntity) = Unit
        override suspend fun upsertAll(entities: List<MealRecordEntity>) = Unit
        override suspend fun updateSyncState(id: String, state: String, updatedAt: Long) = Unit
        override suspend fun updateMedia(id: String, mediaJson: String) = Unit
        override suspend fun setMarked(id: String, marked: Boolean) = Unit
        override suspend fun softDelete(id: String, deletedAt: Long) = Unit
        override suspend fun localRemove(id: String, removedAt: Long) = Unit
        override suspend fun clearLocalRemoved(id: String) = Unit
        override suspend fun clearLocalRemovedInRange(from: Long, to: Long): Int = 0
        override suspend fun hardDelete(id: String) = Unit
        override suspend fun hardDeleteAll(ids: List<String>) = Unit
        override suspend fun purgeCandidates(cutoff: Long): List<MealRecordEntity> = emptyList()
        override suspend fun rangeAll(from: Long, to: Long): List<MealRecordEntity> = emptyList()
        override suspend fun rewriteAuthor(from: String, to: String, now: Long): Int = 0
        override suspend fun recoverStuckSyncing(): Int = 0
        override suspend fun getAll(): List<MealRecordEntity> = emptyList()
        override suspend fun countAll(): Long = 0
        override suspend fun countMine(author: String): Long = 0
    }

    private fun entity(id: String, state: SyncState) = MealRecordEntity(
        id = id,
        timestamp = System.currentTimeMillis(),
        mealType = MealType.DEFAULT.name,
        note = "",
        createdBy = "dev-test-01",
        syncState = state.name,
        updatedAt = 1,
        deletedAt = null,
        rating = null,
    )

    @Test
    fun `自动同步只查 PENDING，不含 FAILED`() = runBlocking {
        val dao = RecordingDao()
        val repo = MealRepositoryImpl(dao, FakePairing(), AdminGate(AdminSession(), FakeGroupConfig(), FakeStorage()))

        repo.pendingUpload(limit = 10, includeFailed = false)

        assertEquals(listOf(SyncState.PENDING.name), dao.lastStates)
        assertEquals(10, dao.lastLimit)
    }

    @Test
    fun `手动同步查 PENDING 与 FAILED`() = runBlocking {
        val dao = RecordingDao()
        val repo = MealRepositoryImpl(dao, FakePairing(), AdminGate(AdminSession(), FakeGroupConfig(), FakeStorage()))

        repo.pendingUpload(limit = 10, includeFailed = true)

        assertEquals(
            listOf(SyncState.PENDING.name, SyncState.FAILED.name),
            dao.lastStates,
        )
    }

    @Test
    fun `SYNCING 或 SYNCED 永不进入待上传队列`() = runBlocking {
        val dao = RecordingDao()
        val repo = MealRepositoryImpl(dao, FakePairing(), AdminGate(AdminSession(), FakeGroupConfig(), FakeStorage()))

        repo.pendingUpload(limit = 10, includeFailed = true)

        assertTrue(
            "SYNCING/SYNCED 不得被查询为待上传",
            dao.lastStates.none { it in listOf(SyncState.SYNCING.name, SyncState.SYNCED.name) },
        )
    }
}

/** 名单只含「我」，权限判定用不到解锁态——本文件只测待上传队列的查询语义。 */
private class FakePairing : com.guolongli.dinner.domain.repository.PairingRepository {
    private val state = com.guolongli.dinner.domain.model.GroupState(
        members = listOf(com.guolongli.dinner.domain.model.Member(id = "dev-test-01", name = "我", joinedAt = 1)),
        myId = "dev-test-01",
    )
    override fun observe() = kotlinx.coroutines.flow.flowOf(state)
    override suspend fun current() = state
    override suspend fun upsertMember(member: com.guolongli.dinner.domain.model.Member) = Result.success(state)
    override suspend fun removeMember(id: String) = Result.success(state)
    override suspend fun replaceMembers(members: List<com.guolongli.dinner.domain.model.Member>) = Unit
    override suspend fun setAdmin(memberId: String) = Result.success(state)
    override suspend fun adoptRemoteAdmin(memberId: String?) = Unit
    override suspend fun migrateMemberId(fromId: String, toId: String) = Result.success(state)
    override suspend fun unbind() = Unit
}

private val fakeAdminSession = AdminSession()

private class FakeGroupConfig : com.guolongli.dinner.domain.repository.GroupConfigRepository {
    override suspend fun read(groupId: String): com.guolongli.dinner.domain.model.GroupConfig? = null
    override suspend fun write(config: com.guolongli.dinner.domain.model.GroupConfig) = Result.success(Unit)
    override suspend fun verifyJoinPassword(groupId: String, input: String) = Result.success(Unit)
    override suspend fun rotateJoinPassword(groupId: String) = Result.failure<String>(IllegalStateException("no"))
    override suspend fun updateAdminPassword(groupId: String, newPassword: String) =
        Result.failure<com.guolongli.dinner.domain.model.GroupConfig>(IllegalStateException("no"))
}

private class FakeStorage : com.guolongli.dinner.domain.repository.StorageConfigRepository {
    private var gid: String? = null
    override fun observePaths() = kotlinx.coroutines.flow.flowOf(com.guolongli.dinner.core.NetdiskPaths())
    override suspend fun paths() = com.guolongli.dinner.core.NetdiskPaths()
    override suspend fun setRoot(raw: String) = Result.success(com.guolongli.dinner.core.NetdiskPaths())
    override suspend fun resetToDefault() = Unit
    override fun observeGroupId() = kotlinx.coroutines.flow.flowOf(gid)
    override suspend fun groupId(): String? = gid
    override suspend fun setGroupId(id: String?) { gid = id }
    override fun observeCompressMedia() = kotlinx.coroutines.flow.flowOf(false)
    override suspend fun compressMedia() = false
    override suspend fun setCompressMedia(enabled: Boolean) = Unit
    override fun observeRetentionDays() = kotlinx.coroutines.flow.flowOf(30)
    override suspend fun retentionDays() = 30
    override suspend fun setRetentionDays(days: Int) = Unit
    override fun observeNetworkRetries() = kotlinx.coroutines.flow.flowOf(1)
    override suspend fun networkRetries() = 1
    override suspend fun setNetworkRetries(times: Int) = Unit
}
