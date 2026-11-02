package com.guolongli.dinner.data.identity

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.identity.IdentityBinding
import com.guolongli.dinner.domain.identity.IdentityRepository
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手机号身份绑定实现。
 *
 * 一次绑定要串起五件事，顺序不能换：
 * 1. 写入号码并把 authorId 换成派生 id（此后所有新记录都用新 id）；
 * 2. 把旧 id 的记录改挂到新 id（否则「我记的」会突然清空）；
 * 3. 名单与管理员跟着改名（否则同一个人以两个身份同时出现）；
 * 4. 头像迁到新文件名（否则换完身份头像就没了）；
 * 5. 拉一次云端历史，再推一次同步（把新的 created_by 与 members.json 写回去）。
 *
 * 没有服务端时还有一个必须承认的局限：**验不了号码归属**。
 * 谁知道你这个号码，谁就能在自己的设备上绑成同一个身份。
 * 在两人共用网盘账号的前提下，真正的边界是「能不能授权这个账号」，
 * 手机号只是把「同一个人」这件事固定下来。
 */
@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val profileStore: LocalProfileStore,
    private val mealRepository: MealRepository,
    private val pairingRepository: PairingRepository,
    private val avatarRepository: AvatarRepository,
    private val syncRepository: SyncRepository,
    private val sessionManager: SessionManager,
) : IdentityRepository {

    override fun observePhoneMask(): Flow<String?> =
        profileStore.observePhone().map { phone -> phone?.let(PhoneIdentity::mask) }

    override fun observeBound(): Flow<Boolean> =
        profileStore.observeAuthorId().map { id -> PhoneIdentity.isPhoneId(id) }

    override suspend fun bind(rawPhone: String): Result<IdentityBinding> = runCatching {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw DinnerException.Validation("请填写 11 位手机号")

        val targetId = PhoneIdentity.idOf(phone)
        // bindPhone 内部会顺手把旧 id 记进退役名单，避免远端旧条目回流。
        val previousId = profileStore.bindPhone(phone)

        var migrated = 0
        if (previousId != null && previousId != targetId) {
            migrated = mealRepository.rewriteAuthor(previousId, targetId)
            runCatching { pairingRepository.migrateMemberId(previousId, targetId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
            runCatching { avatarRepository.migrateIdentity(previousId, targetId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
            Logger.i("身份迁移完成：$previousId -> $targetId，改写 $migrated 条记录")
        }

        // 未授权时只能把身份留在本机，历史等授权后再拉。
        if (!sessionManager.ensureFreshToken()) {
            return@runCatching IdentityBinding(
                phoneMask = PhoneIdentity.mask(phone),
                migratedRecords = migrated,
                restoredRecords = 0,
                pushed = false,
            )
        }

        val restored = runCatching { syncRepository.restoreFromRemote() }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .getOrNull()
            ?.getOrNull()
            ?.size ?: 0

        val pushed = runCatching { syncRepository.syncNow(auto = false) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .getOrNull()
            ?.isSuccess == true

        IdentityBinding(
            phoneMask = PhoneIdentity.mask(phone),
            migratedRecords = migrated,
            restoredRecords = restored,
            pushed = pushed,
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }
}
