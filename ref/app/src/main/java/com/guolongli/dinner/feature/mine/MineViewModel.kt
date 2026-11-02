package com.guolongli.dinner.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.data.maintenance.LocalRetentionTask
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.identity.IdentityRepository
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.repository.AppLockRepository
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.GroupRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.feature.pairing.MemberUi
import com.guolongli.dinner.ui.profile.LocalProfileStore
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 「我的」页不可变 UI 状态。
 */
data class MineUiState(
    val isLoading: Boolean = true,
    val myName: String = "我",
    /**
     * 本机作者标识。
     *
     * 初始值是空串而不是占位 "me"（红线）：这个值会写进 `MealRecord.createdBy`
     * 并上云，占位值会让每条记录的作者都查不到成员、一律显示成「成员」。
     * 真实值由 [observeContext] 从 `LocalProfileStore` 推来；空串期间统计一律按 0 算。
     */
    val authorId: String = "",
    val myAvatar: File? = null,
    val members: List<MemberUi> = emptyList(),
    val memberLimit: Int = GroupState.MAX_MEMBERS,
    /** 当前管理员的昵称。 */
    val adminName: String = "我",
    /**
     * 我是不是管理员：决定能否改 / 删别人的内容，也决定能否转让管理员。
     *
     * 初始值必须是 false：真实值由 [observeGroup] 从 `GroupState.amIAdmin` 推来。
     * 写成 true 会让首帧闪出一个并不存在的管理入口，没入组时也会误导用户。
     */
    val amIAdmin: Boolean = false,
    val showAdminDialog: Boolean = false,
    val totalCount: Long = 0,
    val mineCount: Long = 0,
    val pendingCount: Long = 0,
    val syncedCount: Long = 0,
    val isAuthorized: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncMessage: String? = null,
    val isBound: Boolean = false,
    val storageRoot: String = NetdiskPaths.DEFAULT_ROOT,
    val showNameDialog: Boolean = false,
    val showRevokeDialog: Boolean = false,
    val showUnbindDialog: Boolean = false,
    /** 删除群组（只清本机，云端保留）的确认框。 */
    val showDeleteGroupDialog: Boolean = false,
    val showStorageDialog: Boolean = false,
    val storageInput: String = "",
    val storageError: String? = null,
    /** 本机保留天数，0 表示永久保留。 */
    val retentionDays: Int = StorageConfigRepository.DEFAULT_RETENTION_DAYS,
    val showRetentionDialog: Boolean = false,
    /** 与网盘通讯时单次请求最多尝试几次，全部失败才报错。 */
    val networkRetries: Int = StorageConfigRepository.DEFAULT_NETWORK_RETRIES,
    val showNetworkRetriesDialog: Boolean = false,
    /** 清理确认：会真删本机文件，必须先问一次。 */
    val showCleanConfirm: Boolean = false,
    val isCleaning: Boolean = false,
    /** 维护类操作的结论（清理 / 补取），必须如实展示。 */
    val maintenanceMessage: String? = null,
    val showRangeDialog: Boolean = false,
    /** 从云端补取的区间，闭开区间 [rangeStart, rangeEnd)。 */
    val rangeStart: Long = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000,
    val rangeEnd: Long = System.currentTimeMillis(),
    val isRestoring: Boolean = false,
    /** 「打开应用时验证身份」开关。 */
    val appLockEnabled: Boolean = false,
    /** 是否压缩后再存：默认不压，原图原视频。 */
    val compressMedia: Boolean = StorageConfigRepository.DEFAULT_COMPRESS_MEDIA,
    /** 已绑定的手机号掩码（138****8000）；未绑定为 null。 */
    val phoneMask: String? = null,
    val showPhoneDialog: Boolean = false,
    val phoneInput: String = "",
    val phoneError: String? = null,
    val isBindingPhone: Boolean = false,
    /** 当前群组 id（group_<管理员手机号>）；未建群未入群为 null。 */
    val groupId: String? = null,
)

@HiltViewModel
class MineViewModel @Inject constructor(
    private val mealRepository: MealRepository,
    private val syncRepository: SyncRepository,
    private val avatarRepository: AvatarRepository,
    private val credentialStore: CredentialStore,
    private val sessionManager: SessionManager,
    private val pairingRepository: PairingRepository,
    private val storageConfig: StorageConfigRepository,
    private val profileStore: LocalProfileStore,
    private val retentionTask: LocalRetentionTask,
    private val appLockRepository: AppLockRepository,
    private val identityRepository: IdentityRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MineUiState())
    val uiState: StateFlow<MineUiState> = _uiState.asStateFlow()

    init {
        observeContext()
        observeStats()
        observeAuth()
        observeGroup()
        observeGroupId()
        observeAvatars()
        observeStorage()
        observeAppLock()
        observePhone()
    }

    /** 群组状态：没有群组 id 就是还没建群也没入群。 */
    private fun observeGroupId() {
        viewModelScope.launch {
            storageConfig.observeGroupId().collect { groupId ->
                _uiState.update { it.copy(groupId = groupId) }
            }
        }
    }

    private fun observePhone() {
        viewModelScope.launch {
            identityRepository.observePhoneMask().collect { mask ->
                _uiState.update { it.copy(phoneMask = mask) }
            }
        }
    }

    private fun observeAppLock() {
        viewModelScope.launch {
            appLockRepository.observeEnabled().collect { enabled ->
                _uiState.update { it.copy(appLockEnabled = enabled) }
            }
        }
    }

    /**
     * 开关应用锁。
     *
     * 只落开关，真正的验证交给系统：应用自己不保存口令，
     * 因此也不存在「密码忘了打不开」这种把数据锁死的风险。
     */
    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch {
            appLockRepository.setEnabled(enabled)
            _uiState.update {
                it.copy(
                    lastSyncMessage = if (enabled) {
                        "已开启：下次打开应用需要验证身份"
                    } else {
                        "已关闭应用锁"
                    },
                )
            }
        }
    }

    /**
     * 观察身份与昵称，并同步回流到 UI state。
     *
     * 用 [combine] 把 authorId 与「我的昵称」绑定成单个 Flow，再把它的结果
     * 交给统计观察做 combine，消除 authorId 与 stats 各自收集产生的竞态窗口
     * （stats 可能在 authorId 到达前用空标识算错 mineCount 且粘住）。
     */
    private fun observeContext() {
        viewModelScope.launch {
            combine(profileStore.observeAuthorId(), profileStore.observeMyName()) { id, name ->
                id to name
            }.collect { (id, name) ->
                _uiState.update { it.copy(authorId = id, myName = name) }
            }
        }
    }

    private fun observeStats() {
        viewModelScope.launch {
            val authorIdFlow = profileStore.observeAuthorId()
            val recordsFlow = mealRepository.observeAll()
            combine(authorIdFlow, recordsFlow) { id, records ->
                id to records
            }.collect { (currentAuthorId, records) ->
                _uiState.update {
                    it.copy(
                        totalCount = records.size.toLong(),
                        mineCount = records.count { r -> r.createdBy == currentAuthorId }.toLong(),
                        pendingCount = records.count { r ->
                            r.syncState == com.guolongli.dinner.domain.model.SyncState.PENDING ||
                                r.syncState == com.guolongli.dinner.domain.model.SyncState.FAILED
                        }.toLong(),
                        syncedCount = records.count { r ->
                            r.syncState == com.guolongli.dinner.domain.model.SyncState.SYNCED
                        }.toLong(),
                        isLoading = false,
                    )
                }
            }
        }
    }

    /**
     * 观察授权状态。token 存在且未过期才算已授权，与 [SessionManager.isAuthorized]
     * 口径一致（不无视过期时间，否则 UI 会「假装已授权」）。
     */
    private fun observeAuth() {
        viewModelScope.launch {
            credentialStore.observeToken().collect { token ->
                _uiState.update {
                    it.copy(isAuthorized = token != null && !token.isExpired())
                }
            }
        }
    }

    private fun observeGroup() {
        viewModelScope.launch {
            pairingRepository.observe().collect { group ->
                _uiState.update {
                    it.copy(
                        isBound = group.hasOthers,
                        memberLimit = GroupState.MAX_MEMBERS,
                        amIAdmin = group.amIAdmin,
                        // 自己当管理员时显示「我的昵称」，不要显示名单里那份可能过期的名字。
                        adminName = if (group.amIAdmin) {
                            it.myName
                        } else {
                            group.admin?.name?.ifBlank { "成员" } ?: "成员"
                        },
                    )
                }
            }
        }
    }

    // ---------- 管理员 ----------

    fun openAdminDialog() {
        if (!_uiState.value.amIAdmin) return
        _uiState.update { it.copy(showAdminDialog = true) }
    }

    fun closeAdminDialog() {
        _uiState.update { it.copy(showAdminDialog = false) }
    }

    /**
     * 转让管理员。
     *
     * 只有当前管理员能转让；转让后立刻同步一次，把 members.json 里的 admin_id 推上去，
     * 否则别人下次读到的还是旧管理员。
     */
    fun transferAdmin(memberId: String) {
        _uiState.update { it.copy(showAdminDialog = false) }
        // 权限判断在 ViewModel 做（PairingRepository 的约定）：不是管理员就不进流程。
        if (!_uiState.value.amIAdmin) {
            _uiState.update { it.copy(lastSyncMessage = "只有管理员能转让管理权") }
            return
        }
        viewModelScope.launch {
            // 转让前再取一次最新名单复核：远端名单可能刚被别人覆盖，
            // 界面上的 amIAdmin 是上一次收集到的旧值，不能拿它当裁决依据。
            val group = runCatchingCancellable { pairingRepository.current() }.getOrNull()
            if (group == null || !group.amIAdmin) {
                _uiState.update { it.copy(lastSyncMessage = "只有管理员能转让管理权") }
                return@launch
            }
            // 真正落地的是仓储层：它会 requireAdmin()，未验口令一律拒绝。
            // 这里不再自己调 setAdmin，免得绕过解锁校验。
            val result = runCatchingCancellable { groupRepository.transferAdmin(memberId) }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(lastSyncMessage = result.exceptionOrNull()?.toUserText() ?: "转让失败，请稍后重试")
                }
                return@launch
            }
            val name = _uiState.value.members.firstOrNull { it.id == memberId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: group.nameOf(memberId)
            // 同步结果必须如实展示：转让只落本机、没推上云等于别人那边还是旧管理员。
            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }.getOrNull()
            _uiState.update {
                it.copy(
                    lastSyncMessage = if (outcome?.isSuccess == true) {
                        "$name 现在是管理员"
                    } else {
                        "$name 现在是管理员，但名单没推上云盘：${outcome?.message ?: "同步失败"}。请稍后手动同步"
                    },
                )
            }
        }
    }

    private fun observeAvatars() {
        viewModelScope.launch {
            combine(
                avatarRepository.observeAvatars(),
                pairingRepository.observe(),
                profileStore.observeMyName(),
            ) { avatars, group, myName ->
                val list = group.members.map { member ->
                    MemberUi(
                        id = member.id,
                        name = if (member.id == group.myId) myName else member.name.ifBlank { "成员" },
                        isMe = member.id == group.myId,
                        avatar = avatars[member.id],
                    )
                }
                list to avatars[group.myId]
            }.collect { (members, mine) ->
                _uiState.update { it.copy(members = members, myAvatar = mine) }
            }
        }
    }

    private fun observeStorage() {
        viewModelScope.launch {
            storageConfig.observePaths().collect { paths ->
                _uiState.update { it.copy(storageRoot = paths.root) }
            }
        }
        viewModelScope.launch {
            storageConfig.observeRetentionDays().collect { days ->
                _uiState.update { it.copy(retentionDays = days) }
            }
        }
        viewModelScope.launch {
            storageConfig.observeCompressMedia().collect { enabled ->
                _uiState.update { it.copy(compressMedia = enabled) }
            }
        }
        viewModelScope.launch {
            storageConfig.observeNetworkRetries().collect { times ->
                _uiState.update { it.copy(networkRetries = times) }
            }
        }
    }

    // ---------- 压缩存储 ----------

    /**
     * 开关「压缩后存储」。
     *
     * 只影响之后新添加的媒体，已经存下的原文件不会被回头重压——
     * 那等于偷偷降画质，用户根本没法察觉。
     */
    fun setCompressMedia(enabled: Boolean) {
        viewModelScope.launch {
            storageConfig.setCompressMedia(enabled)
            _uiState.update {
                it.copy(
                    lastSyncMessage = if (enabled) {
                        "已开启：之后添加的照片压到长边 1920、视频降到 1280"
                    } else {
                        "已关闭：之后添加的照片与视频按原画质保存"
                    },
                )
            }
        }
    }

    // ---------- 本地保留策略 ----------

    fun openRetentionDialog() {
        _uiState.update { it.copy(showRetentionDialog = true) }
    }

    fun closeRetentionDialog() {
        _uiState.update { it.copy(showRetentionDialog = false) }
    }

    /** 改保留天数。只改「以后怎么清」，不会立刻删东西——真正的清理走 cleanLocalNow。 */
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            storageConfig.setRetentionDays(days)
            _uiState.update {
                it.copy(
                    showRetentionDialog = false,
                    maintenanceMessage = if (days == StorageConfigRepository.PERSIST_FOREVER) {
                        "已改为永久保留，本地不再自动清理"
                    } else {
                        "已改为保留 $days 天，点「立即清理」才会删旧数据"
                    },
                )
            }
        }
    }

    // ---------- 网盘重试 ----------

    fun openNetworkRetriesDialog() {
        _uiState.update { it.copy(showNetworkRetriesDialog = true) }
    }

    fun closeNetworkRetriesDialog() {
        _uiState.update { it.copy(showNetworkRetriesDialog = false) }
    }

    /**
     * 改与网盘通讯的重试次数。
     *
     * 只改「瞬时故障要试几次」，不影响业务性错误——鉴权失败、文件已存在这类
     * 重试多少次结果都一样，从不进重试，否则白白烧掉开发期 100 次/小时的配额。
     */
    fun setNetworkRetries(times: Int) {
        viewModelScope.launch {
            storageConfig.setNetworkRetries(times)
            _uiState.update {
                it.copy(
                    showNetworkRetriesDialog = false,
                    maintenanceMessage = if (times <= StorageConfigRepository.NETWORK_RETRY_MIN) {
                        "已改为不重试：网盘请求失败就直接报错，最省配额"
                    } else {
                        "已改为最多重试 $times 次，都失败才报错"
                    },
                )
            }
        }
    }

    /** 打开清理确认。删本地文件同样要二次确认，不能点一下就动手。 */
    fun openCleanConfirm() {
        _uiState.update { it.copy(showCleanConfirm = true) }
    }

    fun closeCleanConfirm() {
        _uiState.update { it.copy(showCleanConfirm = false) }
    }

    /** 确认清理：先收对话框，再跑任务，避免任务进行中还能重复点。 */
    fun confirmClean() {
        _uiState.update { it.copy(showCleanConfirm = false) }
        cleanLocalNow()
    }

    /**
     * 立即按保留策略清理本地。
     *
     * 云端确认由 LocalRetentionTask 负责：确认不了的一律保留，因此这里不必再问
     * 「删不删」——用户点这一下就是授权清理，而清理只发生在确实已同步到云端的记录上。
     */
    fun cleanLocalNow() {
        if (_uiState.value.isCleaning) return
        _uiState.update { it.copy(isCleaning = true, maintenanceMessage = null) }
        viewModelScope.launch {
            val outcome = runCatchingCancellable { retentionTask.run() }.getOrNull()
            _uiState.update {
                it.copy(
                    isCleaning = false,
                    maintenanceMessage = outcome?.message
                        ?: (sessionManager.lastAuthError ?: "清理失败，请稍后重试"),
                )
            }
        }
    }

    // ---------- 从云端补取一段 ----------

    fun openRangeDialog() {
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                showRangeDialog = true,
                rangeEnd = if (it.rangeEnd > now) now else it.rangeEnd,
                maintenanceMessage = null,
            )
        }
    }

    fun closeRangeDialog() {
        _uiState.update { it.copy(showRangeDialog = false) }
    }

    fun setRangeStart(millis: Long) {
        _uiState.update { it.copy(rangeStart = millis) }
    }

    fun setRangeEnd(millis: Long) {
        _uiState.update { it.copy(rangeEnd = millis) }
    }

    /**
     * 从 records.json 里补回指定区间。
     *
     * 只补记录与评论，不预取媒体：照片 / 视频在时间线里按需下载，
     * 免得一次补一整年把开发期 100 次/小时的配额打空。
     */
    fun restoreRange() {
        val state = _uiState.value
        if (state.isRestoring) return
        if (state.rangeEnd <= state.rangeStart) {
            _uiState.update { it.copy(maintenanceMessage = "结束时间要晚于开始时间") }
            return
        }
        _uiState.update { it.copy(isRestoring = true, maintenanceMessage = null) }
        viewModelScope.launch {
            // restoreRange 返回 Result<Int>：getOrThrow 摊平成单一层，
            // 否则 isSuccess 看的是外层 Result（永远为真），失败会被当成「补回 0 条」。
            val result = runCatchingCancellable {
                syncRepository.restoreRange(state.rangeStart, state.rangeEnd).getOrThrow()
            }
            val count = result.getOrNull() ?: 0
            _uiState.update {
                it.copy(
                    isRestoring = false,
                    showRangeDialog = if (result.isSuccess) false else it.showRangeDialog,
                    maintenanceMessage = if (result.isSuccess) {
                        "已补回 $count 条记录"
                    } else {
                        result.exceptionOrNull()?.toUserText() ?: "从云端取回失败"
                    },
                )
            }
        }
    }

    /**
     * 一次性取回云端全部历史。
     *
     * 与「补一段」的区别只在于范围：这条读整份 records.json，适合换设备、
     * 重装、或者本地被清理过之后一次把时间线补全。请求数固定（一次 list +
     * 一次下载），不会因为记录多而变贵。
     */
    fun restoreAll() {
        if (_uiState.value.isRestoring) return
        _uiState.update {
            it.copy(isRestoring = true, showRangeDialog = false, maintenanceMessage = null)
        }
        viewModelScope.launch {
            // 同上：restoreFromRemote 返回 Result<List<...>>，不摊平就永远看不到失败。
            val result = runCatchingCancellable {
                syncRepository.restoreFromRemote().getOrThrow()
            }
            val count = result.getOrNull()?.size ?: 0
            _uiState.update {
                it.copy(
                    isRestoring = false,
                    maintenanceMessage = when {
                        result.isFailure ->
                            result.exceptionOrNull()?.toUserText() ?: "从云端取回失败"
                        count > 0 -> "已从云端读取 $count 条记录"
                        else -> "云端还没有记录。先在这台设备上记一顿并同步一次"
                    },
                )
            }
        }
    }

    /** 用户从相册挑选头像，压缩并上传，随后同步所有成员的头像。 */
    /**
     * 换头像。
     *
     * 头像文件与成员名单都不在 Room 里，界面不会因为「写完了」自动重绘；
     * 而且失败过去是静默吞掉的——用户点完相册回来，界面毫无变化，
     * 分不清是「还没生效」还是「根本没成功」。这里显式回写提示。
     */
    fun setMyAvatar(uri: android.net.Uri) {
        _uiState.update { it.copy(maintenanceMessage = null) }
        viewModelScope.launch {
            // setMyAvatar 返回 Result<Unit>：getOrThrow 摊平，
            // 双层 Result 会让 isSuccess 恒真，保存失败就显示不出来了。
            val saved = runCatchingCancellable { avatarRepository.setMyAvatar(uri).getOrThrow() }
            if (saved.isFailure) {
                _uiState.update {
                    it.copy(
                        maintenanceMessage = "头像保存失败：${saved.exceptionOrNull()?.toUserText()}",
                    )
                }
                return@launch
            }
            // 本机头像已换：先让界面按本地文件重绘，再尝试上传。
            _uiState.update { it.copy(maintenanceMessage = "头像已更新，正在同步到网盘") }
            val ids = avatarTargets()
            if (ids.isEmpty()) {
                _uiState.update {
                    it.copy(
                        maintenanceMessage = "头像已在本机更新，但读不到成员名单，暂时无法上传到网盘",
                    )
                }
                return@launch
            }
            // syncAvatars 返回 Boolean（不是 Result）：只有明确拿到 true 才算同步成功，
            // 判 Result 的 isSuccess 会永远为真，云端没推上去却显示「已同步」。
            val uploaded = runCatchingCancellable {
                avatarRepository.syncAvatars(ids)
            }.getOrNull() == true
            _uiState.update {
                it.copy(
                    maintenanceMessage = if (uploaded) {
                        "头像已更新并同步到网盘"
                    } else {
                        "头像已在本机更新，但同步到网盘失败，稍后会自动重试"
                    },
                )
            }
        }
    }

    /**
     * 上传头像时要带上谁，优先按最新的成员名单。
     *
     * 界面上的 members 可能还是空表（名单流还没到），传空列表下去等于
     * 谁的头像都不上传、自己的也漏掉；这里回落到仓储里的当前名单，
     * 再不行至少带上自己的 id。
     */
    private suspend fun avatarTargets(): List<String> {
        val group = runCatchingCancellable { pairingRepository.current() }.getOrNull()
        val others = group?.members?.map { it.id }?.filter { it.isNotBlank() }.orEmpty()
        val me = _uiState.value.authorId.takeIf { it.isNotBlank() }
        return (others + listOfNotNull(me)).distinct()
    }

    fun setName(name: String) {
        if (name.isBlank()) return
        _uiState.update { it.copy(showNameDialog = false) }
        viewModelScope.launch {
            val trimmed = name.trim()
            // 落库与改名都可能抛（DataStore / Room 异常会冒泡到没有异常处理器的
            // viewModelScope 里把应用带崩），这里统一兜住并如实回写。
            val result = runCatchingCancellable {
                profileStore.saveMyName(trimmed)
                // 成员名单里的「我」同步改名，否则别人看到的还是旧名字。
                val group = pairingRepository.current()
                val me = group.memberOf(group.myId)
                pairingRepository.upsertMember(
                    Member(
                        id = group.myId,
                        name = trimmed,
                        avatarPath = me?.avatarPath,
                        joinedAt = me?.joinedAt ?: System.currentTimeMillis(),
                    )
                ).getOrThrow()
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(maintenanceMessage = "昵称没能保存：${result.exceptionOrNull()?.toUserText()}")
                }
                return@launch
            }
            // 顺手推一次：不推的话，其他成员要等下一次同步才看到新名字。
            runCatchingCancellable { syncRepository.syncNow(auto = true) }
        }
    }

    fun openNameDialog() {
        _uiState.update { it.copy(showNameDialog = true) }
    }

    fun closeNameDialog() {
        _uiState.update { it.copy(showNameDialog = false) }
    }

    // ---------- 身份手机号 ----------

    fun openPhoneDialog() {
        _uiState.update {
            it.copy(showPhoneDialog = true, phoneInput = "", phoneError = null)
        }
    }

    fun closePhoneDialog() {
        if (_uiState.value.isBindingPhone) return
        _uiState.update { it.copy(showPhoneDialog = false, phoneError = null) }
    }

    fun setPhoneInput(value: String) {
        // 数字系统先统一成 ASCII（阿拉伯-印度 / 波斯数字），再去掉非数字、限 11 位；
        // 粘贴带 +86 / 空格的号码也能用。只 `filter { it.isDigit() }` 会把
        // `١٣٨٠٠١٣٨٠٠٠` 原样留下，而 PhoneIdentity.normalize 只认 ASCII，
        // 用户填了自己真实的号码却一直提示格式不对。
        val filtered = PhoneIdentity.toAsciiDigits(value)
            .filter { it in '0'..'9' }
            .take(PHONE_LENGTH)
        _uiState.update { it.copy(phoneInput = filtered, phoneError = null) }
    }

    /**
     * 绑定手机号。
     *
     * 成功与否都要说清楚做了什么：迁了几条旧记录、拉回几条历史。
     * 只说「绑定成功」会让用户在历史没回来时无从判断。
     */
    fun confirmBindPhone() {
        val input = _uiState.value.phoneInput
        if (!PhoneIdentity.isValid(input)) {
            _uiState.update { it.copy(phoneError = "请填写 11 位手机号") }
            return
        }
        _uiState.update { it.copy(isBindingPhone = true, phoneError = null) }
        viewModelScope.launch {
            identityRepository.bind(input).fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            isBindingPhone = false,
                            showPhoneDialog = false,
                            phoneMask = result.phoneMask,
                            maintenanceMessage = buildString {
                                append("已绑定 ")
                                append(result.phoneMask)
                                if (result.migratedRecords > 0) {
                                    append("，${result.migratedRecords} 条旧记录已归到这个身份")
                                }
                                if (result.restoredRecords > 0) {
                                    append("，从云端取回 ${result.restoredRecords} 条历史")
                                }
                                if (!result.pushed) {
                                    append("。暂未上传，联网后会自动同步")
                                }
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isBindingPhone = false,
                            phoneError = error.message ?: "绑定失败，请重试",
                        )
                    }
                },
            )
        }
    }

    fun openRevokeDialog() {
        _uiState.update { it.copy(showRevokeDialog = true) }
    }

    fun closeRevokeDialog() {
        _uiState.update { it.copy(showRevokeDialog = false) }
    }

    fun openUnbindDialog() {
        _uiState.update { it.copy(showUnbindDialog = true) }
    }

    fun closeUnbindDialog() {
        _uiState.update { it.copy(showUnbindDialog = false) }
    }

    // ---------- 存储位置 ----------

    fun openStorageDialog() {
        _uiState.update {
            it.copy(
                showStorageDialog = true,
                storageInput = it.storageRoot,
                storageError = null,
            )
        }
    }

    fun closeStorageDialog() {
        _uiState.update { it.copy(showStorageDialog = false, storageError = null) }
    }

    fun setStorageInput(value: String) {
        _uiState.update { it.copy(storageInput = value, storageError = null) }
    }

    /**
     * 改存储位置。
     *
     * 只改「以后写哪里」，不会搬动网盘上已有的文件：旧目录里的记录需要靠
     * 重新同步才能看到，因此改完提示用户手动同步一次。
     */
    fun saveStorageRoot() {
        val input = _uiState.value.storageInput
        viewModelScope.launch {
            storageConfig.setRoot(input)
                .onSuccess { paths ->
                    _uiState.update {
                        it.copy(
                            showStorageDialog = false,
                            storageRoot = paths.root,
                            storageError = null,
                            lastSyncMessage = "存储位置已改为 ${paths.root}，旧目录的数据需要重新同步才会出现",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(storageError = error.message ?: "路径不合法")
                    }
                }
        }
    }

    fun resetStorageRoot() {
        viewModelScope.launch {
            storageConfig.resetToDefault()
            _uiState.update {
                it.copy(
                    showStorageDialog = false,
                    storageInput = NetdiskPaths.DEFAULT_ROOT,
                    storageError = null,
                )
            }
        }
    }

    /** 解除授权前先取消同步，防止继续用旧 token 上传。 */
    fun revokeAuth() {
        _uiState.update { it.copy(showRevokeDialog = false) }
        viewModelScope.launch {
            runCatchingCancellable { syncRepository.cancelAll() }
            sessionManager.signOut()
        }
    }

    fun unbind() {
        _uiState.update { it.copy(showUnbindDialog = false) }
        viewModelScope.launch {
            runCatchingCancellable { pairingRepository.unbind() }
        }
    }

    // ---------- 删除群组 ----------

    fun openDeleteGroupDialog() {
        _uiState.update { it.copy(showDeleteGroupDialog = true, maintenanceMessage = null) }
    }

    fun closeDeleteGroupDialog() {
        _uiState.update { it.copy(showDeleteGroupDialog = false) }
    }

    /**
     * 删除群组：**只清本机状态，云端目录与文件一律不动**。
     *
     * 清三样东西：
     * 1. 成员名单 / admin_id / 绑定时间（`pairingRepository.unbind()`）；
     * 2. `groupId`（`storageConfig.setGroupId(null)`）—— 不清它的话，界面仍停在原群组目录，
     *    时间线还会显示「已入群」，用户以为删干净了，实际什么都没有变；
     * 3. 界面状态回到「未建群」。
     *
     * 明确不做的：不删云端 `group_<号码>/` 下的任何文件。删除云端是不可逆操作，
     * 且会影响同群的其他成员——这一档只表达「这台设备不再属于这个群」。
     * 想回到群里，重新用入群密码加入即可，历史记录会自动拉回。
     */
    fun deleteGroup() {
        val groupId = _uiState.value.groupId
        _uiState.update { it.copy(showDeleteGroupDialog = false, maintenanceMessage = null) }
        viewModelScope.launch {
            val cleared = runCatchingCancellable {
                pairingRepository.unbind()
                storageConfig.setGroupId(null)
            }
            if (cleared.isFailure) {
                _uiState.update {
                    it.copy(
                        maintenanceMessage = cleared.exceptionOrNull()?.let { e ->
                            "删除群组失败：${e.message?.takeIf { m -> m.isNotBlank() } ?: "请重试"}"
                        } ?: "删除群组失败，请重试",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    groupId = null,
                    isBound = false,
                    members = emptyList(),
                    adminName = "",
                    amIAdmin = false,
                    pendingCount = 0,
                    lastSyncMessage = null,
                    maintenanceMessage = buildString {
                        append("已退出群组")
                        if (!groupId.isNullOrBlank()) append("（$groupId）")
                        append("。云端文件没有删除，重新用入群密码加入即可回到群里")
                    },
                )
            }
        }
    }

    /** 手动触发同步。手动同步会重试 FAILED 记录。 */
    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, lastSyncMessage = null) }
            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }.getOrNull()
            when {
                outcome == null -> _uiState.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncMessage = sessionManager.lastAuthError ?: "同步失败",
                    )
                }
                outcome.skipped -> _uiState.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncMessage = outcome.message ?: "未同步",
                    )
                }
                outcome.isSuccess -> _uiState.update {
                    it.copy(isSyncing = false, lastSyncMessage = "同步完成")
                }
                else -> _uiState.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncMessage = outcome.message
                            ?: "部分同步失败，${outcome.failed} 条待手动重试",
                    )
                }
            }
        }
    }

    private companion object {
        /** 中国大陆手机号位数。 */
        const val PHONE_LENGTH = 11
    }
}

private fun MutableStateFlow<MineUiState>.update(transform: (MineUiState) -> MineUiState) {
    value = transform(value)
}
