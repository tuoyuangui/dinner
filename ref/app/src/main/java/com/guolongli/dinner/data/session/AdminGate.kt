package com.guolongli.dinner.data.session

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.domain.repository.GroupConfigRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「管理员能不能动这个」的唯一裁决点。
 *
 * AGENTS.md 群组门禁的后半句是：**后端仍必须 `requireAdminUnlocked()`**。
 * 界面把按钮藏起来不算数——绕过界面的调用路径（深链、后台任务、分享入口）
 * 一样要能被拦住。所以每一条「改 / 删别人的东西」的写路径都要先过这里。
 *
 * 两道条件缺一不可：
 * 1. 本进程内验过管理员口令（[AdminSession]）；
 * 2. 云端 `passwd.json` 里的 `admin_password` 还是当时验的那一份——
 *    别人在另一台设备换了口令，本机这次运行里的解锁态必须立刻作废。
 *
 * 第 2 条是审计里点名的那条「解锁态从不与云端联动」：只在内存里记一个布尔，
 * 换过口令的旧解锁态会一直有效到进程被杀，那等于口令没有失效机制。
 */
@Singleton
class AdminGate @Inject constructor(
    private val adminSession: AdminSession,
    private val groupConfigRepository: GroupConfigRepository,
    private val storageConfig: StorageConfigRepository,
) {

    private val verifyMutex = Mutex()

    /** 当前群组 id；未入组时为 null。 */
    private suspend fun currentGroupId(): String? =
        storageConfig.groupId()?.takeIf { it.isNotBlank() }

    /**
     * 是否已解锁管理员（会顺带与云端比对一次口令）。
     *
     * 失败一律返回 false，不抛错：这是给 ViewModel 算「按钮该不该显示」用的。
     * 真正的写路径必须调 [requireAdmin]，它会在拒绝时抛错。
     */
    suspend fun isAdminUnlocked(): Boolean = verifyMutex.withLock {
        val groupId = currentGroupId() ?: return@withLock false
        if (!adminSession.isUnlocked(groupId)) return@withLock false

        val rememberedPassword = adminSession.rememberedAdminPassword(groupId)
        val remote = runCatching { groupConfigRepository.read(groupId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .getOrNull()
        // 读不到云端口令时按「不可解锁」处理：宁可让用户重验一次，
        // 也不能拿一个可能已经失效的旧解锁态去改别人的数据。
        if (remote == null) {
            adminSession.lock()
            return@withLock false
        }
        // 口令在云端被换掉（或当初验的那份已经对不上）→ 立即锁定。
        if (rememberedPassword != null && !remote.matchesAdminPassword(rememberedPassword)) {
            Logger.w("云端口令已变更，本机管理员解锁态作废")
            adminSession.lock()
            return@withLock false
        }
        true
    }

    /**
     * 后端强制校验。未解锁 / 云端口令已变时抛错。
     *
     * 抛 [DinnerException.Validation] 而不是布尔返回值：返回 false 很容易被
     * `val ok = require...` 之后忘记判，抛错一定会浮到界面。
     */
    suspend fun requireAdmin() {
        if (!isAdminUnlocked()) {
            throw DinnerException.Validation(
                "这次运行还没有验证管理员口令（或口令已在别处更换），请先在群组页验证"
            )
        }
    }

    /**
     * 判定「这次操作允不允许」：自己的内容永远允许；别人的内容要求管理员已解锁。
     *
     * @param authorId 内容作者
     * @param myId 本机成员 id
     * @param amIAdmin 名单口径上的管理员（[com.guolongli.dinner.domain.model.GroupState.amIAdmin]）
     */
    suspend fun canModifyContent(
        authorId: String,
        myId: String,
        amIAdmin: Boolean,
    ): Boolean {
        // 作者为空说明归属未知，此时不能因为管理员身份而放行（历史上误删正是从这里漏的）。
        if (authorId.isBlank()) return false
        if (myId.isBlank()) return false
        if (authorId == myId) return true
        if (!amIAdmin) return false
        return isAdminUnlocked()
    }
}
