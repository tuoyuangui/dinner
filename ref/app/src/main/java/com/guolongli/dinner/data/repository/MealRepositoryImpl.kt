package com.guolongli.dinner.data.repository

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.local.db.dao.MealRecordDao
import com.guolongli.dinner.data.mapper.encodeMedia
import com.guolongli.dinner.data.mapper.toDomain
import com.guolongli.dinner.data.mapper.toEntity
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地优先的记录仓库。
 *
 * 所有写入先落 Room（标 PENDING），再由同步层择机上传。
 *
 * 有两条**后端强制校验**，不依赖界面藏按钮：
 * - [upsert]：覆盖一条别人写的已有记录，要求本次运行已验过管理员口令；
 * - [softDelete]：删别人的记录同理。
 * 自己的新记录 / 自己的旧记录永远放行，普通成员写自己的东西不受影响。
 *
 * 这是 AGENTS「密码卡片…后端仍必须 `requireAdminUnlocked()`」的落点：
 * 界面只是不展示入口，绕过界面的调用路径（深链、后台任务、将来新增的快捷入口）
 * 一样要被拦住。同步层恢复远端记录时走 [upsertAll]，不经过这里，不受影响。
 */
@Singleton
class MealRepositoryImpl @Inject constructor(
    private val dao: MealRecordDao,
    private val pairingRepository: PairingRepository,
    private val adminGate: AdminGate,
) : MealRepository {

    override fun observeAll(): Flow<List<MealRecord>> =
        dao.observeVisible().map { entities -> entities.map { it.toDomain() } }

    override fun observeById(id: String): Flow<MealRecord?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: String): MealRecord? = dao.getById(id)?.toDomain()

    override suspend fun pendingUpload(limit: Int, includeFailed: Boolean): List<MealRecord> {
        val states = if (includeFailed) {
            listOf(SyncState.PENDING.name, SyncState.FAILED.name)
        } else {
            listOf(SyncState.PENDING.name)
        }
        return dao.pendingUpload(states = states, limit = limit).map { it.toDomain() }
    }

    override suspend fun failedCount(): Int =
        dao.countByState(SyncState.FAILED.name).toInt()

    /**
     * 写入一条记录。
     *
     * 覆盖一条**别人写的**已有记录时，要求管理员已解锁：
     * 这是「管理员可以改所有人的内容」在数据层的落点。
     * 自己的新记录、自己的旧记录一律放行。
     */
    override suspend fun upsert(record: MealRecord) {
        requireCanWrite(recordId = record.id)
        dao.upsert(record.toEntity())
    }

    override suspend fun upsertAll(records: List<MealRecord>) {
        // 恢复远端 / 同步回填 / 批量落库走这条路，不经过逐条权限判定：
        // 那些是「把云端已有的东西落到本地」，不是「用户主动改别人的内容」。
        dao.upsertAll(records.map { it.toEntity() })
    }

    override suspend fun updateSyncState(id: String, state: SyncState, updatedAt: Long) {
        dao.updateSyncState(id = id, state = state.name, updatedAt = updatedAt)
    }

    override suspend fun updateMedia(id: String, media: List<MediaItem>) {
        dao.updateMedia(id = id, mediaJson = encodeMedia(media))
    }

    override suspend fun setMarked(id: String, marked: Boolean) {
        dao.setMarked(id = id, marked = marked)
    }

    /**
     * 软删除：置 deletedAt 并回到 PENDING，等待同步告知其他人（云端删除）。
     *
     * 删**别人的**记录要求管理员已解锁（AGENTS：管理员可删所有人的记录，
     * 但必须先验口令）。删自己的永远放行。
     */
    override suspend fun softDelete(id: String, deletedAt: Long) {
        requireCanWrite(recordId = id)
        dao.softDelete(id = id, deletedAt = deletedAt)
    }

    override suspend fun localRemove(id: String, removedAt: Long) {
        // 「只删本地」只打本机标记，云端与他人完全不受影响，不涉及别人的数据。
        dao.localRemove(id = id, removedAt = removedAt)
    }

    override suspend fun clearLocalRemoved(id: String) {
        dao.clearLocalRemoved(id)
    }

    override suspend fun clearLocalRemovedInRange(from: Long, to: Long): Int =
        dao.clearLocalRemovedInRange(from, to)

    override suspend fun hardDelete(id: String) {
        dao.hardDelete(id)
    }

    override suspend fun hardDeleteAll(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.hardDeleteAll(ids)
    }

    override suspend fun purgeCandidates(cutoff: Long): List<MealRecord> =
        dao.purgeCandidates(cutoff).map { it.toDomain() }

    override suspend fun rangeAll(from: Long, to: Long): List<MealRecord> =
        dao.rangeAll(from, to).map { it.toDomain() }

    override suspend fun recoverStuckSyncing(): Int = dao.recoverStuckSyncing()

    override suspend fun rewriteAuthor(from: String, to: String): Int {
        if (from.isBlank() || to.isBlank() || from == to) return 0
        return dao.rewriteAuthor(from, to, System.currentTimeMillis())
    }

    override suspend fun replaceAll(records: List<MealRecord>) {
        dao.upsertAll(records.map { it.toEntity() })
    }

    override suspend fun snapshotAll(): List<MealRecord> =
        dao.getAll().map { it.toDomain() }

    override suspend fun countAll(): Long = dao.countAll()

    override suspend fun countMine(createdBy: String): Long = dao.countMine(createdBy)

    /**
     * 「能不能动这条已有记录」的统一判定。
     *
     * 作者一律取**库里那条**的 createdBy，不信调用方传进来的值：
     * 否则「先把作者改成自己、再写回去」就能绕过。
     *
     * @param recordId 要写入 / 删除的记录 id；库里没有就是新记录，直接放行
     */
    private suspend fun requireCanWrite(recordId: String) {
        val existing = dao.getById(recordId)?.toDomain() ?: return // 新记录：放行
        val group = pairingRepository.observe().first()
        val myId = group.myId
        if (myId.isBlank()) {
            // 身份还没就绪时一律拒绝：空 id 与空作者相等会让「改别人的」被判成「改自己的」。
            throw DinnerException.Validation("本机身份还没就绪，暂时不能修改记录，请稍后重试")
        }
        val authorId = existing.createdBy
        if (authorId.isBlank()) {
            throw DinnerException.Validation("这条记录没有作者，无法判断权限")
        }
        val allowed = adminGate.canModifyContent(
            authorId = authorId,
            myId = myId,
            amIAdmin = group.amIAdmin,
        )
        if (!allowed) {
            throw DinnerException.Validation(
                when {
                    authorId == myId -> "这次修改被拒绝"
                    group.amIAdmin -> "这是别人记的：请先在群组页验证管理员口令，再改 / 删"
                    else -> "这条记录只有本人或管理员能改"
                }
            )
        }
    }
}
