package com.guolongli.dinner.data

import androidx.room.withTransaction
import com.guolongli.dinner.data.MemberRecord
import com.guolongli.dinner.data.local.CommentEntity
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.local.MediaEntity
import com.guolongli.dinner.data.local.MediaKind
import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.local.OutboxEntity
import com.guolongli.dinner.data.local.RecordEntity
import com.guolongli.dinner.data.local.RecordWithChildren
import com.guolongli.dinner.data.local.SyncState
import com.guolongli.dinner.data.media.MediaCache
import com.guolongli.dinner.data.sync.SyncEngine
import com.guolongli.dinner.data.sync.SyncReport
import com.guolongli.dinner.ui.model.CommentUiModel
import com.guolongli.dinner.ui.model.MediaUiModel
import com.guolongli.dinner.ui.model.MemberUiModel
import com.guolongli.dinner.ui.model.RecordType
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.StatScope
import com.guolongli.dinner.ui.model.StatUiModel
import com.guolongli.dinner.ui.theme.AvatarBlue
import com.guolongli.dinner.ui.theme.AvatarGreen
import com.guolongli.dinner.ui.theme.AvatarOrange
import com.guolongli.dinner.ui.theme.TagGreenFg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 新增记录时要落地的媒体。
 * localPath 可以为空——示例数据、或者别人同步下来还没下载的照片，都只有云端路径。
 */
data class MediaInput(
    val localPath: String? = null,
    val kind: MediaKind = MediaKind.PHOTO,
    val durationMs: Long? = null,
)

/**
 * 界面唯一的数据入口。
 *
 * 写入路径固定为「先写本地库 → 再进发件箱」，所以断网也能记；
 * 上传由 SyncEngine 负责，界面只读 Flow，不关心数据是从本地来的还是从网盘拉回来的。
 */
class RecordRepository(
    private val db: DinnerDatabase,
    private val engine: SyncEngine,
    private val user: () -> LocalUser,
    /** 作者 → 头像本地文件；没有就返回 null，界面回落成首字头像 */
    private val avatarPathOf: (String) -> String? = { null },
    private val mediaCache: MediaCache? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * 写完一条就回调一次（事务提交之后）。
     * 用来催自动同步——界面不用关心同步什么时候发生，改完就能关掉应用。
     */
    private val onWrite: (recordId: String) -> Unit = {},
    /** 能否改 / 删这条记录（自己的，或已解锁管理员）。权限判定在 AdminSession */
    private val canManage: (authorId: String) -> Boolean = { true },
    /** 能否动云端那份文件：普通成员只能删自己上传的 */
    private val canTouchCloud: (authorId: String) -> Boolean = { true },
    /** 直接删网盘上的一个文件；由 Graph 接到 NetdiskRepository */
    private val deleteRemote: suspend (remotePath: String) -> Unit = {},
    /** 判断某个成员 id 是不是管理员，用于成员列表的角色标签 */
    private val isAdminMember: (memberId: String) -> Boolean = { false },
    /**
     * 身份版本号：昵称 / 头像变一次 +1（见 ProfileStore.identityTick）。
     * 只改昵称不动数据库，记录 Flow 不会重发；combine 它，改完立刻全量重映射，
     * 时间线、成员列表、统计里的名字与头像当场就是最新的。
     */
    private val identityChanges: Flow<Long> = MutableStateFlow(0L),
    /**
     * 已入群的成员名单（来自 `config/members.json`）。
     *
     * 不能只从记录反推：一个人入群之后可能几天没记东西，
     * 「只看记录里出现过谁」会让他从名单里消失，管理员也就无法把管理权转给他。
     */
    private val knownMembers: () -> List<MemberRecord> = { emptyList() },
) {

    private val records = db.recordDao()
    private val outbox = db.outboxDao()

    // ------------------------------------------------------------------ 读

    fun timeline(): Flow<List<RecordUiModel>> =
        combine(records.observeTimeline(), identityChanges) { rows, _ -> rows }
            .map { rows -> rows.map { it.toUiModel() } }

    /** 页头那句「一共 N 条 · 跨越 M 天」 */
    fun summary(): Flow<String> = records.observeTimeline().map { rows ->
        val days = rows.map { dayOf(it.record.createdAt) }.distinct().size
        "一共 ${rows.size} 条 · 跨越 $days 天"
    }

    /**
     * 「我的」顶部三个可点数字。
     *
     * 三个数从同一份时间线快照里数出来，不再各查各的 COUNT：
     * 三条独立查询在写入间隙会数出互相矛盾的组合（总记录 2、我记的 3 这种），
     * 而且「我记的」用的作者 id 在绑定手机号后会变，绑完那一刻旧查询还挂着老 id。
     * 同一份快照 + 当场取的 id，三个数天然自洽（我记的 ≤ 总记录、已同步 ≤ 总记录）。
     */
    fun stats(): Flow<List<StatUiModel>> =
        combine(records.observeTimeline(), identityChanges) { rows, _ -> rows }
            .map { rows ->
                val me = user().id
                val total = rows.size
                val mine = rows.count { it.record.authorId == me }
                val synced = rows.count { it.record.syncState == SyncState.SYNCED.name }
                listOf(
                    StatUiModel(total, "总记录", scope = StatScope.ALL),
                    StatUiModel(mine, "我记的", scope = StatScope.MINE),
                    StatUiModel(synced, "已同步", TagGreenFg, StatScope.SYNCED),
                )
            }

    fun pendingCount(): Flow<Int> = records.observeOutboxCount()

    /**
     * 统计数字点进去之后要看的那批记录。
     * 过滤放在这里而不是页面里，是因为「已同步」要看 RecordEntity.syncState，
     * 这个细节不该漏到 UI 层。
     */
    fun records(scope: StatScope): Flow<List<RecordUiModel>> =
        combine(records.observeTimeline(), identityChanges) { rows, _ -> rows }
            .map { rows ->
                val me = user().id
                rows.filter { row ->
                    when (scope) {
                        StatScope.ALL -> true
                        StatScope.MINE -> row.record.authorId == me
                        StatScope.SYNCED -> row.record.syncState == SyncState.SYNCED.name
                    }
                }.map { it.toUiModel() }
            }

    /**
     * 群组成员：不另建账号体系，直接看「记录里出现过谁」。
     * 我是永远在列表里的第一位——哪怕一条都没记过。
     */
    fun members(): Flow<List<MemberUiModel>> =
        combine(records.observeTimeline(), identityChanges) { rows, _ -> rows }
            .map { rows ->
        val me = user()
        val joined = knownMembers()

        // 名单里有、记录里没出现过的成员也要显示（刚入群还没记东西）
        val seen = LinkedHashMap<String, MemberUiModel>()
        joined.forEach { member ->
            seen[member.id] = MemberUiModel(
                id = member.id,
                name = member.name.ifBlank { "成员" },
                initial = member.name.trim().take(1).ifBlank { "?" },
                color = avatarColorFor(member.id),
                isAdmin = isAdminMember(member.id),
                joinedAt = if (member.joinedAt > 0) "加入 " + dayLabel(member.joinedAt) else "已入群",
                avatarPath = avatarPathOf(member.id),
                isMe = member.id == me.id,
            )
        }

        // 记录里出现过、名单里还没有的（别人还没同步名单过来时兜底）
        rows.map { it.record }
            .filter { it.authorId != me.id && !seen.containsKey(it.authorId) }
            .distinctBy { it.authorId }
            .sortedBy { it.createdAt }
            .forEach { record ->
                val fallbackName = latestNameOf(record.authorId, record.authorName)
                seen[record.authorId] = MemberUiModel(
                    id = record.authorId,
                    name = fallbackName.ifBlank { "成员" },
                    initial = fallbackName.trim().take(1).ifBlank { "?" },
                    color = avatarColorFor(record.authorId),
                    isAdmin = isAdminMember(record.authorId),
                    joinedAt = "首次记录 " + dayLabel(record.createdAt),
                    avatarPath = avatarPathOf(record.authorId),
                )
            }

        val others = seen.values.filterNot { it.id == me.id }.sortedBy { it.joinedAt }
        listOf(
            MemberUiModel(
                id = me.id,
                name = me.name,
                initial = me.initial,
                color = AvatarBlue,
                isAdmin = isAdminMember(me.id),
                joinedAt = "本机",
                avatarPath = me.avatarPath ?: avatarPathOf(me.id),
                isMe = true,
            ),
        ) + others
    }

    // ------------------------------------------------------------------ 写

    /**
     * 记一条。先落本地再进发件箱——顺序不能反：
     * 反了的话上传失败就等于什么都没记下来。
     */
    suspend fun addRecord(
        type: RecordType,
        note: String,
        rating: Int,
        media: List<MediaInput> = emptyList(),
        starred: Boolean = false,
        createdAt: Long = now(),
        /** 位置：可选，null 表示这条不记位置 */
        latitude: Double? = null,
        longitude: Double? = null,
        place: String? = null,
    ): String {
        val id = db.withTransaction {
            val me = user()
            val id = UUID.randomUUID().toString()
            val moment = now()
            records.upsertRecord(
                RecordEntity(
                    id = id,
                    authorId = me.id,
                    authorName = me.name,
                    type = type.name,
                    note = note.trim(),
                    rating = rating.coerceIn(0, 5),
                    starred = starred,
                    createdAt = createdAt,
                    updatedAt = moment,
                    deleted = false,
                    latitude = latitude,
                    longitude = longitude,
                    place = place,
                    syncState = SyncState.PENDING.name,
                ),
            )
            if (media.isNotEmpty()) {
                records.upsertMedia(
                    media.take(MAX_MEDIA).mapIndexed { index, item ->
                        MediaEntity(
                            recordId = id,
                            position = index,
                            kind = item.kind.name,
                            localPath = item.localPath,
                            durationMs = item.durationMs,
                        )
                    },
                )
            }
            enqueue(id, OpKind.UPSERT_RECORD, moment)
            id
        }
        onWrite(id)
        return id
    }

    suspend fun addComment(recordId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val added = db.withTransaction {
            val me = user()
            val parent = records.record(recordId) ?: return@withTransaction false
            val moment = now()
            records.upsertComments(
                listOf(
                    CommentEntity(
                        id = UUID.randomUUID().toString(),
                        recordId = recordId,
                        authorId = me.id,
                        authorName = me.name,
                        text = body,
                        createdAt = moment,
                        pendingSync = true,
                    ),
                ),
            )
            // 评论跟着记录一起传：把父记录标成待同步并重新入队
            records.upsertRecord(parent.copy(updatedAt = moment, syncState = SyncState.PENDING.name))
            enqueue(recordId, OpKind.UPSERT_RECORD, moment)
            true
        }
        if (added) onWrite(recordId)
    }

    /**
     * 删除一条记录，三档可选（README 的「只删本地 / 只删云端 / 都删」）。
     *
     * 三档的区别必须落到存储上，否则就是糊弄：
     * - [DeleteScope.LOCAL]：本机留墓碑但**不进发件箱**。云端不知道，别人照常看得到；
     *   又因为本地 updatedAt 更新，下一轮拉取时不会被云端的旧版本「复活」。
     * - [DeleteScope.CLOUD]：删掉网盘上的 JSON 与附件，再在本机抹掉（同样是墓碑不进发件箱）。
     *   文件都没了，别人下一次同步自然也就看不到。
     * - [DeleteScope.BOTH]：墓碑 + 进发件箱，让所有人跟着删。
     *
     * 权限：改 / 删别人的内容要先解锁管理员（[canManage]）；动云端文件还要看
     * [canTouchCloud]——普通成员只能删自己上传的。
     */
    suspend fun deleteRecord(recordId: String, scope: DeleteScope = DeleteScope.BOTH): DeleteOutcome {
        val existing = records.record(recordId) ?: return DeleteOutcome.NOT_FOUND
        if (!canManage(existing.authorId)) return DeleteOutcome.NOT_ALLOWED
        if (scope != DeleteScope.LOCAL && !canTouchCloud(existing.authorId)) {
            return DeleteOutcome.CLOUD_NOT_ALLOWED
        }

        if (scope != DeleteScope.LOCAL) {
            // 先删附件再删文档：反过来的话，删到一半失败会留下一堆孤儿文件，
            // 而文档已经没了，整理任务也就找不到它们了。
            records.mediaOf(recordId).forEach { media ->
                media.remotePath?.let { path ->
                    runCatching { deleteRemote(path) }
                }
            }
            existing.remotePath?.let { path ->
                runCatching { deleteRemote(path) }
            }
        }
        // 本机文件也一起收掉：留着只会在私有目录里慢慢攒，而且记录都删了，
        // 这些文件再也没有入口能访问到
        runCatching { dropLocalMedia(recordId) }

        db.withTransaction {
            records.upsertRecord(
                existing.copy(
                    deleted = true,
                    updatedAt = now(),
                    // 只删本地时标成「已同步」：它不该再被推上去
                    syncState = if (scope == DeleteScope.BOTH) {
                        SyncState.PENDING.name
                    } else {
                        SyncState.SYNCED.name
                    },
                ),
            )
            if (scope == DeleteScope.BOTH) {
                enqueue(recordId, OpKind.DELETE_RECORD, now())
            } else {
                // 同一目标上如果还压着一条待办，说明之前改过没传出去；
                // 现在用户选择只在本机删掉，那条待办就成了幽灵，清掉。
                outbox.deleteForTarget(recordId)
            }
        }
        if (scope == DeleteScope.BOTH) onWrite(recordId)
        return DeleteOutcome.DONE
    }

    /** 清掉本机已经下载的媒体文件（删记录 / 只删本地时顺手做） */
    private suspend fun dropLocalMedia(recordId: String) {
        records.mediaOf(recordId).forEach { media ->
            mediaCache?.delete(media.localPath)
            records.setMediaLocalPath(recordId, media.position, null)
        }
    }

    /** 打分：点第几颗星就是几分，点已经点亮的那颗则清零 */
    /**
     * 删一条评论。
     *
     * 和删记录一样留墓碑（`deleted = true`）：直接删行的话，别的设备拉下来
     * 只会发现「少了一条」，永远不知道它被删过，于是各自留着一份已经删掉的评论。
     * 父记录的 updatedAt 一起更新，这条改动才会被推上去。
     */
    suspend fun deleteComment(recordId: String, commentId: String): Boolean {
        val changed = db.withTransaction {
            val parent = records.record(recordId) ?: return@withTransaction false
            val comment = records.commentsOf(recordId).firstOrNull { it.id == commentId }
                ?: return@withTransaction false
            val moment = now()
            records.upsertComments(
                listOf(comment.copy(deleted = true, pendingSync = true)),
            )
            records.upsertRecord(
                parent.copy(updatedAt = moment, syncState = SyncState.PENDING.name),
            )
            enqueue(recordId, OpKind.UPSERT_RECORD, moment)
            true
        }
        if (changed) onWrite(recordId)
        return changed
    }

    suspend fun setRating(recordId: String, stars: Int) = mutate(recordId) { it.copy(rating = stars.coerceIn(0, 5)) }

    /** 星标：时间线的「星标」筛选靠它 */
    suspend fun toggleStar(recordId: String) = mutate(recordId) { it.copy(starred = !it.starred) }

    /** 改类型：长按标签时用得上，逻辑与其它编辑一致 */
    suspend fun setType(recordId: String, type: RecordType) = mutate(recordId) { it.copy(type = type.name) }

    /** 所有「改一条已有记录」的操作都走这里，保证 updatedAt / 发件箱 / 回调三件事不漏 */
    private suspend fun mutate(recordId: String, change: (RecordEntity) -> RecordEntity) {
        val changed = db.withTransaction {
            val existing = records.record(recordId) ?: return@withTransaction false
            val moment = now()
            val next = change(existing).copy(
                updatedAt = moment,
                syncState = SyncState.PENDING.name,
            )
            records.upsertRecord(next)
            enqueue(recordId, OpKind.UPSERT_RECORD, moment)
            true
        }
        if (changed) onWrite(recordId)
    }

    /** 上传失败后手动重试 */
    suspend fun retryFailed(): SyncReport {
        records.timelineOnce().forEach { record ->
            if (record.syncState == SyncState.FAILED.name) {
                records.markSyncFailed(record.id, SyncState.PENDING.name, null)
                val queued = outbox.forTarget(record.id)
                if (queued.isEmpty()) enqueue(record.id, OpKind.UPSERT_RECORD, now())
            }
        }
        return engine.sync()
    }

    suspend fun sync(): SyncReport = engine.sync()

    /**
     * 按保留期清理本地照片视频（记录本身、云端文件都不动）。
     * 返回删掉的文件数，用来在设置里给个反馈。
     *
     * **红线：先确认云端有副本，才允许删本地。**
     * 只清 `uploaded && remotePath != null` 的媒体——那种文件云端已经有一份，
     * 手机上的只是缓存。还没传上去的（授权丢了、Wi-Fi 关了、上传失败）一律留着：
     * 那是这位用户唯一的一份，删了就真没了。
     */
    suspend fun cleanupLocalMedia(retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val cache = mediaCache ?: return 0
        val cutoff = now() - retentionDays * DAY_MS
        var removed = 0

        records.timelineOnce().forEach { record ->
            if (record.createdAt >= cutoff) return@forEach
            records.mediaOf(record.id).forEach { media ->
                val path = media.localPath ?: return@forEach
                if (!media.uploaded || media.remotePath == null) return@forEach
                // 数据库里的路径一定要清掉：不管文件是这次删的还是早就没了，
                // 留着死路径只会让界面去读一个不存在的文件
                val deleted = cache.delete(path)
                records.setMediaLocalPath(media.recordId, media.position, null)
                if (deleted) removed++
            }
        }
        return removed
    }


    /**
     * 清掉早期版本「首次启动写入的示例记录」。
     *
     * 旧版会在空库时自动写三条示例（晚饭 / 午饭 / 风景），产品后来决定：
     * 时间线只展示真实记录，示例数据只会误导家人。这条给已经装过的设备
     * 做一次性善后——按「示例的原文 + 同类型 + 无附件」识别，认出就删掉
     * （连云端墓碑一起），避免别的设备再把它同步回来。
     */
    suspend fun removeSeedSamples(): Int {
        var removed = 0
        records.timelineOnce().forEach { record ->
            val isSeed = SEED_SAMPLES.any { it.second == record.note && it.first.name == record.type }
            if (!isSeed) return@forEach
            if (records.mediaOf(record.id).isNotEmpty()) return@forEach
            if (deleteRecord(record.id, DeleteScope.BOTH) == DeleteOutcome.DONE) removed++
        }
        return removed
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 同一个目标只留一条待办：连续改三次不该排三个上传任务，
     * 否则前面两次传的都是过期内容，白烧配额。
     */
    private suspend fun enqueue(targetId: String, kind: OpKind, moment: Long) {
        outbox.deleteForTarget(targetId)
        outbox.insert(
            OutboxEntity(
                opId = UUID.randomUUID().toString(),
                kind = kind.name,
                targetId = targetId,
                enqueuedAt = moment,
            ),
        )
    }

    /**
     * 当前口径的显示名：本机改过的昵称 / 成员名单里的名字优先，
     * 都没有才回退记录落库时的快照名。改一次名，历史记录全部跟着变。
     */
    private fun latestNameOf(authorId: String, snapshot: String): String {
        val me = user()
        if (authorId == me.id) return me.name.ifBlank { snapshot }
        return knownMembers().firstOrNull { it.id == authorId }?.name
            ?.takeIf { it.isNotBlank() }
            ?: snapshot
    }

    private fun RecordWithChildren.toUiModel(): RecordUiModel {
        val type = runCatching { RecordType.valueOf(record.type) }.getOrDefault(RecordType.OTHER)
        val authorName = latestNameOf(record.authorId, record.authorName)
        return RecordUiModel(
            id = record.id,
            authorId = record.authorId,
            authorName = authorName,
            authorInitial = authorName.trim().take(1).ifBlank { "?" },
            authorColor = avatarColorFor(record.authorId),
            authorAvatarPath = avatarPathOf(record.authorId),
            type = type,
            createdAt = record.createdAt,
            timeLabel = timeLabel(record.createdAt),
            note = record.note,
            media = media.sortedBy { it.position }.map { item ->
                MediaUiModel(
                    localPath = item.localPath,
                    remotePath = item.remotePath,
                    isVideo = item.kind == MediaKind.VIDEO.name,
                    durationLabel = item.durationMs?.let { formatDuration(it) },
                    recordId = record.id,
                    position = item.position,
                )
            },
            rating = record.rating,
            starred = record.starred,
            comments = comments
                .filterNot { it.deleted }
                .sortedBy { it.createdAt }
                .map { comment ->
                    val commentName = latestNameOf(comment.authorId, comment.authorName)
                    CommentUiModel(
                        id = comment.id,
                        authorInitial = commentName.trim().take(1).ifBlank { "?" },
                        authorColor = avatarColorFor(comment.authorId),
                        text = "$commentName：${comment.text}",
                        authorId = comment.authorId,
                    )
                },
            place = record.place,
            coordinate = if (record.latitude != null && record.longitude != null) {
                String.format(Locale.US, "%.5f, %.5f", record.latitude, record.longitude)
            } else {
                null
            },
            syncLabel = when (record.syncState) {
                SyncState.PENDING.name -> "待上传"
                SyncState.FAILED.name -> record.lastError?.let { "上传失败" } ?: "上传失败"
                else -> null
            },
        )
    }

    private fun avatarColorFor(authorId: String) = AVATAR_COLORS[
        (authorId.hashCode().let { if (it == Int.MIN_VALUE) 0 else it } and Int.MAX_VALUE) % AVATAR_COLORS.size,
    ]

    companion object {
        /** 旧版播种的三条示例（类型 to 备注原文），[removeSeedSamples] 按它精确匹配才删 */
        val SEED_SAMPLES = listOf(
            RecordType.DINNER to "今晚的番茄牛腩炖得很到位，饭搭子手艺见长",
            RecordType.LUNCH to "食堂二楼新出的麻辣香锅，值得一试",
            RecordType.SCENERY to "傍晚在江边散步，天边烧起来了",
        )

        const val MAX_MEDIA = 9
        private const val DAY_MS = 86_400_000L
        private val AVATAR_COLORS = listOf(AvatarBlue, AvatarOrange, AvatarGreen)

        fun timeLabel(millis: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

        /** 按本机时区取「第几天」，跨时区/夏令时都不会把记录分错组 */
        fun dayOf(millis: Long): Long = dayOf(Calendar.getInstance().apply { timeInMillis = millis })

        fun dayOf(calendar: Calendar): Long {
            val zero = calendar.clone() as Calendar
            zero.set(Calendar.HOUR_OF_DAY, 0)
            zero.set(Calendar.MINUTE, 0)
            zero.set(Calendar.SECOND, 0)
            zero.set(Calendar.MILLISECOND, 0)
            return zero.timeInMillis / DAY_MS
        }

        /** 「今天 / 昨天 / 10月28日」 */
        fun dayLabel(millis: Long, today: Long = System.currentTimeMillis()): String {
            val target = dayOf(millis)
            val base = dayOf(today)
            return when (base - target) {
                0L -> "今天"
                1L -> "昨天"
                2L -> "前天"
                else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(millis))
            }
        }

        fun formatDuration(millis: Long): String {
            val totalSeconds = millis / 1000
            return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }

        /** 「10月30日 星期五」——分组标题右侧那半句 */
        fun dayTitle(millis: Long): String =
            SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(Date(millis))
    }
}
