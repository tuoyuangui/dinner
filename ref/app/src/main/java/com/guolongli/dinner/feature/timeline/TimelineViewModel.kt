package com.guolongli.dinner.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.maintenance.LocalRetentionTask
import com.guolongli.dinner.data.session.AdminGate
import com.guolongli.dinner.domain.model.Comment
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.CommentRepository
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.MediaCache
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.components.CommentUi
import com.guolongli.dinner.ui.components.MealCardUi
import com.guolongli.dinner.ui.components.MediaCardUi
import com.guolongli.dinner.ui.profile.LocalProfileStore
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.util.DateText
import com.guolongli.dinner.ui.util.DayGroup
import com.guolongli.dinner.ui.util.durationTextOf
import com.guolongli.dinner.ui.util.mealLabelOf
import com.guolongli.dinner.ui.util.runCatchingCancellable
import com.guolongli.dinner.ui.util.toUserText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 时间线的筛选范围，相当于一条「索引条」。
 *
 * 单选用法：点「星标」只看星标，点某个类型只看这个类型，点「全部」回到全量。
 * 不做多选——手机上多选筛选的状态组合很容易让人迷路。
 */
sealed interface TimelineScope {
    val label: String

    data object All : TimelineScope {
        override val label: String get() = "全部"
    }

    data object Marked : TimelineScope {
        override val label: String get() = "星标"
    }

    data class Type(val type: MealType) : TimelineScope {
        override val label: String get() = mealLabelOf(type)
    }
}

/** 索引条上的一个可点项。 */
data class ScopeChip(
    val label: String,
    val scope: TimelineScope,
    val selected: Boolean,
)

/** 月份索引：点一下滚到该月的第一条。 */
data class MonthIndex(
    val label: String,
    val itemIndex: Int,
)

/**
 * 时间线条目：首屏摘要卡、日期头、或一张卡片。
 */
sealed interface TimelineEntry {
    /** 顶部摘要：一共记了多少条、跨越多少天。 */
    data class Hero(val count: Int, val days: Int, val subtitle: String) : TimelineEntry
    data class Header(val title: String, val subtitle: String?, val dayKey: String) : TimelineEntry
    data class Card(val item: MealCardUi) : TimelineEntry
}

/**
 * 视频播放状态。path 非空时弹出全屏播放器。
 */
data class VideoPlayback(
    val path: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/** 评论面板状态。mealId 为空表示面板关闭。 */
data class CommentSheetState(
    val mealId: String,
    val title: String,
    val comments: List<CommentUi> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 待删除的记录。
 *
 * canDeleteCloud = 本人 或 管理员：只有这两类人能删云端（红线：只能删自己上传的，
 * 管理员是唯一例外，因为「管理员可以修改所有数据」）。
 */
data class DeleteTarget(
    val id: String,
    val isMine: Boolean,
    val canDeleteCloud: Boolean,
)

/**
 * 时间线页不可变 UI 状态。
 */
data class TimelineUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val errorMessage: String? = null,
    val entries: List<TimelineEntry> = emptyList(),
    /** 本机是否有任何记录（不受筛选影响），用于区分「没记录」与「筛选后为空」。 */
    val hasRecords: Boolean = false,
    val totalCount: Int = 0,
    val totalDays: Int = 0,
    val scope: TimelineScope = TimelineScope.All,
    val scopeChips: List<ScopeChip> = emptyList(),
    val months: List<MonthIndex> = emptyList(),
    val player: VideoPlayback = VideoPlayback(),
    val commentSheet: CommentSheetState? = null,
    /** 待确认删除的评论：删除评论也要二次确认，不能一点就没。 */
    val pendingCommentDelete: CommentUi? = null,
    val deleteTarget: DeleteTarget? = null,
    /** 正在与网盘交互（进入应用自动同步、手动刷新都算）。 */
    val isSyncing: Boolean = false,
    /**
     * 最近一次同步的结论。成功也要说一句「取回 N 条」，
     * 否则用户点了刷新毫无反馈，只能猜。
     */
    val syncMessage: String? = null,
    /**
     * 未入组：不展示任何推文，只显示「先建群组 / 加入群组」的引导。
     *
     * 推文属于群组——没有群组就没有「我们的推文」可看。
     * 这是产品边界而不是技术限制：数据本来就在 `groups/<id>/` 之下，
     * 没有群组 id 就读不到任何人的记录。
     */
    val needsGroup: Boolean = false,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val mealRepository: MealRepository,
    private val commentRepository: CommentRepository,
    private val syncRepository: SyncRepository,
    private val avatarRepository: AvatarRepository,
    private val pairingRepository: PairingRepository,
    private val mediaCache: MediaCache,
    private val profileStore: LocalProfileStore,
    private val retentionTask: LocalRetentionTask,
    private val storageConfig: StorageConfigRepository,
    private val adminGate: AdminGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    /**
     * 本机身份 id。空表示还没读到（成员表未就绪），这段时间卡片一律按
     * 「别人的记录」处理，不要用占位值去比较。
     */
    private var authorId: String = ""
    private var myName: String = "我"

    /**
     * 最新一次收集到的群组状态，权限判断的唯一依据。
     *
     * 早先是拿 authorId 与 effectiveAdminId 自己比：名单还没到时两者都是空串，
     * "" == "" 于是「我是管理员」成立，非本人的云端记录会被误删。
     */
    private var latestGroup: GroupState? = null

    /** 成员表：id -> 成员，用于渲染每条记录的作者名与头像。 */
    private var members: Map<String, Member> = emptyMap()

    /** 头像缓存：authorId -> 本地文件。 */
    private var avatars: Map<String, File> = emptyMap()

    /**
     * 已退役的旧身份 id（绑定手机号后旧随机 id 作废）。
     *
     * 记录里的 created_by 是历史值，迁移后可能还留着旧 id；
     * 渲染时把它归并到当前身份，卡片上的名字与头像才会跟着 id 一起变，
     * 而不是显示成「成员」或者一张旧头像。
     */
    private var retiredIds: Set<String> = emptySet()

    /** 全部评论，按 mealId 分组后供卡片预览与评论面板使用。 */
    private var allComments: List<Comment> = emptyList()

    /** 最近一次落库的可见记录，供重新渲染时复用（渲染不再需要重新查库）。 */
    private var latestRecords: List<MealRecord> = emptyList()

    /** 正在从网盘取回的媒体，键为 `recordId/mediaId`。 */
    private val resolving = mutableSetOf<String>()

    /** 本次会话内已失败、等待用户手动重试的媒体，键同上。 */
    private val failed = mutableSetOf<String>()

    private var mediaJob: Job? = null

    private var scope: TimelineScope = TimelineScope.All

    /** 上次同步的时间戳，用于进入页面的 60 秒节流。 */
    private var lastSyncAt: Long = 0L

    /** 上次拉头像时的成员 id 列表，用于避免每次数据变化都重拉。 */
    private var syncedAvatarIds: List<String> = emptyList()

    /**
     * 本次运行是否已验过管理员口令。
     *
     * 只用来决定「别人的内容能不能点修改 / 删除」这一个按钮的可见性；
     * 真正的准入仍然在仓储层（[AdminGate.requireAdmin]）。
     */
    private var adminUnlocked: Boolean = false

    /** 收集本地记录流的任务。上游失败后要取消重起，所以留个句柄。 */
    private var recordsJob: kotlinx.coroutines.Job? = null

    init {
        observeRecords()
        observeGroupGate()
        // 每次进入应用顺手执行一次本地保留策略：超期的记录在确认云端有副本后清掉本机副本。
        // 失败（未授权 / 网络异常 / 频控）静默跳过——清理没做成不该打扰用户。
        viewModelScope.launch {
            runCatchingCancellable { retentionTask.run() }
                .onSuccess { if (it.removed > 0) render() }
        }
    }

    /**
     * 群组门禁：没建群组也没加入群组时，时间线不展示任何推文。
     *
     * 推文是群组的东西——数据本身就在 `groups/<id>/` 之下，没有群组 id
     * 就读不到任何人的记录。这里只是把这条边界如实反映到界面上，
     * 并给一个明确出口，而不是让用户对着空白页面猜。
     */
    private fun observeGroupGate() {
        viewModelScope.launch {
            storageConfig.observeGroupId().collect { groupId ->
                _uiState.update { it.copy(needsGroup = groupId.isNullOrBlank()) }
            }
        }
    }

    /**
     * 重新订阅本地记录流。
     *
     * 上游（Room / DataStore）抛异常时，`.catch` 会把这条流终结——它不会自己恢复。
     * 而界面上的「重试」过去只做一次网络同步，没重新订阅本地流，于是表现为
     * 「点了重试还是空白，非要杀掉应用重开」。这里把收集任务记下来，
     * 重试时先取消旧的再起一条，失败态才真的能自愈。
     */
    private fun restartRecordsObservation() {
        recordsJob?.cancel()
        observeRecords()
    }

    private fun observeRecords() {
        recordsJob = viewModelScope.launch {
            combine(
                mealRepository.observeAll(),
                commentRepository.observeAll(),
                pairingRepository.observe(),
                avatarRepository.observeAvatars(),
                // combine 的带类型重载最多 5 个参数，第 5 个位置把两个 profile 流并起来。
                combine(
                    profileStore.observeMyName(),
                    profileStore.observeRetiredIds(),
                ) { name, retired -> name to retired },
            ) { records, comments, group, avatarMap, profile ->
                Snapshot(
                    records = records,
                    comments = comments,
                    group = group,
                    members = group.members,
                    myId = group.myId,
                    avatars = avatarMap,
                    myName = profile.first,
                    retired = profile.second,
                )
            }
                // Room / DataStore 的流一旦抛异常，collect 之外没有兜底就会把整个
                // viewModelScope 连带崩掉。这里先落到 UI 的失败态，再由用户重试。
                .catch { error ->
                    Logger.w("读取本地记录失败：${error.message}")
                    onLoadFailed(error)
                }
                .collect { snapshot ->
                    latestGroup = snapshot.group
                    authorId = snapshot.myId
                    myName = snapshot.myName
                    members = snapshot.members.associateBy { it.id }
                    avatars = snapshot.avatars
                    retiredIds = snapshot.retired
                    allComments = snapshot.comments
                    latestRecords = snapshot.records.filterNot { it.isDeleted }
                    refreshAdminUnlock()
                    render()
                    syncAvatarsIfMembersChanged(snapshot.members)
                    ensureImages()
                    refreshCommentSheet()
                }
        }
    }

    /** 加载失败进 UI 状态：isLoading 必须一起落下，否则永远卡在转圈。 */
    private fun onLoadFailed(error: Throwable) {
        _uiState.update {
            it.copy(
                isLoading = false,
                loadFailed = true,
                errorMessage = error.toUserText(),
            )
        }
    }

    /**
     * 这条记录是不是「我写的」。
     *
     * createdBy 要先过 [resolveAuthorId] 归并退役 id，否则绑定手机号之前的
     * 旧记录会被判成别人的；两边只要有一个是空，一律按「不是我的」处理。
     */
    private fun isMine(createdBy: String): Boolean =
        authorId.isNotBlank() && resolveAuthorId(createdBy).let { it.isNotBlank() && it == authorId }

    /**
     * 这条记录能不能改 / 删（含云端）。
     *
     * 缓存的可能是 null（流还没到）：myId 空白时一律按无权处理，
     * 历史上的误删正是从这里漏出去的。
     *
     * 判「别人的内容」时必须叠加管理员解锁态——只判 `amIAdmin` 会让
     * 未验口令的管理员也能改别人的东西，管理员口令就白设了。
     * 这里只做一次本地纯判定，真正的准入在仓储层（[GroupRepository.canModifyContent]）。
     */
    private fun canModifyNow(createdBy: String): Boolean {
        val group = latestGroup?.takeIf { it.myId.isNotBlank() } ?: return false
        val author = resolveAuthorId(createdBy)
        if (author.isBlank()) return false
        if (author == group.myId) return true
        return group.amIAdmin && adminUnlocked
    }

    /**
     * 重新读一次管理员解锁态，并据此重绘（按钮可见性依赖它）。
     *
     * 每次名单 / 记录变化都重算，是因为「别人在另一台设备换了管理员口令」
     * 这件事本机没有任何推送可依赖；重算时会与云端比对，口令一换就自动锁上。
     */
    private suspend fun refreshAdminUnlock() {
        val unlocked = adminGate.isAdminUnlocked()
        if (unlocked != adminUnlocked) {
            adminUnlocked = unlocked
            render()
        }
    }

    /**
     * 成员名单第一次就绪（或有人加入、改名换了 id）时拉一次头像。
     *
     * 头像不会自己出现：`avatarRepository` 的缓存要靠 [syncAvatars] 填。
     * 之前只在 refresh() 里按 `members.keys` 拉，而那时成员还没收集到，
     * 传进去的是空列表，结果谁的都头像都不下载——卡片上只剩一个「成」字。
     */
    private fun syncAvatarsIfMembersChanged(members: List<Member>, force: Boolean = false) {
        val ids = members.map { it.id }.filter { it.isNotBlank() }.sorted()
        // 空名单直接返回：既不可能拉到头像，也不该把空列表记进「已拉过」的账，
        // 否则名单就绪后反而因为「跟上次一样」被跳过。
        if (ids.isEmpty()) return
        if (!force && ids == syncedAvatarIds) return
        syncedAvatarIds = ids
        viewModelScope.launch {
            runCatchingCancellable { avatarRepository.syncAvatars(ids) }
        }
    }

    private data class Snapshot(
        val records: List<MealRecord>,
        val comments: List<Comment>,
        val group: GroupState,
        val members: List<Member>,
        val myId: String,
        val avatars: Map<String, File>,
        val myName: String,
        val retired: Set<String>,
    )

    /** 用当前内存里的记录重建列表，避免为状态变化再查一次库。 */
    private fun render() {
        // 渲染读的是内存快照，理论上不该抛；但一旦抛了（日期解析、非法枚举等），
        // 调用点全是没有异常处理器的 viewModelScope，就地落到失败态而不是崩。
        runCatching { renderInternal() }
            .onFailure { error ->
                Logger.w("渲染时间线失败：${error.message}")
                onLoadFailed(error)
            }
    }

    private fun renderInternal() {
        val visible = latestRecords.filter { matches(it) }
        val groups = DayGroup.of(visible)
        val entries = buildEntries(groups, visible.size)
        _uiState.update {
            it.copy(
                isLoading = false,
                entries = entries,
                hasRecords = latestRecords.isNotEmpty(),
                totalCount = visible.size,
                totalDays = groups.size,
                scope = scope,
                scopeChips = buildChips(),
                months = monthIndexOf(entries),
                // 渲染成功说明数据是好的，把上一次的加载失败态收掉。
                loadFailed = false,
                errorMessage = null,
            )
        }
    }

    private fun matches(record: MealRecord): Boolean = when (val current = scope) {
        TimelineScope.All -> true
        TimelineScope.Marked -> record.marked
        is TimelineScope.Type -> record.mealType == current.type
    }

    /** 索引条：全部 / 星标 / 九种类型。类型多，交给横向滚动。 */
    private fun buildChips(): List<ScopeChip> {
        val items = buildList {
            add(TimelineScope.All)
            add(TimelineScope.Marked)
            MealType.entries.forEach { add(TimelineScope.Type(it)) }
        }
        return items.map { ScopeChip(label = it.label, scope = it, selected = it == scope) }
    }

    private fun buildEntries(groups: List<DayGroup>, totalCount: Int): List<TimelineEntry> {
        val result = mutableListOf<TimelineEntry>()
        // 只有「全部」才放摘要卡：
        // 1) 没有记录时不放，让空状态成为唯一焦点（否则「0 条记录」会先晃一眼）；
        // 2) 筛选之后不放，用户此时要的是筛选结果，一张统计大卡只会把列表挤下去。
        if (totalCount > 0 && scope == TimelineScope.All) {
            result.add(
                TimelineEntry.Hero(
                    count = totalCount,
                    days = groups.size,
                    subtitle = heroSubtitle(),
                )
            )
        }
        for (group in groups) {
            result.add(
                TimelineEntry.Header(
                    title = DateText.dayTitle(group.dayStartMillis),
                    subtitle = "${group.records.size} 条",
                    dayKey = group.dayKey,
                )
            )
            group.records.forEach { record ->
                result.add(TimelineEntry.Card(toCardUi(record)))
            }
        }
        return result
    }

    /**
     * 从条目里抽出月份锚点：每个月第一次出现的位置。
     *
     * 直接复用已渲染的列表，不再单独算一遍分组，避免两份数据走偏。
     */
    private fun monthIndexOf(entries: List<TimelineEntry>): List<MonthIndex> {
        val months = mutableListOf<MonthIndex>()
        var lastKey = ""
        entries.forEachIndexed { index, entry ->
            if (entry !is TimelineEntry.Header) return@forEachIndexed
            val key = entry.dayKey.take(7)
            if (key == lastKey) return@forEachIndexed
            lastKey = key
            val label = runCatching {
                val year = key.substring(0, 4).toInt()
                val month = key.substring(5, 7).toInt()
                "${year}年${month}月"
            }.getOrDefault(key)
            months.add(MonthIndex(label = label, itemIndex = index))
        }
        return months
    }

    /** 摘要卡的问候语：按时段变化，比固定文案更有「今天也在一起」的感觉。 */
    private fun heroSubtitle(): String {
        val greeting = when (java.time.LocalTime.now().hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "夜深了"
        }
        return "$greeting · ${DateText.dateLabel(System.currentTimeMillis())}"
    }

    /** 旧 id 归并：已退役的 id 一律按当前身份显示。 */
    private fun resolveAuthorId(createdBy: String): String =
        if (createdBy in retiredIds) authorId else createdBy

    private fun toCardUi(record: MealRecord): MealCardUi {
        val authorKey = resolveAuthorId(record.createdBy)
        val mine = isMine(record.createdBy)
        // 作者不一定是我或「那个人」：成员表里查不到时才回退为通用称呼。
        val author = members[authorKey] ?: members[record.createdBy]
        val authorName = when {
            mine -> myName
            author != null -> author.name.ifBlank { "成员" }
            else -> "成员"
        }
        val mealComments = allComments.filter { it.mealId == record.id && !it.isDeleted }

        return MealCardUi(
            id = record.id,
            timestamp = record.timestamp,
            dayTitle = DateText.dayTitle(record.timestamp),
            timeText = DateText.timeText(record.timestamp),
            mealType = record.mealType,
            mealLabel = mealLabelOf(record.mealType),
            authorName = authorName,
            isMine = mine,
            avatarImage = avatars[authorKey] ?: avatars[record.createdBy],
            media = record.media.map { item -> toMediaUi(record, item) },
            note = record.note,
            syncState = record.syncState,
            rating = record.rating,
            marked = record.marked,
            locationText = record.location?.displayText,
            tags = record.tags,
            commentCount = mealComments.size,
            commentPreview = mealComments
                .takeLast(DinnerDimens.commentPreviewCount)
                .map { toCommentUi(it) },
        )
    }

    private fun toMediaUi(record: MealRecord, item: MediaItem): MediaCardUi = MediaCardUi(
        id = item.id,
        localPath = item.localPath,
        thumbPath = item.thumbPath,
        mediaType = item.mediaType,
        hint = mediaHintOf(record, item),
        durationText = if (item.mediaType == MediaType.VIDEO) durationTextOf(item.durationMs) else null,
    )

    private fun toCommentUi(comment: Comment): CommentUi {
        val authorKey = resolveAuthorId(comment.authorId)
        val mine = isMine(comment.authorId)
        val author = members[authorKey] ?: members[comment.authorId]
        return CommentUi(
            id = comment.id,
            authorName = when {
                mine -> myName
                author != null -> author.name.ifBlank { "成员" }
                else -> "成员"
            },
            text = comment.text,
            timeText = DateText.timeText(comment.createdAt),
            isMine = mine,
            avatar = avatars[authorKey] ?: avatars[comment.authorId],
            // 作者本人或管理员可删，删除会同步给所有人；判断与记录删除同一口径。
            canDelete = canModifyNow(comment.authorId),
        )
    }

    /**
     * 卡片上的取回提示。
     *
     * 远端有文件但本地没有，说明这是别人上传的（或本机重装过、或本地已按保留策略清理过），
     * 需要从网盘拉一份回来才能显示；这里只给文案，不做任何 IO。
     */
    private fun mediaHintOf(record: MealRecord, item: MediaItem): String? {
        if (item.mediaType != MediaType.IMAGE) return null
        if (item.hasLocalFile) return null
        val key = mediaKey(record.id, item.id)
        return when {
            key in resolving -> "正在取回…"
            key in failed -> "取回失败，点一下重试"
            !item.remotePath.isNullOrBlank() -> "准备取回…"
            else -> null
        }
    }

    /**
     * 把远端照片补下载到本地。
     *
     * 串行执行：开发期接口频率只有约 100 次/小时，一屏十几张照片并发会直接触发频控。
     * 已缓存命中时 MediaCache 不会发请求，所以重复进入页面几乎零成本。
     */
    /**
     * 把还没有本机副本的图取回来。
     *
     * 只对**最近几条**记录取，而且每轮有张数上限：
     * 从云端恢复一次历史可能有上千张图，如果见一张取一张，
     * 开发期 100 次/小时的接口配额一次就被打空（每张图 = filemetas + download）。
     * 更早的照片不在这里预取——点进详情页会自动取那一张，滚动时也不会卡。
     */
    private fun ensureImages() {
        if (mediaJob?.isActive == true) return
        val pending = latestRecords
            .take(VISIBLE_RECORD_BUDGET)
            .flatMap { record ->
                record.media
                    .filter { item ->
                        item.mediaType == MediaType.IMAGE &&
                            !item.remotePath.isNullOrBlank() &&
                            !item.hasLocalFile &&
                            mediaKey(record.id, item.id) !in failed
                    }
                    .map { item -> record to item }
            }
            .take(IMAGE_FETCH_BUDGET)
        if (pending.isEmpty()) return

        mediaJob = viewModelScope.launch {
            for ((record, item) in pending) {
                val remote = item.remotePath ?: continue
                val key = mediaKey(record.id, item.id)
                resolving += key
                render()
                mediaCache.resolve(remote)
                    .onSuccess { file -> bindLocalMedia(record.id, item.id, file.absolutePath) }
                    .onFailure { failed += key }
                resolving -= key
                render()
            }
        }
    }

    /** 只回填本地路径，不改同步状态：缓存是设备私有数据。 */
    private suspend fun bindLocalMedia(recordId: String, mediaId: String, localPath: String) {
        val current = runCatchingCancellable { mealRepository.getById(recordId) }.getOrNull() ?: return
        runCatchingCancellable {
            mealRepository.updateMedia(
                recordId,
                current.media.map { if (it.id == mediaId) it.copy(localPath = localPath) else it },
            )
        }
    }

    /** 用户点了加载失败的图片：清掉失败标记重新取。 */
    fun retryMedia(recordId: String) {
        failed.removeAll { it.startsWith("$recordId/") }
        render()
        ensureImages()
    }

    // ---------- 索引 ----------

    /** 切换筛选范围（全部 / 星标 / 某个类型）。 */
    fun setScope(next: TimelineScope) {
        if (scope == next) return
        scope = next
        render()
        ensureImages()
    }

    /** 星标：只影响本机索引，不触发同步。 */
    fun toggleMark(id: String) {
        val record = latestRecords.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatchingCancellable { mealRepository.setMarked(id, !record.marked) }
        }
    }

    // ---------- 评论 ----------

    fun openComments(id: String) {
        val record = latestRecords.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                commentSheet = CommentSheetState(
                    mealId = id,
                    title = "${mealLabelOf(record.mealType)} · ${DateText.timeText(record.timestamp)}",
                )
            )
        }
        refreshCommentSheet()
    }

    fun closeComments() {
        _uiState.update { it.copy(commentSheet = null) }
    }

    fun setCommentInput(text: String) {
        _uiState.update { state ->
            val sheet = state.commentSheet ?: return@update state
            state.copy(commentSheet = sheet.copy(input = text.take(MAX_COMMENT_LENGTH), errorMessage = null))
        }
    }

    fun sendComment() {
        val sheet = _uiState.value.commentSheet ?: return
        val text = sheet.input.trim()
        if (text.isEmpty() || sheet.isSending) return

        _uiState.update { it.copy(commentSheet = sheet.copy(isSending = true, errorMessage = null)) }
        viewModelScope.launch {
            runCatchingCancellable { commentRepository.add(sheet.mealId, authorId, text) }
                .onSuccess {
                    _uiState.update { state ->
                        val current = state.commentSheet ?: return@update state
                        state.copy(commentSheet = current.copy(input = "", isSending = false))
                    }
                    // 评论随快照发布，这里立即推一次；失败不回滚，下次同步会补上。
                    runCatchingCancellable { syncRepository.syncNow(auto = true) }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        val current = state.commentSheet ?: return@update state
                        state.copy(
                            commentSheet = current.copy(
                                isSending = false,
                                errorMessage = error.toUserText(),
                            )
                        )
                    }
                }
        }
    }

    /** 请求删除一条评论：先弹确认，确认后才真删。 */
    fun requestDeleteComment(comment: CommentUi) {
        _uiState.update { it.copy(pendingCommentDelete = comment) }
    }

    fun cancelDeleteComment() {
        _uiState.update { it.copy(pendingCommentDelete = null) }
    }

    fun confirmDeleteComment() {
        val target = _uiState.value.pendingCommentDelete ?: return
        _uiState.update { it.copy(pendingCommentDelete = null) }
        viewModelScope.launch {
            runCatchingCancellable { commentRepository.softDelete(target.id, System.currentTimeMillis()) }
            // 评论随快照发布：删完立刻推一次，否则别人那边还看得见。
            runCatchingCancellable { syncRepository.syncNow(auto = true) }
        }
    }

    /** 把最新评论同步进打开中的面板。 */
    private fun refreshCommentSheet() {
        val sheet = _uiState.value.commentSheet ?: return
        val comments = allComments
            .filter { it.mealId == sheet.mealId && !it.isDeleted }
            .map { toCommentUi(it) }
        _uiState.update { state ->
            val current = state.commentSheet ?: return@update state
            state.copy(commentSheet = current.copy(comments = comments))
        }
    }

    // ---------- 删除 ----------

    fun requestDelete(id: String) {
        val record = latestRecords.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                deleteTarget = DeleteTarget(
                    id = id,
                    isMine = isMine(record.createdBy),
                    // myId 空白时按无权处理，避免名单未就绪时把别人的云端记录判成可删；
                    // 别人的内容还要求管理员已验过口令。
                    canDeleteCloud = canModifyNow(record.createdBy),
                )
            )
        }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    /** 只删本地：本机隐藏，云端与他人不受影响。 */
    fun deleteLocalOnly() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        viewModelScope.launch {
            runCatchingCancellable { mealRepository.localRemove(target.id, System.currentTimeMillis()) }
        }
    }

    /**
     * 只删云端：通知所有人删除并删掉网盘原文件，本地行保留但不再展示。
     *
     * 删之前先站着看一眼云端：既没同步（syncState 不是 SYNCED）、
     * 又有媒体没推上去，这时候「删云端」其实删不到任何东西，
     * 而现状不可确定时不得动手（红线：先拿到数据 -> 再解析 -> 再上传）。
     */
    fun deleteCloudOnly() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null, syncMessage = null) }
        if (!target.canDeleteCloud) {
            _uiState.update { it.copy(syncMessage = "这条记录只有本人或管理员能从云端删除") }
            return
        }
        val record = latestRecords.firstOrNull { it.id == target.id }
        if (record == null) {
            _uiState.update { it.copy(syncMessage = "记录已不存在，未改动云端") }
            return
        }
        if (record.syncState != SyncState.SYNCED || !record.isFullyUploaded) {
            _uiState.update {
                it.copy(syncMessage = "云端还没有这条记录的完整副本，已中止。先同步一次再删")
            }
            return
        }
        viewModelScope.launch {
            runCatchingCancellable { mealRepository.softDelete(target.id, System.currentTimeMillis()) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(syncMessage = "云端删除未完成：${error.toUserText()}")
                    }
                    return@launch
                }
            // 结局必须如实展示：丢掉 outcome 等于「点了没反应」。
            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }.getOrNull()
            _uiState.update {
                it.copy(
                    syncMessage = if (outcome?.isSuccess == true) {
                        "已从云端删除，本机这条也收起来了"
                    } else {
                        "云端删除未完成：${outcome?.message ?: "同步失败，请稍后重试"}"
                    },
                )
            }
        }
    }

    /**
     * 本地和云端都删：软删除进入上传队列，**确认云端已经同步成功**后才抹掉本地行与评论。
     *
     * 早先是无条件抹掉本地行，理由是「云端失败时记录仍可通过同步找回」。
     * 但那句话的前提是「云端确实删掉了」：如果这次同步根本没成功（断网 / 频控 / 授权过期），
     * 云端那条记录仍然存在，下一轮同步又会把它拉回来——
     * 用户看到的是「删了又自己冒出来」，而本地那份已经没了，连重试都没地方点。
     *
     * 现在的顺序：软删除 -> 同步 -> 只有在同步成功时才清本地。
     * 同步失败就保留本地行（保持 FAILED / PENDING 状态），用户可以在时间线上看到并重试。
     */
    fun deleteBoth() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        if (!target.canDeleteCloud) return
        viewModelScope.launch {
            // 第一步：软删除。这一条写库失败就不能往下走——否则后面会对着一条
            // 根本没被标删除的记录做同步，然后误以为云端已经删了。
            val softDeleted = runCatchingCancellable {
                mealRepository.softDelete(target.id, System.currentTimeMillis())
            }
            if (softDeleted.isFailure) {
                _uiState.update {
                    it.copy(
                        syncMessage = "标记删除失败，记录仍保留在本机：${softDeleted.exceptionOrNull()?.toUserText()}"
                    )
                }
                return@launch
            }
            // 第二步：同步。只有云端确认删成功才允许抹本地。
            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = false) }.getOrNull()
            val synced = outcome?.isSuccess == true
            if (!synced) {
                // 云端没删成：本地保留，标 FAILED 让用户能看见并重试。
                _uiState.update {
                    it.copy(
                        syncMessage = outcome?.message?.let { msg -> "云端删除未完成，记录仍保留在本机：$msg" }
                            ?: "云端删除未完成，记录仍保留在本机",
                    )
                }
                return@launch
            }
            // 第三步：抹本地。两步分别判定，任何一步失败都必须如实展示——
            // 吞掉结果会出现「界面说删干净了，本地行其实还在」。
            val commentsCleared = runCatchingCancellable { commentRepository.deleteForMeal(target.id) }
            val rowCleared = runCatchingCancellable { mealRepository.hardDelete(target.id) }
            val failures = listOfNotNull(
                commentsCleared.exceptionOrNull()?.let { "评论没清掉：${it.toUserText()}" },
                rowCleared.exceptionOrNull()?.let { "本机记录没清掉：${it.toUserText()}" },
            )
            if (failures.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        syncMessage = "云端已删除，但本机没清干净（${failures.joinToString("；")}），可再点一次删除",
                    )
                }
            }
        }
    }

    // ---------- 媒体播放 ----------

    /** 点视频：本地有文件直接播，没有则先下载再播。 */
    fun playVideo(recordId: String, mediaId: String) {
        val record = latestRecords.firstOrNull { it.id == recordId } ?: return
        val item = record.media.firstOrNull { it.id == mediaId } ?: return
        if (_uiState.value.player.isLoading || _uiState.value.player.path != null) return

        MealRecord.existingFile(item.localPath)?.let { local ->
            _uiState.update { it.copy(player = VideoPlayback(path = local.absolutePath)) }
            return
        }

        val remote = item.remotePath
        if (remote.isNullOrBlank()) {
            _uiState.update {
                it.copy(player = VideoPlayback(errorMessage = "这个视频不在这台设备上，也找不到网盘副本"))
            }
            return
        }

        _uiState.update { it.copy(player = VideoPlayback(isLoading = true)) }
        viewModelScope.launch {
            mediaCache.resolve(remote)
                .onSuccess { file ->
                    bindLocalMedia(recordId, item.id, file.absolutePath)
                    _uiState.update { it.copy(player = VideoPlayback(path = file.absolutePath)) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(player = VideoPlayback(errorMessage = error.toUserText()))
                    }
                }
        }
    }

    fun dismissPlayer() {
        _uiState.update { it.copy(player = VideoPlayback()) }
    }

    /**
     * 进入应用与手动刷新共用。
     *
     * **先拉后推**：对端新记的那几顿要先下来，本机待上传的再推上去。
     * 早先这里只调 `syncNow`（纯上传），既不读远端也不刷新成员与头像，
     * 于是「刷新」在用户眼里等于什么也没发生——对方记的东西永远看不到。
     *
     * @param force 手动点同步 / 错误重试用 true；进入页面用默认值，走 60 秒节流。
     *   切换底部导航会重建一次 composition，不节流的话来回点几次就把
     *   开发期 100 次/小时的配额消耗在重复请求上。
     */
    fun refresh(force: Boolean = false) {
        failed.clear()
        // 本地流挂掉过：先重新订阅再谈同步，否则这轮拉回的数据没人渲染，
        // 用户会看到「点了重试还是空白」。
        if (_uiState.value.loadFailed) {
            _uiState.update { it.copy(loadFailed = false, errorMessage = null, isLoading = true) }
            restartRecordsObservation()
        }
        if (_uiState.value.isSyncing) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < AUTO_SYNC_INTERVAL_MS) {
            ensureImages()
            return
        }
        // 一开始就记时间：并发的两次进入不该各发一轮请求。
        lastSyncAt = now
        _uiState.update { it.copy(isSyncing = true, syncMessage = null) }

        viewModelScope.launch {
            val pulled: Int
            val pullError: String?
            // 近 30 天默认全量预取：记录与图片拉回本地，视频只拉封面。
            // 全部下载的语义见 SyncRepository.prefetchRecent。
            //
            // prefetchRecent 返回 Result<PrefetchOutcome>：必须摊平，
            // 三层 Result 会让 onSuccess 恒为真，「拉失败」被当成「拉到 0 条」。
            val prefetch = runCatchingCancellable {
                syncRepository.prefetchRecent().getOrThrow()
            }
            pulled = prefetch.getOrNull()?.records ?: 0
            pullError = prefetch.exceptionOrNull()?.toUserText()

            val outcome = runCatchingCancellable { syncRepository.syncNow(auto = true) }
                .getOrNull()

            _uiState.update {
                it.copy(
                    isSyncing = false,
                    syncMessage = syncMessageOf(pulled, pullError, outcome),
                )
            }
        }
        // 头像跟着成员名单走 force 拉一次：名单可能还没收集到，空列表一律不发请求。
        syncAvatarsIfMembersChanged(members.values.toList(), force = true)
        ensureImages()
    }

    /**
     * 把「拉」与「推」两段结果合成一句人话。
     *
     * 未授权时 syncNow 会 skipped：这时不弹任何同步提示，
     * 由「我的」页的授权入口引导，避免每次进应用都糊一条红字。
     */
    private fun syncMessageOf(
        pulled: Int,
        pullError: String?,
        outcome: com.guolongli.dinner.domain.repository.SyncOutcome?,
    ): String? {
        if (outcome == null || outcome.skipped) return null
        outcome.message?.let { return it }
        return when {
            pulled > 0 && outcome.uploaded > 0 -> "已取回 $pulled 条，上传 ${outcome.uploaded} 条"
            pulled > 0 -> "已从云端取回 $pulled 条记录"
            outcome.uploaded > 0 -> "已上传 ${outcome.uploaded} 条记录"
            pullError != null -> "取回失败：$pullError"
            else -> null
        }
    }

    private fun mediaKey(recordId: String, mediaId: String): String = "$recordId/$mediaId"

    private companion object {
        const val MAX_COMMENT_LENGTH = 300

        /**
         * 自动预取图片的范围：最近 6 条记录（约一屏），每轮最多 12 张。
         *
         * 上限的存在意义是保护接口配额：每张图要 filemetas + download 两次请求，
         * 开发期只有 100 次/小时。更早的照片交给详情页按需取。
         */
        const val VISIBLE_RECORD_BUDGET = 6
        const val IMAGE_FETCH_BUDGET = 12

        /** 进入页面的自动同步节流窗口：一分钟内重复进入不再打接口。 */
        const val AUTO_SYNC_INTERVAL_MS = 60_000L
    }
}
