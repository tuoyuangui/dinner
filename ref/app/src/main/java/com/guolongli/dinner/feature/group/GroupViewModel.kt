package com.guolongli.dinner.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.repository.GroupRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 身份选择：先决定「我要建组还是加入别人的群」，再填对应的表单。 */
enum class GroupRole { ADMIN, MEMBER }

/**
 * 群组页不可变 UI 状态。
 *
 * 一页承载两种场景，靠 [phase] 切换：
 * - 未建组未加入：选身份 + 填表（管理员填手机号 + 管理员口令；群员填两个号码 + 入群密码）；
 * - 已入组：看自己在哪个群、谁是管理员，管理员可验口令后换入群密码。
 *
 * 口令一律默认遮明文：界面在验证通过前不展示明文，
 * 但后端（[GroupRepository.rotateJoinPassword]）仍然会 `requireAdminUnlocked`，
 * 藏起来只是不让人顺手看见，不是权限本身。
 */
data class GroupUiState(
    val isLoading: Boolean = true,
    val phase: Phase = Phase.SETUP,
    val role: GroupRole = GroupRole.ADMIN,
    /** 当前所属群组 id（`group_<管理员手机号>`）；null 表示还没建 / 还没加入。 */
    val groupId: String? = null,
    /** 我是不是管理员（以 GroupState.effectiveAdminId 为准，不看「谁建过组」）。 */
    val amIAdmin: Boolean = false,
    /** 本次运行是否已验过管理员口令（内存态，冷启动必为 false）。 */
    val adminUnlocked: Boolean = false,
    // ---- 建组表单 ----
    val adminPhone: String = "",
    val adminPasswordInput: String = "",
    // ---- 入群表单 ----
    val memberPhone: String = "",
    val joinAdminPhone: String = "",
    val joinPasswordInput: String = "",
    // ---- 口令卡片 ----
    /** 云端读回来的入群密码；null 表示还没读到 / 读失败。 */
    val joinPassword: String? = null,
    /** 默认遮明文，管理员点「显示」才展开。 */
    val joinPasswordVisible: Boolean = false,
    /** 管理员登录输入框。 */
    val unlockPasswordInput: String = "",
    /** 读云端 passwd.json 的失败原因（读不到 ≠ 没有）。 */
    val configError: String? = null,
    // ---- 状态 ----
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    /**
     * 消息的自增序号。
     *
     * 界面把消息当成一次性事件弹 Snackbar，如果以「消息内容」当 key，
     * 连续两次弹出同一句文案时 key 不变、effect 不重启，用户第二次什么都看不到。
     * 序号每次发消息都 +1，界面改用 `LaunchedEffect(state.messageSeq)` 即可。
     */
    val messageSeq: Int = 0,
) {
    enum class Phase {
        /** 还没建组也没加入：选身份填表。 */
        SETUP,

        /** 已入组（含自己建的组）。 */
        JOINED,
    }

    /** 手机号合法：按 [PhoneIdentity] 的口径判 11 位，不能用「非空」蒙过去。 */
    val adminPhoneValid: Boolean get() = PhoneIdentity.isValid(adminPhone)

    /** 自己的号码合法（入群时只作成员 id，但仍然必须是真号码）。 */
    val memberPhoneValid: Boolean get() = PhoneIdentity.isValid(memberPhone)

    /** 管理员号码合法：群组目录名由它拼出，错一位就进到另一个群。 */
    val joinAdminPhoneValid: Boolean get() = PhoneIdentity.isValid(joinAdminPhone)

    /** 管理员口令非空。合法性上限由仓储层再校验一次。 */
    val adminPasswordReady: Boolean
        get() = adminPasswordInput.trim().isNotEmpty() &&
            adminPasswordInput.trim().length <= GroupConfig.MAX_PASSWORD_LENGTH

    /** 入群密码非空。 */
    val joinPasswordReady: Boolean
        get() = joinPasswordInput.trim().isNotEmpty() &&
            joinPasswordInput.trim().length <= GroupConfig.MAX_PASSWORD_LENGTH

    /** 建组表单是否可提交。 */
    val canCreate: Boolean get() = adminPhoneValid && adminPasswordReady && !isBusy

    /** 入群表单是否可提交。 */
    val canJoin: Boolean
        get() = memberPhoneValid && joinAdminPhoneValid && joinPasswordReady && !isBusy

    /** 口令卡片是否展示明文：验过管理员口令，或管理员手动点了「显示」。 */
    val passwordCardExpanded: Boolean get() = adminUnlocked && joinPasswordVisible
}

@HiltViewModel
class GroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val syncRepository: SyncRepository,
    private val pairingRepository: PairingRepository,
    private val adminSession: AdminSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupUiState())
    val uiState: StateFlow<GroupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                groupRepository.observeGroupId(),
                pairingRepository.observe(),
            ) { groupId, group -> groupId to group }
                .collect { (groupId, group) ->
                    val phase = if (groupId.isNullOrBlank()) {
                        GroupUiState.Phase.SETUP
                    } else {
                        GroupUiState.Phase.JOINED
                    }
                    // 管理员身份以 GroupState 为准（显式 adminId -> 最早加入 -> 自己），
                    // 不是「谁建过组就永远是谁」。解锁态只认内存里的 AdminSession。
                    val unlocked = groupId != null && adminSession.isUnlocked(groupId)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            groupId = groupId,
                            phase = phase,
                            amIAdmin = group.amIAdmin,
                            adminUnlocked = unlocked,
                            joinPassword = if (unlocked) it.joinPassword else null,
                            joinPasswordVisible = if (unlocked) it.joinPasswordVisible else false,
                        )
                    }
                    if (!groupId.isNullOrBlank()) refreshConfig(groupId)
                }
        }
    }

    /** 读云端 passwd.json。读失败必须如实展示，不能说成「没有密码」。 */
    private fun refreshConfig(groupId: String) {
        viewModelScope.launch {
            runCatchingCancellable { groupRepository.config(groupId).getOrThrow() }
                .onSuccess { config ->
                    _uiState.update {
                        it.copy(
                            joinPassword = config?.joinPassword?.takeIf { p -> p.isNotBlank() },
                            configError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            joinPassword = null,
                            configError = "读不到云端 passwd.json：${error.toUserText()}",
                        )
                    }
                }
        }
    }

    fun setRole(role: GroupRole) {
        _uiState.update { it.copy(role = role, errorMessage = null) }
    }

    /**
     * 输入手机号：非数字字符一律丢掉，各种数字系统的数字统一转成 ASCII。
     *
     * 不能无条件 `take(11)`：粘贴 `+86 138 0013 8000` 滤完还剩 13 位，
     * 截成 11 位会把号码切坏（86 + 前 9 位），用户看不出哪里错了。
     * 长度上限留出带国家码的写法（0086 + 11 位 = 15），真正决定合不合法的是 PhoneIdentity.normalize。
     *
     * 也不能直接 `filter { it.isDigit() }`：阿拉伯-印度数字会被原样留下，
     * 而 PhoneIdentity.normalize 只认 ASCII，用户填了自己真实的号码却一直提示格式不对。
     */
    fun setAdminPhone(value: String) {
        _uiState.update { it.copy(adminPhone = asciiPhoneInput(value)) }
    }

    fun setMemberPhone(value: String) {
        _uiState.update { it.copy(memberPhone = asciiPhoneInput(value)) }
    }

    fun setJoinAdminPhone(value: String) {
        _uiState.update { it.copy(joinAdminPhone = asciiPhoneInput(value)) }
    }

    /** 管理员口令：只做长度截断，不做字符过滤（口令可以是任意字符）。 */
    fun setAdminPassword(value: String) {
        _uiState.update {
            it.copy(
                adminPasswordInput = value.take(GroupConfig.MAX_PASSWORD_LENGTH),
                errorMessage = null,
            )
        }
    }

    fun setJoinPassword(value: String) {
        _uiState.update {
            it.copy(
                joinPasswordInput = value.take(GroupConfig.MAX_PASSWORD_LENGTH),
                errorMessage = null,
            )
        }
    }

    /** 管理员解锁输入框。 */
    fun setUnlockPassword(value: String) {
        _uiState.update {
            it.copy(
                unlockPasswordInput = value.take(GroupConfig.MAX_PASSWORD_LENGTH),
                errorMessage = null,
            )
        }
    }

    fun toggleJoinPasswordVisible() {
        _uiState.update { it.copy(joinPasswordVisible = !it.joinPasswordVisible) }
    }

    /** 手机号输入框的统一处理：数字系统转 ASCII -> 去非数字 -> 限长。 */
    private fun asciiPhoneInput(value: String): String =
        PhoneIdentity.toAsciiDigits(value)
            .filter { it in '0'..'9' }
            .take(MAX_PHONE_INPUT_LENGTH)

    /**
     * 发一条错误提示。
     *
     * 一律走这里而不是直接 `copy(errorMessage = ...)`：消息是一次性事件，
     * 序号必须跟着自增，界面才能把连续两次相同的文案各弹一次。
     */
    private fun postError(message: String) {
        _uiState.update {
            it.copy(errorMessage = message, successMessage = null, messageSeq = it.messageSeq + 1)
        }
    }

    private fun postSuccess(message: String) {
        _uiState.update {
            it.copy(successMessage = message, errorMessage = null, messageSeq = it.messageSeq + 1)
        }
    }

    /** 建组：手机号 + 管理员口令，口令会明文写进云端 passwd.json。 */
    fun createGroup() {
        val current = _uiState.value
        if (!current.canCreate) {
            postError(
                when {
                    !current.adminPhoneValid -> "请填写 11 位手机号（可以带 +86、空格或横线）"
                    !current.adminPasswordReady -> "请填写管理员口令（不超过 64 个字符）"
                    else -> "先补全表单"
                }
            )
            return
        }
        // 归一化后再交给仓储：这样才能容忍 +86 / 空格 / 横线这些写法，
        // 也不会因为两种写法拼出两个不同的群组目录。
        val adminPhone = PhoneIdentity.normalize(current.adminPhone)
        if (adminPhone == null) {
            postError("请输入有效的 11 位手机号")
            return
        }
        val adminPassword = current.adminPasswordInput.trim()
        _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            // createGroup 本身返回 Result：这里只要「抛不抛」的语义，
            // 取到最终值，别把 Result 当成业务对象继续往下传。
            runCatchingCancellable {
                groupRepository.createGroup(adminPhone, adminPassword).getOrThrow()
            }.onSuccess { setup ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        phase = GroupUiState.Phase.JOINED,
                        groupId = setup.identity.groupId,
                        amIAdmin = true,
                        adminUnlocked = true,
                        adminPasswordInput = "",
                        joinPassword = setup.joinPassword,
                        successMessage = setup.message,
                        messageSeq = it.messageSeq + 1,
                    )
                }
                if (!setup.synced) {
                    // 建组本身成功（本地已切路径），但快照没推上去必须单独说清。
                    postError("群组已创建，但记录快照与成员名单没有推上云盘，别人暂时看不到，稍后请手动同步")
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = error.toUserText(),
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }
        }
    }

    /**
     * 加入群组：填自己的号码 + 管理员号码 + 入群密码。
     *
     * 管理员号码决定进哪个群（`group_<号码>/`）；自己的号码就是成员 id；
     * 入群密码与云端 `join_password` 比对，不比对管理员口令。
     */
    fun joinGroup() {
        val current = _uiState.value
        if (!current.canJoin) {
            postError(
                when {
                    !current.memberPhoneValid -> "请填写你自己的 11 位手机号"
                    !current.joinAdminPhoneValid -> "请填写管理员的 11 位手机号"
                    !current.joinPasswordReady -> "请填写管理员转告的入群密码"
                    else -> "先补全表单"
                }
            )
            return
        }
        // 两边都要归一化：管理员号码拼的是群组目录名，自己的号码就是成员 id，
        // 任何一种写法不统一都会导致「同一个人两个 id」或者进错群。
        val memberPhone = PhoneIdentity.normalize(current.memberPhone)
        val adminPhone = PhoneIdentity.normalize(current.joinAdminPhone)
        if (memberPhone == null || adminPhone == null) {
            postError("请输入有效的 11 位手机号")
            return
        }
        val joinPassword = current.joinPasswordInput.trim()
        _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            runCatchingCancellable {
                groupRepository.joinGroup(
                    rawPhone = memberPhone,
                    adminPhone = adminPhone,
                    joinPassword = joinPassword,
                ).getOrThrow()
            }.onSuccess { joined ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        phase = GroupUiState.Phase.JOINED,
                        groupId = joined.identity.groupId,
                        joinPasswordInput = "",
                        successMessage = joined.message,
                        messageSeq = it.messageSeq + 1,
                    )
                }
                refreshConfig(joined.identity.groupId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = error.toUserText(),
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }
        }
    }

    /**
     * 管理员登录：显式输入管理员口令。
     *
     * 通过后本进程内解锁，界面才出现「换入群密码」与「退出管理员」；
     * 后端仓储层仍会 `requireAdminUnlocked`，藏按钮不等于权限。
     */
    fun unlockAdmin() {
        val current = _uiState.value
        val groupId = current.groupId
        if (groupId.isNullOrBlank()) {
            postError("还没有加入群组")
            return
        }
        val password = current.unlockPasswordInput.trim()
        if (password.isEmpty()) {
            postError("请填写管理员口令")
            return
        }
        if (current.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            runCatchingCancellable {
                groupRepository.unlockAdmin(groupId, password).getOrThrow()
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        unlockPasswordInput = "",
                        adminUnlocked = true,
                        successMessage = "管理员口令校验通过，本次运行内有效",
                        messageSeq = it.messageSeq + 1,
                    )
                }
                refreshConfig(groupId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        adminUnlocked = false,
                        errorMessage = error.toUserText(),
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }
        }
    }

    /** 退出管理员：只锁内存里的解锁态，不动群组关系，也不清云端任何文件。 */
    fun lockAdmin() {
        groupRepository.lockAdmin()
        _uiState.update {
            it.copy(
                adminUnlocked = false,
                joinPasswordVisible = false,
                unlockPasswordInput = "",
                successMessage = "已退出管理员，改别人内容需要重新验口令",
                errorMessage = null,
                messageSeq = it.messageSeq + 1,
            )
        }
    }

    /**
     * 换入群密码：本地生成 -> 推云端 -> 再从云端 read() 刷新。
     *
     * 展示的必须是云端读回来的值，不是本地生成的那个：
     * 两者不一致时写后回读已经抛错，界面不会显示一个云端并不存在的密码。
     */
    fun rotateJoinPassword() {
        val groupId = _uiState.value.groupId
        if (groupId.isNullOrBlank()) {
            postError("还没有加入群组")
            return
        }
        if (!_uiState.value.adminUnlocked) {
            postError("请先输入管理员口令验证身份")
            return
        }
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            runCatchingCancellable {
                groupRepository.rotateJoinPassword(groupId).getOrThrow()
            }.onSuccess { refreshed ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        joinPassword = refreshed,
                        joinPasswordVisible = true,
                        successMessage = "已换新入群密码，旧密码立刻作废，把它转告要加的人",
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = error.toUserText(),
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }
        }
    }

    /** 重新从云端读一次口令：换设备、别人改过密码后，本机显示的值一定是旧的。 */
    fun reloadConfig() {
        val groupId = _uiState.value.groupId
        if (groupId.isNullOrBlank()) {
            postError("还没有加入群组")
            return
        }
        refreshConfig(groupId)
    }

    /** 手动推一次，让其他人看到最新名单。 */
    fun syncNow() {
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }
                .getOrNull()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    successMessage = outcome?.message ?: "已同步",
                    errorMessage = outcome?.takeIf { o -> !o.isSuccess && !o.skipped }?.message,
                    messageSeq = it.messageSeq + 1,
                )
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    companion object {
        /**
         * 手机号输入框的位数上限。
         *
         * 取 15 是为了容忍 `0086 13800138000` 这种带国家码的粘贴内容；
         * 不是合法性判断，合法性一律由 PhoneIdentity.isValid 说了算。
         */
        private const val MAX_PHONE_INPUT_LENGTH = 15
    }
}
