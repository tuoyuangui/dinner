package com.guolongli.dinner.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.BuildConfig
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.core.Graph
import com.guolongli.dinner.data.DeleteOutcome
import com.guolongli.dinner.data.DeleteScope
import com.guolongli.dinner.data.MediaInput
import com.guolongli.dinner.data.identity.PhoneIdentity
import com.guolongli.dinner.data.location.GeoPoint
import com.guolongli.dinner.data.media.ImportedMedia
import com.guolongli.dinner.data.sync.SyncReason
import com.guolongli.dinner.data.sync.SyncUiState
import com.guolongli.dinner.ui.model.MemberUiModel
import com.guolongli.dinner.ui.model.RecordType
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.StatScope
import com.guolongli.dinner.ui.model.StatUiModel
import com.guolongli.dinner.ui.nav.DinnerRoute
import com.guolongli.dinner.ui.nav.Navigator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用唯一的状态持有者。
 *
 * 为什么把状态放在这里、而不是写在 `MainActivity` 的 Composable 里：
 * 1. 转屏、分屏、改字号都会重建 Activity，`remember` 全部丢失；
 *    ViewModel 活得比 Activity 久。
 * 2. 页面只描述「长什么样 + 点下去调哪个方法」，不认识 Room、网盘、权限。
 * 3. 同一条数据（比如成员列表）被三个页面用，只有一份来源。
 *
 * 这里只做「编排」：读写都转手给仓储 / 同步引擎 / 群组服务，不实现业务规则本身。
 */
class DinnerViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application get() = getApplication()

    // ---------------------------------------------------------------- 依赖
    private val repository = Graph.repository
    private val autoSync = Graph.autoSync
    private val settings = Graph.settings
    private val profile = Graph.profile
    private val avatars = Graph.avatarRepository
    private val mediaCache = Graph.mediaCache
    private val admin = Graph.adminSession
    private val group = Graph.groupService
    private val location = Graph.locationProvider

    /** 返回栈。Compose 侧只读 [Navigator.current] */
    val navigator = Navigator()

    // ---------------------------------------------------------------- 数据流
    val records: StateFlow<List<RecordUiModel>> = repository.timeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summary: StateFlow<String> = repository.summary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val stats: StateFlow<List<StatUiModel>> = repository.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val members: StateFlow<List<MemberUiModel>> = repository.members()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<Int> = repository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncState: StateFlow<SyncUiState> = autoSync.state

    // ---------------------------------------------------------------- 本机设置
    var displayName by mutableStateOf(profile.displayName)
        private set

    /** 昵称是否已改过（新手引导第 1 步用） */
    val nameSet: Boolean get() = profile.hasCustomName

    /**
     * 新手引导是否已走完第一遍。走完就不再自动判断和显示，
     * 内容收进「我的 → 新手引导」常驻入口，想看随时看。
     */
    var onboardingDone by mutableStateOf(profile.onboardingDone)
        private set

    /** 三步齐了调用一次：记住「带路已完成」，引导卡从此不再自动出现 */
    fun finishOnboarding() {
        if (profile.onboardingDone) return
        profile.onboardingDone = true
        onboardingDone = true
        message = "新手引导已完成，可在「我的 → 新手引导」随时查看"
    }

    var avatarPath by mutableStateOf(profile.avatarPathOf(profile.memberId))
        private set

    var groupName by mutableStateOf(profile.groupName)
        private set

    var joinCode by mutableStateOf(profile.joinCode)
        private set

    var adminCode by mutableStateOf(profile.adminCode)
        private set

    /** 本机这个人的手机号，未绑定时为空串 */
    var phone by mutableStateOf(profile.phone)
        private set

    /** 掩码后的号码，界面直接显示它，绝不整段显示 */
    val phoneMask: String get() = if (phone.isBlank()) "" else PhoneIdentity.mask(phone)

    /** 建组者号码（掩码） */
    var adminPhoneMask by mutableStateOf(
        profile.adminPhone.takeIf { it.isNotBlank() }?.let { PhoneIdentity.mask(it) }.orEmpty(),
    )
        private set

    /** 是否已经入群。没入群也能用（数据照样存自己的网盘），只是名单为空 */
    var paired by mutableStateOf(profile.isPaired)
        private set

    var retentionDays by mutableStateOf(settings.retentionDays)
        private set

    var compressEnabled by mutableStateOf(settings.compressEnabled)
        private set

    var appLockEnabled by mutableStateOf(settings.appLockEnabled)
        private set

    /** 主题：浅色 / 深色 / 跟随系统（SettingsStore.themeMode 的界面态） */
    var themeMode by mutableStateOf(settings.themeMode)
        private set

    /** 管理员口令是否已在本机解锁（只在内存里，杀进程即失效） */
    val adminUnlocked: StateFlow<Boolean> = admin.unlocked

    /** 本机是不是管理员 */
    val iAmAdmin: Boolean get() = admin.iAmAdmin

    val memberLimit: Int get() = BaiduConfig.MAX_MEMBERS

    /**
     * 这条记录能不能改。界面用它决定按钮灰不灰，但真正的门槛在仓储里——
     * 藏起按钮不算权限，详见 [com.guolongli.dinner.data.session.AdminSession]。
     */
    fun canManage(authorId: String): Boolean = admin.canManage(authorId)

    /** 能不能动云端那份文件：普通成员只限自己上传的 */
    fun canTouchCloud(authorId: String): Boolean = admin.canTouchCloud(authorId)

    /** 应用锁屏：进入前台且开了锁就置 true */
    var locked by mutableStateOf(settings.appLockEnabled)
        private set

    /** 一次性提示语。界面弹完调用 [consumeMessage] 清掉 */
    var message by mutableStateOf<String?>(null)
        private set

    /** 正在做长任务（整理云端 / 补回媒体 / 建组），按钮据此置灰 */
    var busy by mutableStateOf(false)
        private set

    /**
     * 编辑页刚取到的位置。放在 ViewModel 而不是编辑页的 `remember` 里：
     * 转屏会重建 Composable，用 `remember` 的话刚取到的位置会丢，用户还得再点一次。
     */
    var pendingLocation by mutableStateOf<GeoPoint?>(null)
        private set

    private var wentBackground = false
    private var started = false

    // ---------------------------------------------------------------- 生命周期

    /** 首次进入：清掉旧版示例记录、刷待传条数、把本机头像补传一次 */
    fun onStart() {
        if (started) return
        started = true
        viewModelScope.launch {
            // 旧版本会往空库里写三条示例记录；这一版起时间线只留真实内容，
            // 装过旧版的设备在这里做一次性善后（连云端一起删）。
            repository.removeSeedSamples()
            autoSync.refreshPending()
            if (Graph.isAuthorized()) {
                if (profile.avatarPathOf(profile.memberId) != null) {
                    runCatching { avatars.uploadMine() }
                }
                // 名单是「谁在这个群」的唯一真相，进应用先对齐一次
                runCatching { group.syncMembers() }
            }
        }
    }

    fun onForeground() {
        // 先刷授权标志：刚在网盘页授权完回来，state 里可能还挂着旧的「未授权」，
        // 不刷的话「我的」页一直显示未授权，用户以为授权失败
        autoSync.refreshAuthorized()
        autoSync.request(SyncReason.STARTUP)
        autoSync.refreshPending()
        refreshLocalIdentity()
        if (wentBackground && settings.appLockEnabled) locked = true
        wentBackground = false
        // 别人在另一台设备换了管理员口令，这边的解锁态要立刻作废
        viewModelScope.launch {
            val revoked = runCatching { group.refreshAdminState() }.getOrDefault(false)
            if (revoked) message = "管理员口令已在别的设备更换，需要重新输入"
        }
    }

    fun onBackground() {
        wentBackground = true
    }

    /**
     * 别处（另一台设备同步下来 / 管理页改过）动了群组配置，
     * 回到前台要把本机这几份快照重新读一遍，否则界面还显示旧口令。
     */
    fun refreshLocalIdentity() {
        displayName = profile.displayName
        avatarPath = profile.avatarPathOf(profile.memberId)
        onboardingDone = profile.onboardingDone
        groupName = profile.groupName
        joinCode = profile.joinCode
        adminCode = profile.adminCode
        phone = profile.phone
        adminPhoneMask = profile.adminPhone.takeIf { it.isNotBlank() }
            ?.let { PhoneIdentity.mask(it) }.orEmpty()
        paired = profile.isPaired
    }

    fun consumeMessage() {
        message = null
    }

    // ---------------------------------------------------------------- 记录

    fun addRecord(type: RecordType, note: String, rating: Int, media: List<MediaInput>) {
        val geo = pendingLocation
        viewModelScope.launch {
            // 先落本地再返回：断网也不会丢，上传交给发件箱
            repository.addRecord(
                type = type,
                note = note,
                rating = rating,
                media = media,
                latitude = geo?.latitude,
                longitude = geo?.longitude,
                place = geo?.displayText,
            )
            pendingLocation = null
            navigator.popTo(DinnerRoute.Timeline)
        }
    }

    fun addComment(recordId: String, text: String) {
        viewModelScope.launch { repository.addComment(recordId, text) }
    }

    /** 删评论：自己的随便删，别人的要先解锁管理员（判定在仓储层） */
    fun deleteComment(recordId: String, commentId: String) {
        viewModelScope.launch {
            val ok = repository.deleteComment(recordId, commentId)
            if (!ok) message = "这条评论已经不在了"
        }
    }

    fun setRating(recordId: String, stars: Int) {
        viewModelScope.launch { repository.setRating(recordId, stars) }
    }

    fun toggleStar(recordId: String) {
        viewModelScope.launch { repository.toggleStar(recordId) }
    }

    /**
     * 删除。三档在仓储里落到存储上，这里只负责结果提示——
     * 权限判定同样在仓储层（[com.guolongli.dinner.data.session.AdminSession]）。
     */
    fun deleteRecord(recordId: String, scope: DeleteScope) {
        viewModelScope.launch {
            when (repository.deleteRecord(recordId, scope)) {
                DeleteOutcome.DONE -> {
                    message = when (scope) {
                        DeleteScope.LOCAL -> "已从本机删除，云端不受影响"
                        DeleteScope.CLOUD -> "已删除云端文件"
                        DeleteScope.BOTH -> "已删除，正在同步给其他人"
                    }
                    if (navigator.current is DinnerRoute.Detail) navigator.pop()
                }

                DeleteOutcome.NOT_FOUND -> message = "这条记录已经不在了"
                DeleteOutcome.NOT_ALLOWED -> message = "只能修改或删除自己的记录；管理员请先在「群组」解锁"
                DeleteOutcome.CLOUD_NOT_ALLOWED -> message = "只能删除自己上传的云端文件"
            }
        }
    }

    /** 上传失败的记录手动重试一次 */
    fun retryFailed() {
        viewModelScope.launch {
            busy = true
            val report = runCatching { repository.retryFailed() }.getOrNull()
            busy = false
            message = when {
                report == null -> "重试失败，检查一下网络"
                report.failed == 0 -> "已全部上传"
                else -> "还有 ${report.failed} 条没传成：${report.errors.firstOrNull().orEmpty()}"
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            busy = true
            val report = runCatching { autoSync.run(SyncReason.MANUAL, force = true) }.getOrNull()
            busy = false
            message = report?.summary() ?: "同步失败，检查网络或授权"
        }
    }

    // ---------------------------------------------------------------- 媒体

    /**
     * 相册 Uri → 应用私有目录里的一份文件。
     * 返回的是 [ImportedMedia]（还没进库），因为编辑页要把这几份放在内存里让用户增删。
     */
    suspend fun importMedia(uri: Uri, compress: Boolean): ImportedMedia? =
        mediaCache.import(appContext.contentResolver, uri, compress)

    /** 选一张图当头像：裁方 → 存本地 → 传网盘（没授权就先留着，下次同步补） */
    fun onAvatarPicked(uri: Uri) {
        viewModelScope.launch {
            val path = avatars.importMine(appContext.contentResolver, uri)
            if (path == null) {
                message = "这张图读不出来，换一张试试"
                return@launch
            }
            avatarPath = path
            if (avatars.uploadMine() == null) {
                message = "头像已保存，联网后自动上传"
            } else {
                // 传完了立刻把新头像地址写进成员名单并推到云端，
                // 别人那边才拿得到新地址；不然要等下一轮名单同步才见得到
                runCatching { group.syncMembers() }
            }
            autoSync.request(SyncReason.MANUAL)
        }
    }

    fun restoreMedia() {
        viewModelScope.launch {
            busy = true
            autoSync.run(SyncReason.MANUAL, force = true)
            // 「补回文件」= 全量取回：不设时间窗，把云端有、本地没有的都下回来
            val restored = runCatching { Graph.mediaRestorer.restoreAll() }.getOrDefault(0)
            busy = false
            message = if (restored > 0) "补回 $restored 个文件" else "没有需要补回的文件"
        }
    }

    /**
     * 按需取回单个媒体：点开还没下载的图片/视频时调用。
     * 已在本机的直接返回本地路径；否则从云端下载后再返回，失败返回 null。
     */
    suspend fun fetchMedia(recordId: String, position: Int): String? =
        runCatching { Graph.mediaRestorer.restoreOne(recordId, position) }.getOrNull()

    fun sweepCloud() {
        if (!Graph.isAuthorized()) {
            message = "先连上网盘才能整理云端"
            return
        }
        viewModelScope.launch {
            busy = true
            val report = runCatching { Graph.sweepCloudIfDue(force = true) }.getOrNull()
            busy = false
            message = report?.summary() ?: "整理失败，稍后再试"
        }
    }

    // ---------------------------------------------------------------- 位置

    /**
     * 取一次当前位置，取到就挂到待保存的记录上。
     *
     * 权限由编辑页在点按钮时申请（见 `EditScreen`），这里只管取；
     * 没权限 / 取不到就返回 null，让调用方提示一句，保存记录照样成功。
     */
    fun attachLocation(onResult: (GeoPoint?) -> Unit = {}) {
        viewModelScope.launch {
            val point = runCatching { location.current() }.getOrNull()
            pendingLocation = point
            if (point == null) message = "暂时取不到位置，可以直接保存"
            onResult(point)
        }
    }

    fun clearLocation() {
        pendingLocation = null
    }

    fun hasLocationPermission(): Boolean = location.hasPermission()

    // ---------------------------------------------------------------- 本机设置

    fun rename(value: String) {
        profile.displayName = value
        displayName = profile.displayName
        viewModelScope.launch {
            // 改完立刻推名单（新昵称进 members.json）并同步一轮，
            // 家人那边才能马上看到新名字——不是等下一次周期任务
            runCatching { group.syncMembers() }
            autoSync.request(SyncReason.MANUAL)
        }
    }

    fun setRetention(days: Int) {
        settings.retentionDays = days
        retentionDays = days
        if (days <= 0) {
            message = "已改为永久保留，本机不再自动清理旧照片"
            return
        }
        viewModelScope.launch {
            // force：用户刚点了这一档，就该立刻看到效果，不能等到明天那轮同步
            val removed = runCatching { Graph.cleanupLocalIfDue(force = true) }.getOrDefault(0)
            message = if (removed > 0) {
                "已清理 $removed 个本机文件，云端和记录都还在"
            } else {
                "没有超过 $days 天、且已上传到云端的本机文件"
            }
        }
    }

    fun setCompress(enabled: Boolean) {
        settings.compressEnabled = enabled
        compressEnabled = enabled
    }

    /** 切主题：存设置 + 刷界面态。真正的换色在 DinnerTheme 里按这个值走 */
    fun changeThemeMode(mode: String) {
        settings.themeMode = mode
        themeMode = mode
    }

    /**
     * 应用锁开关。开启时会先确认设备上有可用的系统凭证——
     * 没有的话开了也验不了，只会把人挡在自己的记录外面。
     */
    fun setAppLock(enabled: Boolean) {
        if (enabled && !hasDeviceCredential()) {
            message = "这台手机还没设置锁屏密码或指纹，先在系统设置里设一个"
            return
        }
        settings.appLockEnabled = enabled
        appLockEnabled = enabled
        locked = false
    }

    private fun hasDeviceCredential(): Boolean = runCatching {
        val guard = appContext.getSystemService(android.content.Context.KEYGUARD_SERVICE)
            as? android.app.KeyguardManager
        val secure = guard?.isDeviceSecure == true
        val biometric = androidx.biometric.BiometricManager.from(appContext)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        secure || biometric
    }.getOrDefault(false)

    /** 身份已验证（指纹 / 人脸 / 锁屏密码都走这里）：开锁 */
    fun unlockApp() {
        locked = false
    }

    /** 设备没有可用凭证：不能把用户锁在自己的记录外面 */
    fun skipLock() {
        locked = false
        if (settings.appLockEnabled) {
            settings.appLockEnabled = false
            appLockEnabled = false
            message = "这台手机没有可用的锁屏验证，应用锁已自动关闭"
        }
    }

    // ---------------------------------------------------------------- 群组

    /**
     * 建组：手机号 + 管理员口令。
     * 口令先读云端比对（别人在别处换过就立刻失效），所以这里是异步的。
     */
    fun createGroup(rawPhone: String, rawAdminPassword: String) {
        viewModelScope.launch {
            busy = true
            runCatching { group.createGroup(rawPhone, rawAdminPassword) }
                .onSuccess { result ->
                    refreshLocalIdentity()
                    message = if (result.synced) {
                        "群组已建好，入群密码 ${result.joinCode}"
                    } else {
                        "群组已建在本机，入群密码 ${result.joinCode}；联网后自动同步"
                    }
                }
                .onFailure { message = it.message ?: "建组失败" }
            busy = false
        }
    }

    fun joinGroup(rawPhone: String, rawAdminPhone: String, joinPassword: String) {
        viewModelScope.launch {
            busy = true
            runCatching { group.joinGroup(rawPhone, rawAdminPhone, joinPassword) }
                .onSuccess { result ->
                    refreshLocalIdentity()
                    message = if (result.synced) "已加入群组" else "已加入群组，联网后同步名单"
                    autoSync.request(SyncReason.AUTH)
                }
                .onFailure { message = it.message ?: "加入失败" }
            busy = false
        }
    }

    fun bindPhone(rawPhone: String) {
        viewModelScope.launch {
            busy = true
            runCatching { group.bindPhone(rawPhone) }
                .onSuccess {
                    refreshLocalIdentity()
                    message = "手机号已绑定：${phoneMask}"
                }
                .onFailure { message = it.message ?: "绑定失败" }
            busy = false
        }
    }

    /** 解锁管理员：先读云端 passwd.json 再比对 */
    fun unlockAdmin(input: String) {
        viewModelScope.launch {
            val ok = runCatching { group.verifyAdmin(input) }.getOrDefault(false)
            if (!ok) message = "口令不对（或已在别的设备更换），再试一次"
        }
    }

    fun lockAdmin() = admin.lock()

    fun updateGroupName(value: String) {
        viewModelScope.launch {
            runCatching { group.updateGroupName(value) }
                .onSuccess { ok ->
                    refreshLocalIdentity()
                    if (!ok) message = "群名已存在本机，联网后自动同步"
                }
                .onFailure { message = it.message ?: "改群名失败" }
        }
    }

    fun updateJoinCode(value: String) {
        viewModelScope.launch {
            runCatching { group.updateJoinCode(value) }
                .onSuccess { ok ->
                    refreshLocalIdentity()
                    if (!ok) message = "入群密码已存在本机，联网后自动同步"
                }
                .onFailure { message = it.message ?: "换密码失败" }
        }
    }

    fun updateAdminCode(value: String) {
        viewModelScope.launch {
            runCatching { group.updateAdminCode(value) }
                .onSuccess { ok ->
                    refreshLocalIdentity()
                    if (!ok) message = "管理员口令已存在本机，联网后自动同步"
                }
                .onFailure { message = it.message ?: "改口令失败" }
        }
    }

    /** 换一个新的动态入群密码（加人前重新生成，用完即换；要先解锁管理员） */
    fun rotateJoinCode() {
        val code = runCatching { group.rotateJoinCode() }.getOrNull()
        if (code == null) {
            message = "请先输入管理员口令解锁，再换入群密码"
            return
        }
        joinCode = code
        updateJoinCode(code)
    }

    /** 转让管理权：只有解锁后的现任管理员能做 */
    fun transferAdmin(memberId: String, memberName: String) {
        viewModelScope.launch {
            runCatching { group.transferAdmin(memberId) }
                .onSuccess { message = "管理权已转给 $memberName" }
                .onFailure { message = it.message ?: "转让失败" }
            refreshLocalIdentity()
        }
    }

    // ---------------------------------------------------------------- 展示文案

    /** 「我的 → 网盘连接」右侧那行小字 */
    fun netdiskStatusText(state: SyncUiState, pendingCount: Int): String = when {
        !state.authorized -> "未授权，数据只在本机"
        state.running -> "同步中…"
        pendingCount > 0 -> "$pendingCount 条待上传"
        state.errors.isNotEmpty() -> "上次 ${state.errors.size} 条没传成"
        state.lastAt == 0L -> "尚未同步"
        else -> "上次同步 ${timeLabel(state.lastAt)}"
    }

    fun versionLabel(authorized: Boolean): String =
        "食伴 ${BuildConfig.VERSION_NAME} · " + if (authorized) "百度网盘已授权" else "百度网盘未授权"

    /**
     * 明细页要的那批记录。
     *
     * 不在这里自己 filter：「已同步」要看记录在库里的同步状态，那是仓储的解释权。
     */
    fun recordsFlow(scope: StatScope): Flow<List<RecordUiModel>> = repository.records(scope)

    private fun timeLabel(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
