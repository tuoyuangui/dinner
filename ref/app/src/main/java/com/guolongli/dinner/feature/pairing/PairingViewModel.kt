package com.guolongli.dinner.feature.pairing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 成员行展示数据。 */
@Immutable
data class MemberUi(
    val id: String,
    val name: String,
    val isMe: Boolean,
    val avatar: File?,
)

/**
 * 成员管理页不可变 UI 状态。
 *
 * 不再有「配对」概念：这里管理的是一个上限 10 人的成员名单。
 */
data class PairingUiState(
    val isLoading: Boolean = true,
    val members: List<MemberUi> = emptyList(),
    val memberLimit: Int = GroupState.MAX_MEMBERS,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showRenameDialog: Boolean = false,
    val nameInput: String = "",
    val removeTarget: MemberUi? = null,
) {
    val isFull: Boolean get() = members.size >= memberLimit
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val avatarRepository: AvatarRepository,
    private val syncRepository: SyncRepository,
    private val profileStore: LocalProfileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                pairingRepository.observe(),
                avatarRepository.observeAvatars(),
                profileStore.observeMyName(),
            ) { group, avatars, myName ->
                val list = group.members.map { member ->
                    MemberUi(
                        id = member.id,
                        // 自己的昵称以本地档案为准，避免改名后名单里的旧名字回跳。
                        name = if (member.id == group.myId) myName else member.name.ifBlank { "成员" },
                        isMe = member.id == group.myId,
                        avatar = avatars[member.id],
                    )
                }
                list to myName
            }.collect { (members, _) ->
                _uiState.update {
                    it.copy(isLoading = false, members = members)
                }
            }
        }
    }

    fun openRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = true, nameInput = it.members.firstOrNull { m -> m.isMe }?.name.orEmpty()) }
    }

    fun closeRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false) }
    }

    fun setRenameInput(value: String) {
        _uiState.update { it.copy(nameInput = value.take(MAX_NAME_LENGTH)) }
    }

    /** 改昵称：本地档案与成员名单同步更新，否则别人看到的还是旧名字。 */
    fun rename() {
        val name = _uiState.value.nameInput.trim()
        if (name.isEmpty()) return
        _uiState.update { it.copy(showRenameDialog = false, errorMessage = null) }
        viewModelScope.launch {
            // DataStore / Room 的异常会一路冒到没有异常处理器的 viewModelScope 里，
            // 直接把应用带崩；这里兜住并如实回写到错误字段。
            val result = runCatchingCancellable {
                profileStore.saveMyName(name)
                ensureSelf(nameOverride = name)
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(errorMessage = result.exceptionOrNull()?.toUserText() ?: "昵称没能保存，请重试")
                }
                return@launch
            }
            // 昵称落本机了，但推没推上云是另一件事：同步失败必须如实说明，
            // 否则别人看到的还是旧名字。
            reportSync(profileChanged = "昵称已保存")
        }
    }

    /**
     * 同步一次并把结论写进界面状态。
     *
     * 以前改完昵称 / 移出成员后直接 `runCatchingCancellable { syncNow() }` 丢掉返回值，
     * 界面上已经显示「已移出…」，同步失败时用户以为所有人都看到了。
     */
    private suspend fun reportSync(profileChanged: String) {
        val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }.getOrNull()
        when {
            outcome == null -> _uiState.update {
                it.copy(successMessage = profileChanged, errorMessage = "本地已改，但同步没跑起来，别人暂时看不到")
            }
            outcome.isSuccess -> _uiState.update {
                it.copy(successMessage = profileChanged + "，已同步给所有人")
            }
            outcome.skipped -> _uiState.update {
                it.copy(successMessage = profileChanged, errorMessage = "本地已改，但这次没有同步（未授权或已取消）")
            }
            else -> _uiState.update {
                it.copy(
                    successMessage = profileChanged,
                    errorMessage = "本地已改，但没推上云盘：${outcome.message ?: "同步失败"}，别人暂时看不到",
                )
            }
        }
    }

    fun askRemove(member: MemberUi) {
        _uiState.update { it.copy(removeTarget = member) }
    }

    fun dismissRemove() {
        _uiState.update { it.copy(removeTarget = null) }
    }

    /**
     * 移出成员。
     *
     * 只是把这个人从名单里去掉，不会删除 TA 上传过的任何记录——
     * 记录归属由 createdBy 决定，删人不等于删内容（红线：不能删别人的东西）。
     */
    fun confirmRemove() {
        val target = _uiState.value.removeTarget ?: return
        _uiState.update { it.copy(removeTarget = null) }
        viewModelScope.launch {
            // removeMember 返回 Result：必须摊平，否则 isSuccess 恒真，失败被吞。
            val result = runCatchingCancellable {
                pairingRepository.removeMember(target.id).getOrThrow()
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(errorMessage = result.exceptionOrNull()?.toUserText() ?: "移出失败，请重试")
                }
                return@launch
            }
            _uiState.update {
                it.copy(successMessage = "已移出「${target.name}」，TA 的记录仍在时间线上")
            }
            reportSync(profileChanged = "已移出「${target.name}」")
        }
    }

    /** 确保「我」始终在名单里，且昵称与本地档案一致。 */
    private suspend fun ensureSelf(nameOverride: String? = null) {
        val group = pairingRepository.current()
        val name = nameOverride ?: profileStore.observeMyName().first()
        val me = group.memberOf(group.myId)
        // upsertMember 返回 Result：摊平后失败才能冒到调用点，而不是被丢掉假装写成功。
        pairingRepository.upsertMember(
            Member(
                id = group.myId,
                name = name,
                avatarPath = me?.avatarPath,
                joinedAt = me?.joinedAt ?: System.currentTimeMillis(),
            )
        ).getOrThrow()
    }

    companion object {
        const val MAX_NAME_LENGTH = 16
    }
}

private fun MutableStateFlow<PairingUiState>.update(transform: (PairingUiState) -> PairingUiState) {
    value = transform(value)
}
