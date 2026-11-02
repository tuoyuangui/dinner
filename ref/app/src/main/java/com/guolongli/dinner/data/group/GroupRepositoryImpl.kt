package com.guolongli.dinner.data.group

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.identity.IdentityRepository
import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.model.GroupIdentity
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.GroupConfigRepository
import com.guolongli.dinner.domain.repository.GroupJoin
import com.guolongli.dinner.domain.repository.GroupRepository
import com.guolongli.dinner.domain.repository.GroupSetup
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 群组身份与入群流程的实现。
 *
 * 只有一种目录形态：`group_<管理员手机号>/`。建组就是从零建立这套目录，
 * 不做任何「把老数据搬过来」的动作。
 *
 * 建组的关键在**顺序**，换一步就会丢数据：
 * 1. 先验网盘授权——群组信息与群组数据都写在网盘上，未授权只会建出空壳；
 * 2. 再把 groupId 落下来，之后所有路径都变成 `group_<号码>/`；
 * 3. 绑定手机号（身份迁移 + 从新路径恢复 + 推一次）；
 * 4. 自己写进名单并成为管理员；
 * 5. 写 passwd.json（管理员口令 + 动态入群密码）——**这一步失败必须回滚 groupId**，
 *    否则会留下「界面显示已建群、云端却没有 passwd.json」的最糟状态：
 *    别人拿不到入群密码，本机也再改不了，重试还会因为 groupId 已经是这个值而修不好。
 * 6. 推一次快照与名单。
 *
 * 入群只验云端 `join_password`：管理员手机号只用来定位目录，不绑电话、不验管理员口令。
 * 管理员口令是另一道门，只管「改别人的东西」，见 [unlockAdmin]。
 */
@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val profileStore: LocalProfileStore,
    private val storageConfig: StorageConfigRepository,
    private val pairingRepository: PairingRepository,
    private val identityRepository: IdentityRepository,
    private val groupConfigRepository: GroupConfigRepository,
    private val avatarRepository: AvatarRepository,
    private val syncRepository: SyncRepository,
    private val sessionManager: SessionManager,
    private val adminSession: AdminSession,
    private val adminGate: AdminGate,
) : GroupRepository {

    override fun observeGroupId(): Flow<String?> = storageConfig.observeGroupId()

    override suspend fun groupId(): String? = storageConfig.groupId()

    override suspend fun config(groupId: String): Result<GroupConfig?> = runCatching {
        groupConfigRepository.read(groupId)
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun createGroup(
        rawPhone: String,
        rawAdminPassword: String,
    ): Result<GroupSetup> = runCatching {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw DinnerException.Validation("请填写 11 位手机号")
        val adminPassword = validatePassword(rawAdminPassword, "管理员口令")
        val groupId = PhoneIdentity.groupIdOf(phone)

        // 建组前必须先完成网盘授权：群组信息与群组数据都写在网盘上，
        // 未授权时建到一半只会得到一个远程什么都没有的空壳。
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "请先完成百度网盘授权，再创建群组"
            )
        }

        // 切路径。**必须记下旧值用于回滚**。
        val previousGroupId = storageConfig.groupId()
        storageConfig.setGroupId(groupId)

        // 2. 绑定手机号：旧记录 / 成员条目 / 头像跟着改名，并从新路径恢复历史
        runCatching { identityRepository.bind(phone) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .onFailure { Logger.w("身份绑定失败：${it.message}") }

        // 3. 自己进名单并成为管理员（成员 id 就是手机号）
        val myName = profileStore.observeMyName().first()
        val existing = pairingRepository.current().memberOf(phone)
        pairingRepository.upsertMember(
            Member(
                id = phone,
                name = myName.ifBlank { existing?.name.orEmpty() },
                avatarPath = existing?.avatarPath,
                joinedAt = existing?.joinedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        )
        pairingRepository.setAdmin(phone)

        // 4. 写 passwd.json（管理员口令 + 动态入群密码，明文）。
        //    写失败必须回滚 groupId 并终止：远程没有 passwd.json 的群组，
        //    别人加不进来（没有 join_password），本机也改不了（没有可比对的管理员口令）。
        val now = System.currentTimeMillis()
        val joinPassword = GroupConfig.randomPassword(GroupConfig.JOIN_PASSWORD_LENGTH)
        val written = groupConfigRepository.write(
            GroupConfig(
                groupId = groupId,
                adminPhone = phone,
                adminPassword = adminPassword,
                joinPassword = joinPassword,
                adminId = phone,
                createdAt = now,
            )
        )
        written.getOrElse { error ->
            runCatching { storageConfig.setGroupId(previousGroupId) }
                .onFailure { rollback ->
                    if (rollback is CancellationException) throw rollback
                    Logger.w("回滚 groupId 失败：${rollback.message}")
                }
            throw DinnerException.Local(
                "passwd.json 写入网盘失败：${error.message}。群组没有创建，请重试",
                error
            )
        }

        // 建组**不自动授予管理权**（AGENTS 群组门禁）：
        // 必须走与 unlockAdmin 同一套「读云端 → 比对 → 才解锁」的路径。
        // 建组者刚亲手设过这个口令，因此这里能一次就通过；
        // 但万一云端读回来的不是这次写的（写后回读已保证不会），也不会放出管理权。
        unlockAdmin(groupId, adminPassword).getOrThrow()

        // 5. 推一次：让 members.json 带上管理员，快照落到群组目录
        val synced = runCatching { syncRepository.syncNow(auto = false) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .getOrNull()
            ?.isSuccess == true

        if (synced) {
            runCatching { avatarRepository.syncAvatars(listOf(phone)) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
        }

        GroupSetup(
            identity = GroupIdentity(groupId = groupId, phoneMask = PhoneIdentity.mask(phone)),
            authorized = true,
            synced = synced,
            adminPassword = adminPassword,
            joinPassword = joinPassword,
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun joinGroup(
        rawPhone: String,
        adminPhone: String,
        joinPassword: String,
    ): Result<GroupJoin> = runCatching {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw DinnerException.Validation("请填写你的 11 位手机号")
        val admin = PhoneIdentity.normalize(adminPhone)
            ?: throw DinnerException.Validation("请填写管理员的 11 位手机号")
        val groupId = PhoneIdentity.groupIdOf(admin)
        if (phone == admin) {
            throw DinnerException.Validation("这是你自己的号码。想当建组者请用「创建群组」")
        }
        val input = joinPassword.trim()
        if (input.isEmpty()) {
            throw DinnerException.Validation("请填写管理员转告的入群密码")
        }

        // 入群前必须先完成网盘授权：群组数据全在网盘上，
        // 未授权时拉名单与同步都无从谈起。
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "请先完成百度网盘授权，再加入群组"
            )
        }

        // **只验 join_password**：先从云端读 passwd.json 再比对。
        // 不绑定加入者的电话（号码只作为成员 id），也不校验管理员口令——
        // 那是管理员自己改别人内容时用的门，和「能不能进群」是两件事。
        groupConfigRepository.verifyJoinPassword(groupId, input).getOrThrow()

        // 成员 id 就是手机号：先固定身份，之后记录里的 createdBy 才对得上。
        val currentId = profileStore.observeAuthorId().first()
        if (currentId != phone) {
            runCatching { identityRepository.bind(phone) }.onFailure { throw it }
        }

        // 切进群组目录。同 createGroup：记下旧值，后面任何一步失败都要退回去。
        val previousGroupId = storageConfig.groupId()
        storageConfig.setGroupId(groupId)

        // 先合并远端名单（保住已在群里的成员），再把自己写进去。
        runCatching { syncRepository.restoreFromRemote() }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .onFailure { Logger.w("入群时恢复记录失败：${it.message}") }

        val myName = profileStore.observeMyName().first()
        val existing = pairingRepository.current().memberOf(phone)
        pairingRepository.upsertMember(
            Member(
                id = phone,
                name = myName.ifBlank { existing?.name.orEmpty() },
                avatarPath = existing?.avatarPath,
                joinedAt = existing?.joinedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        ).getOrElse { error ->
            runCatching { storageConfig.setGroupId(previousGroupId) }
                .onFailure { rollback ->
                    if (rollback is CancellationException) throw rollback
                    Logger.w("回滚 groupId 失败：${rollback.message}")
                }
            throw error
        }

        // 推一次名单，让管理员与其他成员在 members.json 里看到我。
        runCatching { syncRepository.syncNow(auto = false) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .onFailure { Logger.w("入群后同步失败：${it.message}") }
        runCatching { avatarRepository.syncAvatars(listOf(phone)) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }

        Logger.i("已加入群组 $groupId，成员 ${pairingRepository.current().memberCount} 人")
        GroupJoin(
            identity = GroupIdentity(groupId = groupId, phoneMask = PhoneIdentity.mask(admin)),
            members = pairingRepository.current().memberCount,
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun unlockAdmin(
        groupId: String,
        adminPassword: String,
    ): Result<Unit> = runCatching {
        if (groupId.isBlank()) throw DinnerException.Validation("还没有加入群组")
        val input = adminPassword.trim()
        if (input.isEmpty()) throw DinnerException.Validation("请填写管理员口令")

        // 每次先 read() 云端：别人可能刚换了口令，本机内存里的旧解锁态不算数。
        val config = groupConfigRepository.read(groupId)
            ?: throw DinnerException.Validation("云端没有找到 passwd.json，请让管理员先建组")
        if (!config.matchesAdminPassword(input)) {
            // 输错必须保持未解锁；不能因为「以前解锁过」就放行。
            adminSession.lock()
            throw DinnerException.Validation("管理员口令不对")
        }

        // 账号身份：管理员口令通过后，解锁的是「当前生效的管理员」。
        // 转让过管理权时 admin_id 指向新管理员，而口令仍然是原来那一份
        // （AGENTS.md 里明确记着这个张力：转让不等于拿到口令）。
        val effective = config.adminId?.takeIf { it.isNotBlank() }
            ?: config.adminPhone.takeIf { it.isNotBlank() }
            ?: throw DinnerException.Validation("云端 passwd.json 缺少管理员信息")
        adminSession.unlock(groupId, effective, input)
        Logger.i("管理员已解锁：$groupId")
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override fun lockAdmin() {
        adminSession.lock()
    }

    override suspend fun transferAdmin(memberId: String): Result<String> = runCatching {
        // 后端强制校验：转让管理权是管理员专属动作，未解锁一律拒绝。
        // 这条以前只由 ViewModel 判 amIAdmin，未解锁也能把管理权交出去。
        adminGate.requireAdmin()
        val target = memberId.trim()
        if (target.isEmpty()) throw DinnerException.Validation("要转让给谁？")
        val group = pairingRepository.current()
        if (!group.amIAdmin) {
            throw DinnerException.Validation("当前管理员已经不是你，无法转让")
        }
        if (group.memberOf(target) == null) {
            throw DinnerException.Validation("名单里没有这个人")
        }
        // setAdmin 自身返回 Result，必须摊平：包成 Result<Result<*>> 时
        // isSuccess 恒为真，转让失败会被静默吞掉。
        pairingRepository.setAdmin(target).getOrThrow()
        // 解锁身份跟着走到新管理员（本机刚验过口令，不必强制重验）。
        adminSession.adoptAdmin(storageConfig.groupId().orEmpty(), target)
        target
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun canModifyContent(
        authorId: String,
        myId: String,
        amIAdmin: Boolean,
    ): Boolean = adminGate.canModifyContent(authorId = authorId, myId = myId, amIAdmin = amIAdmin)

    override suspend fun rotateJoinPassword(groupId: String): Result<String> = runCatching {
        // 后端强制校验：界面藏了按钮不算数，绕过界面的调用路径也必须被拦住。
        // AdminGate 还会与云端再比一次口令——别人在别的设备换过口令，
        // 本机这次运行里的旧解锁态必须立刻作废。
        adminGate.requireAdmin()
        groupConfigRepository.rotateJoinPassword(groupId).getOrThrow()
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun currentConfig(): Result<GroupConfig?> = runCatching {
        val groupId = storageConfig.groupId()?.takeIf { it.isNotBlank() }
            ?: throw DinnerException.Validation("还没有加入群组")
        groupConfigRepository.read(groupId)
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    /**
     * 口令合法性只在这里把守一次。
     *
     * 不接受空白与超长串：明文会整段写进 passwd.json 上云，
     * 粘贴进一个几千字的串既写不进去，读回来也没法比对。
     */
    private fun validatePassword(raw: String, label: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw DinnerException.Validation("请填写$label")
        if (trimmed.length > GroupConfig.MAX_PASSWORD_LENGTH) {
            throw DinnerException.Validation("$label 不能超过 ${GroupConfig.MAX_PASSWORD_LENGTH} 个字符")
        }
        return trimmed
    }
}
