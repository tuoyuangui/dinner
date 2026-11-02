package com.guolongli.dinner.data.sync

import com.guolongli.dinner.core.SyncConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.domain.repository.CommentRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.MediaCache
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.PrefetchOutcome
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.SyncOutcome
import com.guolongli.dinner.ui.profile.LocalProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端自建的同步实现。
 *
 * 百度网盘不会推送文件变更，所有同步都必须由本地发起。
 * 策略（见 AGENTS.md）：
 * 1. 本地写入优先，先落 Room 标 PENDING。
 * 2. 网络可用 + 进入应用或手动触发，分批上传（每批 10 条）。
 * 3. 失败指数退避（初始 1s，最多 3 次），超过标 FAILED，仅手动触发时重试，
 *    自动同步不拉 FAILED，避免无限重试（红线）。
 * 4. 冲突 updated_at 较新者胜，保留本地冲突副本，不静默丢弃。
 * 5. 新设备可从 records.json 恢复。
 * 6. 评论随 records.json 一起发布；成员名单单独写 members.json。
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val mealRepository: MealRepository,
    private val commentRepository: CommentRepository,
    private val pairingRepository: PairingRepository,
    private val fileDataSource: BaiduFileDataSource,
    private val credentialStore: CredentialStore,
    private val sessionManager: SessionManager,
    private val profileStore: LocalProfileStore,
    private val storageConfig: StorageConfigRepository,
    private val mediaCache: MediaCache,
) : com.guolongli.dinner.domain.repository.SyncRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    /** 同一时刻只允许一个同步在跑，避免并发上传同一条记录。 */
    private val syncMutex = Mutex()

    @Volatile
    private var cancelled = false

    /** 本次同步里已确认存在的远端目录，避免每条记录都 list 一遍浪费频次。 */
    private val ensuredDirs = mutableSetOf<String>()

    /** 本轮同步的记录快照缓存，避免每个媒体段都全表扫描一次。 */
    @Volatile
    private var roundRecords: List<MealRecord>? = null

    /** 本轮已被占用的远端文件名（含新发出的），用于给新文件让路、避免覆盖。 */
    private val usedRemoteNames = mutableSetOf<String>()

    /**
     * [runCatching] 会把 [CancellationException] 一并包成 `Result.failure`，
     * 协程取消因此被吞掉：界面销毁后同步还在后台跑，直到把配额烧光。
     *
     * 本文件里每个 `runCatching` 的 `onFailure` 与 `catch (t: Throwable)` 都必须先调一次
     * 这个方法，让取消信号原样往上冒；真正的业务失败才留下来记账 / 上报。
     */
    private fun rethrowIfCancelled(error: Throwable) {
        if (error is CancellationException) throw error
    }

    /**
     * 本次同步第一处失败的真实原因。
     *
     * 之前失败只写日志，界面上永远只有「部分同步失败，N 条待手动重试」——
     * 用户根本无从判断是授权过期、频控、还是云端已有同名文件。
     * 这里把原因带到 [SyncOutcome.message]，让界面能说人话。
     */
    private var lastSyncError: String? = null

    override suspend fun syncNow(auto: Boolean): SyncOutcome = syncMutex.withLock {
        cancelled = false
        ensuredDirs.clear()
        // 每轮开头清空缓存：记录在本轮内会被上传改写，上轮的结果不能拿来算名字。
        roundRecords = null
        usedRemoteNames.clear()
        lastSyncError = null

        // 崩溃/强杀可能遗留 SYNCING，先回退为 PENDING，避免永久「上传中」。
        val recovered = mealRepository.recoverStuckSyncing()
        if (recovered > 0) Logger.i("已回退 $recovered 条遗留 SYNCING 记录")
        commentRepository.recoverStuckSyncing()

        // 先确保拿到未过期的 access_token。没凭证/未授权则跳过，交给 UI 引导。
        val hasToken = sessionManager.ensureFreshToken()
        if (!hasToken) {
            Logger.i("同步跳过：无有效 token")
            return@withLock SyncOutcome(
                skipped = true,
                message = sessionManager.lastAuthError ?: "需要先完成授权",
            )
        }

        // 没有群组就没有可写目录（`NetdiskPaths.groupRoot` 会抛 IllegalStateException）。
        //
        // 不能让它冒到 UI：`publishSnapshot` / `publishMembers` 都要 `paths.configDir`，
        // 未入组时这里会直接崩掉，而用户看到的是一句
        // 「尚未加入群组，没有可用的群组目录」的堆栈——同步按钮点一下就闪退。
        // 未入组本来就没有「要同步的东西」，如实跳过比崩掉清晰得多。
        if (storageConfig.paths().groupId.isNullOrBlank()) {
            Logger.i("同步跳过：尚未加入群组")
            return@withLock SyncOutcome(
                skipped = true,
                message = "还没加入群组，没有需要同步的内容",
            )
        }

        var uploaded = 0
        var failed = 0

        // 本轮已经处理过的记录 id。
        //
        // pendingUpload 每次都按 updated_at 从队首取，而失败项刻意保持原 updated_at
        // 不被顶到队尾——于是手动同步（会把 FAILED 一起取回来）下一轮拿到的还是
        // 同一批，while 永远退不出去：界面卡在「同步中」，配额被同一批记录烧光。
        val processed = HashSet<String>()
        var rounds = 0

        // 分批上传，每批 BATCH_SIZE 条。自动同步只取 PENDING，FAILED 仅手动。
        while (!cancelled && rounds < MAX_SYNC_ROUNDS) {
            rounds += 1
            // 先取一批再过滤掉已处理过的：一次取不够 BATCH_SIZE 也没关系，
            // 宁可多查一次也不能漏掉排在后面的记录。
            val batch = mealRepository.pendingUpload(
                limit = SyncConfig.BATCH_SIZE,
                includeFailed = !auto,
            ).filter { it.id !in processed }
            // 过滤后为空说明队列里剩下的都处理过了，可以收工。
            if (batch.isEmpty()) break

            for (record in batch) {
                if (cancelled) break
                // 无论成败都记下来：这一轮不再碰它，避免同一条被反复重试。
                processed += record.id
                mealRepository.updateSyncState(record.id, SyncState.SYNCING, record.updatedAt)
                if (uploadRecord(record)) {
                    uploaded += 1
                } else {
                    failed += 1
                    // 失败不刷新 updatedAt：保持原来的失败隔离顺序，避免顶到队首被反复取到。
                    mealRepository.updateSyncState(record.id, SyncState.FAILED, record.updatedAt)
                }
            }
        }

        if (cancelled) {
            return@withLock SyncOutcome(
                uploaded = uploaded,
                failed = failed,
                skipped = true,
                message = "同步已取消",
            )
        }
        if (rounds >= MAX_SYNC_ROUNDS) {
            // 兜底：队列异常大时别在这里耗到超时，剩下的下一轮再传。
            Logger.w("本轮同步达到上限 $MAX_SYNC_ROUNDS 批，其余记录留到下一轮")
        }

        // 上传完记录后刷新快照，供其他人恢复使用。快照失败独立上报，不依赖 failed==0。
        var incomplete = false
        val snapshotOk = runCatching { publishSnapshot() }
            .onFailure { error ->
                rethrowIfCancelled(error)
                lastSyncError = error.message
            }
            .getOrDefault(false)
        if (!snapshotOk) {
            // 记录传上去了，但快照没写成功 —— 别人那边看不到这一轮的改动。
            // 不能只把原因塞进 message 就当成功：deleteBoth / deleteCloudOnly 拿
            // isSuccess 决定是否抹掉本地行，这里说谎等于把云端删除意图永久丢掉。
            incomplete = true
            return@withLock SyncOutcome(
                uploaded = uploaded,
                failed = failed,
                incomplete = true,
                message = lastSyncError
                    ?.let { "记录已上传，但同步快照写入失败：$it" }
                    ?: "记录已上传，但同步快照写入失败",
            )
        }

        // 成员名单发布：读不到远端就中止本轮（不拿本机名单覆盖），
        // 失败原因要留到 SyncOutcome.message，否则用户只看到「同步完成」，名单其实没发出去。
        runCatching { publishMembers() }
            .onFailure { error ->
                rethrowIfCancelled(error)
                Logger.w("成员名单未发布：${error.message}")
                if (lastSyncError == null) {
                    lastSyncError = "成员名单未同步：${error.message ?: "读不到云端成员名单"}"
                }
                // 名单没发出去同样是「本轮没做完」：新成员 / 改过的昵称别人看不到。
                incomplete = true
            }

        return@withLock SyncOutcome(
            uploaded = uploaded,
            failed = failed,
            incomplete = incomplete,
            // 有失败就必须给出原因，不能只说「失败了几条」。
            // 上传全成、但快照 / 名单没发出去时也要报出来，否则界面只显示「同步完成」。
            message = when {
                failed > 0 -> lastSyncError?.let { "上传失败：$it" } ?: "上传失败，可稍后手动重试"
                incomplete -> lastSyncError
                else -> null
            },
        )
    }

    /**
     * 上传单条记录：把还没上传的媒体逐段传上去，最后标 SYNCED。
     * 纯文字记录没有媒体，直接标记完成。
     *
     * 每传完一段就立刻回写 media_json：中途断网 / 被杀时，
     * 已经传上去的不会在下一轮重传（百度接口频率有限，重复上传很贵）。
     *
     * 返回该条是否全部成功；false 由调用方统一标 FAILED。
     */
    private suspend fun uploadRecord(record: MealRecord): Boolean {
        return try {
            val paths = storageConfig.paths()
            // 云端删除：远端有文件则删除，删干净了才允许标记完成。
            //
            // 早先这里把 delete 包在 runCatching 里吞掉失败后无条件标 SYNCED：
            // 本机显示「已删除」，云端的照片其实还在，别人打开时间线照样看得到，
            // 而且因为本地已经是 SYNCED，这条删除再也不会重传——删除意图彻底丢失。
            if (record.isDeleted) {
                for (item in record.media) {
                    val remote = item.remotePath ?: continue
                    // 越界的路径一律拒绝删除：根目录之外不是本应用的数据，
                    // 删错了既不可恢复，也会动到别人的文件。
                    FileNames.requireInsideRoot(remote, paths.root)
                    // 不吞异常：失败由外层的 catch 统一记账，本条返回 false 标 FAILED 下轮重试。
                    fileDataSource.delete(remote)
                }
                mealRepository.updateSyncState(record.id, SyncState.SYNCED, record.updatedAt)
                return true
            }

            if (record.media.isEmpty()) {
                mealRepository.updateSyncState(record.id, SyncState.SYNCED, record.updatedAt)
                return true
            }

            val pending = record.media.filterNot { it.isUploaded }
            if (pending.isEmpty()) {
                // 媒体早就传完了（上次同步中断在写完状态之前），不用再碰网盘。
                mealRepository.updateSyncState(record.id, SyncState.SYNCED, record.updatedAt)
                return true
            }

            // 群组结构下按「谁传的」分目录：管理员与成员各自维护自己的文件夹。
            val dir = paths.mediaDirOf(
                memberId = record.createdBy,
                year = record.timestamp.toYearInDefaultZone(),
                month = record.timestamp.toMonthInDefaultZone(),
            )
            // 第一次使用（甚至第一次记录）时网盘里还没有 /apps/dinner：
            // 先逐级建出来再传，否则上传会写到一个不存在的目录下。
            // 每次同步只对同一个月份目录做一次，避免浪费有限的接口频次。
            //
            // 必须先建后记：早先是 `ensuredDirs.add(dir)` 成立才去建，
            // 建失败也会被记成「建过了」，这一整轮（乃至之后的每一轮）都跳过建目录，
            // 于是上传全部写到一个并不存在的目录里，报出来的错还含糊其辞。
            if (dir !in ensuredDirs) {
                fileDataSource.ensureDirectory(dir)
                ensuredDirs += dir
            }
            var media = record.media
            for (item in pending) {
                val local = item.localPath
                val localFile = MealRecord.existingFile(local)
                if (localFile == null) {
                    // 本地文件缺失：绝不得假装成功。返回 false 让调用方标 FAILED，
                    // 保留记录供用户补传。
                    Logger.w("本地媒体不存在，标 FAILED 待补传：$local")
                    lastSyncError = "本机已找不到这条记录的媒体文件，需要重新添加"
                    return false
                }
                val remotePath = "$dir/${remoteFileName(record, item)}"
                val md5 = fileDataSource.upload(localFile, remotePath)
                var updated = media.map {
                    if (it.id == item.id) it.copy(remotePath = remotePath, md5 = md5) else it
                }
                // 视频封面单独传一张小图：对端只看时间线时不必为缩略图下整个视频。
                if (item.isVideo) {
                    val coverRemote = uploadCover(record, item, remotePath)
                    if (coverRemote != null) {
                        updated = updated.map {
                            if (it.id == item.id) it.copy(thumbRemotePath = coverRemote) else it
                        }
                    }
                }
                media = updated
                mealRepository.updateMedia(record.id, media)
            }

            mealRepository.updateMedia(record.id, media)
            mealRepository.updateSyncState(record.id, SyncState.SYNCED, record.updatedAt)
            true
        } catch (e: DinnerException.AuthRequired) {
            // 鉴权失效：先尝试刷新，成功则本批自动中止（下一轮带新 token 重试）；
            // 刷新失败则彻底停止并交给上层引导重新授权。
            Logger.w("上传中止：授权失效：${e.message}")
            lastSyncError = e.message
            cancelled = true
            sessionManager.onAuthError(e)
            false
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            Logger.w("上传失败（${record.id}）：${t.message}")
            lastSyncError = (t as? DinnerException)?.message ?: t.message ?: t::class.java.simpleName
            false
        }
    }

    /**
     * 上传视频封面，返回封面的远端路径。
     *
     * 封面是独立的小图（`<视频名>.cover.jpg`），和视频放在同一个目录下。
     * 单独存一份而不是让对端下载视频再抽帧：一屏十条视频，光封面就省下几百 MB。
     *
     * 封面本身拿不到（没生成过 / 文件已清理）就返回 null，不影响视频上传结果——
     * 对端此时显示「视频待下载」占位，行为与老版本一致。
     */
    private suspend fun uploadCover(
        record: MealRecord,
        item: MediaItem,
        remoteVideoPath: String,
    ): String? {
        val coverFile = MealRecord.existingFile(item.thumbPath) ?: return null
        val coverRemote = "$remoteVideoPath$COVER_SUFFIX"
        return try {
            fileDataSource.upload(coverFile, coverRemote)
            coverRemote
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            // 封面失败不判整条失败：视频本体已经传上去了，封面可以下次再补。
            Logger.w("视频封面上传失败（不影响视频本体）：${t.message}")
            null
        }
    }

    /** 把当前全部记录与评论写成 records.json 上传，供其他成员 / 新设备恢复。 */
    private suspend fun publishSnapshot(): Boolean {
        // 取全量（含软删除），让其他人知道哪些记录被删了。
        val all = mealRepository.snapshotAll()
        val comments = commentRepository.snapshotAll()
        val paths = storageConfig.paths()

        val snapshot = RecordsSnapshot(
            updatedAt = System.currentTimeMillis(),
            records = all.map { it.toDto() },
            comments = comments.map { it.toDto() },
        )
        val payload = json.encodeToString(RecordsSnapshot.serializer(), snapshot)

        val temp = File.createTempFile("records", ".json")
        return try {
            temp.writeText(payload)
            // 内容没变就别重传：先算本地 MD5，再与云端那份比。
            // 快照是全量覆盖写，一次上传可能就是几百 KB 到几 MB；
            // 每次同步都无条件重传，白烧配额也拖慢同步。
            val localMd5 = BaiduFileDataSource.localMd5(temp)
            // 只有「确认云端那份与本地一致」才跳过。列目录失败时云端状态未知，
            // 不能假设一致——照常上传（覆盖写是幂等的），宁可多一次请求也不留旧数据。
            val cloudMd5 = fileDataSource.remoteMd5Result(paths.recordsSnapshot)
                .onFailure { Logger.w("无法确认 records.json 云端状态，改为照常上传：${it.message}") }
                .getOrNull()
            if (cloudMd5 != null && cloudMd5 == localMd5) {
                Logger.i("records.json 内容未变，跳过上传")
                commentRepository.markSynced(comments.map { it.id })
                return true
            }
            fileDataSource.ensureDirectory(paths.configDir)
            fileDataSource.upload(temp, paths.recordsSnapshot, verifyRemote = true)
            // 快照发布成功，评论的删除/新增才算真正被其他人看到。
            commentRepository.markSynced(comments.map { it.id })
            true
        } finally {
            temp.delete()
        }
    }

    /**
     * 把本机成员名单写成 members.json，供其他人合并。
     *
     * 必须先读远端再合并写回：members.json 是单个文件，如果直接用自己的名单
     * 覆盖，会把别人刚加入的成员抹掉（后写者胜）。
     *
     * 合并顺序有讲究：远端条目先放，本机条目后放——但只对本机自己那条破例。
     * 自己刚改的昵称必须以本机为准，否则会被远端那份旧名字盖回去，
     * 出现「改了名字，同步一次又变回来」的鬼现象。
     */
    private suspend fun publishMembers(): Boolean {
        val group = pairingRepository.current()
        // 身份迁移后旧 id 已作废：远端名单里若还留着，不能让它跟着写回去。
        val retired = profileStore.observeRetiredIds().first()
        val myName = profileStore.observeMyName().first()
        val mine = group.members
            .filterNot { it.id in retired }
            .map { member ->
                // 「我」这条的昵称以本机档案为准。
                // 名单里自己那条可能还是 decode() 插进去的占位「我」，
                // 直接发上去，对方看到的就是「我」而不是你设的名字。
                if (member.id == group.myId) member.copy(name = myName) else member
            }
        if (mine.isEmpty()) return true

        // 云端交互顺序：先拿到数据 -> 再解析 -> 再上传。
        // 读失败必须中止：把「读不到」当成「远端没人」，会用本机名单把别人的条目抹掉。
        val remote = readMembersSnapshot().getOrElse { error ->
            Logger.w("读不到远端成员名单，本轮跳过发布：${error.message}")
            throw error
        }
        val mergedById = LinkedHashMap<String, Member>()
        remote?.members?.forEach { dto ->
            if (dto.id !in retired) mergedById[dto.id] = dto.toDomain()
        }
        mine.forEach { member ->
            val remote = mergedById[member.id]
            when {
                // 「我」这一条永远以本机为准。
                member.id == group.myId -> mergedById[member.id] = member
                remote == null -> mergedById[member.id] = member
                // 远端那条没昵称（老文件 / 老版本写的），别让它把本地的好名字顶掉。
                remote.name.isBlank() && member.name.isNotBlank() ->
                    mergedById[member.id] = member
                // 其余情况远端优先，这样对方改的昵称能落到本机。
            }
        }
        val merged = mergedById.values
            .sortedBy { it.joinedAt }
            .take(GroupState.MAX_MEMBERS)
            .toList()

        // 先把合并结果落本地，UI 立刻能看到新成员。
        pairingRepository.replaceMembers(merged)

        // 管理员：本机显式指定过的优先（刚转让完就发布），否则沿用远端，最后回落最早加入的人。
        val adminId = group.adminId
            ?.takeIf { id -> merged.any { it.id == id } }
            ?: remote?.adminId?.takeIf { id -> merged.any { it.id == id } }
            ?: merged.minByOrNull { it.joinedAt }?.id

        val paths = storageConfig.paths()
        val snapshot = MembersSnapshot(
            updatedAt = System.currentTimeMillis(),
            members = merged.map { it.toDto() },
            adminId = adminId,
        )
        val payload = json.encodeToString(MembersSnapshot.serializer(), snapshot)

        val temp = File.createTempFile("members", ".json")
        return try {
            temp.writeText(payload)
            // 同快照：名单没变就不上传。改昵称、加人才会真正产生新内容。
            val localMd5 = BaiduFileDataSource.localMd5(temp)
            // 同上：拿不到云端现状就照常上传，不猜「内容没变」。
            val cloudMd5 = fileDataSource.remoteMd5Result(paths.membersSnapshot)
                .onFailure { Logger.w("无法确认 members.json 云端状态，改为照常上传：${it.message}") }
                .getOrNull()
            if (cloudMd5 != null && cloudMd5 == localMd5) {
                Logger.i("members.json 内容未变，跳过上传")
                return true
            }
            fileDataSource.ensureDirectory(paths.configDir)
            fileDataSource.upload(temp, paths.membersSnapshot, verifyRemote = true)
            true
        } finally {
            temp.delete()
        }
    }

    /**
     * 读取远端成员名单。
     *
     * **必须区分「文件不存在」与「读取失败」**：
     * - 返回 `Result.success(null)`：目录列出来了、就是没有 members.json（首次同步）；
     * - 返回 `Result.failure(...)`：列目录 / 取 dlink / 下载 / 解析任何一步失败。
     *
     * 过去两者都返回 null，`publishMembers` 就把「读失败」当成「远端没人」，
     * 拿本机名单把远端那份覆盖掉——别人的成员条目被直接抹掉。
     * 云端交互的顺序必须是「先拿到数据 -> 再解析 -> 再上传」，
     * 拿不到就中止，不能拿空数据往下走。
     */
    private suspend fun readMembersSnapshot(): Result<MembersSnapshot?> {
        val paths = storageConfig.paths()
        val entries = fileDataSource.list(paths.configDir)
        val entry = entries.firstOrNull { it.path == paths.membersSnapshot }
            ?: return Result.success(null)
        val meta = fileDataSource.fileMeta(entry.fsId)
            ?: return Result.failure(DinnerException.Local("取不到 members.json 的文件信息"))
        val dlink = meta.dlink?.takeIf { it.isNotBlank() }
            ?: return Result.failure(DinnerException.Local("取不到 members.json 的下载地址"))
        val token = credentialStore.token()?.accessToken
            ?: return Result.failure(DinnerException.AuthRequired("token 已失效，请重新授权"))

        val target = File.createTempFile("members-snapshot", ".json")
        return try {
            fileDataSource.download(dlink, target, token)
            if (!target.isFile || target.length() == 0L) {
                throw DinnerException.Local("下载到的 members.json 为空")
            }
            Result.success(
                json.decodeFromString(MembersSnapshot.serializer(), target.readText())
            )
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            Logger.w("成员名单读取失败：${t.message}")
            Result.failure(t)
        } finally {
            target.delete()
        }
    }

    override suspend fun restoreFromRemote(): Result<List<MealRecord>> = runCatching {
        // 必须先续期：access_token 只有 30 天。早先这里直接读 credentialStore.token()，
        // 过期后就地抛「尚未授权」，而 refresh_token 明明还能换——
        // 用户看到的是「网盘上有记录，这台设备就是取不回来」。
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "需要先完成网盘授权，才能取回云端记录"
            )
        }

        // 未加入群组 = 没有任何可读目录（`NetdiskPaths.groupRoot` 会抛
        // IllegalStateException）。这不是错误，是正常状态：刚授权、还没建组的设备
        // 本来就没有云端记录可取。
        //
        // 真机实测暴露的问题：授权成功会自动调一次 `restoreFromRemote`，
        // 那时用户还没建组，于是 `fetchSnapshot` 抛错、退化成 `scanMealsFromRemote`，
        // 又因为要 `listRecursively(paths.groupRoot)` 再抛一次，
        // 日志里刷两条「读取 records.json 快照失败」的红字，最终 result 仍是失败。
        // 用户看到的是「授权成功了，但取回云端历史失败」，实际上这台设备
        // 根本没有群组，没东西可取——如实返回空列表比报错清晰。
        if (storageConfig.paths().groupId.isNullOrBlank()) {
            Logger.i("取回跳过：尚未加入群组，云端没有可读目录")
            return@runCatching emptyList()
        }

        // 优先读 records.json 快照；读不到再退回扫描 meals/ 目录。
        val snapshot = runCatching { fetchSnapshot() }
            .onFailure { error -> rethrowIfCancelled(error) }
            .getOrNull()
        val records = snapshot?.records?.map { it.toDomain() }
            ?: scanMealsFromRemote()

        if (records.isNotEmpty()) {
            // 冲突处理：updated_at 较新者胜，并保留本地冲突副本。
            mergeByUpdatedAt(records)
        }

        // 评论与成员是附属数据，失败不阻断记录恢复。
        snapshot?.comments
            ?.takeIf { it.isNotEmpty() }
            ?.let { dtos ->
                runCatching { commentRepository.mergeFromRemote(dtos.map { it.toDomain() }) }
                    .onFailure { error -> rethrowIfCancelled(error) }
            }
        runCatching { mergeMembersFromRemote() }
            .onFailure { error -> rethrowIfCancelled(error) }

        records
    }.onFailure { error ->
        // 协程取消不是「失败」：这里不重抛的话，界面销毁后同步协程被吞成 Result.failure，
        // 既不会真的停下，也会把「同步失败」误报给用户。
        rethrowIfCancelled(error)
    }

    /**
     * 合并远端成员名单：按 id 去重，保留先加入的顺序，超出上限截断。
     *
     * 同名条目以「有昵称的那份」为准：远端 members.json 可能是老版本写的，
     * 里面没有 name 字段（读出来是空串），无脑用远端覆盖会把本地好好的昵称
     * 抹成空，时间线上就只剩「成员」。
     */
    private suspend fun mergeMembersFromRemote() {
        // 同样区分「没有」与「读失败」：读失败直接放弃本轮合并，别拿空名单覆盖本机。
        val remote = readMembersSnapshot().getOrElse { error ->
            Logger.w("合并成员名单失败，本轮跳过：${error.message}")
            return
        } ?: return
        // 旧身份已退役：远端可能还没跟上（我们上次同步失败），别把旧 id 又合并回来。
        val retired = profileStore.observeRetiredIds().first()
        val mine = pairingRepository.current().members.filterNot { it.id in retired }
        val merged = (mine + remote.members.map { it.toDomain() }.filterNot { it.id in retired })
            .groupBy { it.id }
            .map { (_, candidates) ->
                val named = candidates.firstOrNull { it.name.isNotBlank() } ?: candidates.first()
                named.copy(joinedAt = candidates.minOf { it.joinedAt })
            }
            .sortedBy { it.joinedAt }
            .toList()
        pairingRepository.replaceMembers(merged)
        // 管理员也可能刚被转让，跟随远端；远端没指定就保持本地回退逻辑。
        pairingRepository.adoptRemoteAdmin(remote.adminId)
    }

    /**
     * 读取远端 records.json 快照；读不到返回 null。
     *
     * 这里**整段包在 runCatching 里**：列目录、取 dlink、下载、解析任何一步失败
     * 都要退化成 null，让上层走「扫描 meals/ 兜底」那条路。
     * 早先列目录是裸调用，config 目录不存在时（首次使用 / 目录结构变动）
     * 异常会一路冒到 restoreFromRemote 的最外层，把整个取回判成失败——
     * 用户看到的是「云端有记录，就是取不回来」。
     */
    private suspend fun fetchSnapshot(): RecordsSnapshot? = runCatching {
        val paths = storageConfig.paths()
        val files = fileDataSource.list(paths.configDir)
        val entry = files.firstOrNull { it.path == paths.recordsSnapshot }
            ?: return@runCatching null
        val meta = fileDataSource.fileMeta(entry.fsId) ?: return@runCatching null
        val dlink = meta.dlink ?: return@runCatching null
        val token = credentialStore.token() ?: return@runCatching null

        val target = File.createTempFile("records-snapshot", ".json")
        try {
            fileDataSource.download(dlink, target, token.accessToken)
            json.decodeFromString(RecordsSnapshot.serializer(), target.readText())
        } finally {
            target.delete()
        }
    }.onFailure { error ->
        rethrowIfCancelled(error)
        Logger.w("读取 records.json 快照失败，改为扫描 meals/：${error.message}")
    }.getOrNull()

    /**
     * 新设备兜底：扫描 meals/ 下的媒体文件，生成占位记录以便重建时间线。
     *
     * 只在 records.json 读不到时才走这条路，所以它必须真的能用。
     * 早先这里用 `name.substringBefore('_').toLongOrNull()` 取时间戳，但实际文件名
     * 是 `2026-10-28-user-1.jpg`，既没有下划线也不是纯数字——每一张都在
     * mapNotNull 里被丢掉，兜底等于永远返回空列表。
     * 现在走 [FileNames.dayMillisOf]，按现行命名解析，一天内的多张归到同一天 0 点。
     *
     * **只认现行命名**：旧命名的文件会被跳过并记一条日志。
     * 这条路径本来就是「records.json 读不到」时的下下策，
     * 正常恢复走快照里的显式 remotePath，不受文件名影响。
     *
     * 另外两处曾经让兜底结果无法使用：
     * - `createdBy` 写死空串：所有人扫出来的记录作者都是「未知」，
     *   成员筛选、头像绑定、按人统计全部失效；
     * - `id` 直接用远端 md5：两个人存了同一份文件时 id 相同，
     *   合并时后一条把前一条顶掉，时间线上凭空少一条。
     */
    private suspend fun scanMealsFromRemote(): List<MealRecord> {
        val paths = storageConfig.paths()
        // 群组结构下媒体在 group_<adminPhone>/members/<mid>/meals/yyyy/MM，
        // 从群组根算起有四层，扫描深度要跟着放宽。
        val files = fileDataSource.listRecursively(paths.groupRoot, maxDepth = 6)
        return files
            .filter { !it.isDirectory }
            // 只认媒体目录里的文件：群组根下还有 config/*.json 与 avatars/*.jpg，
            // 不筛掉会被当成一条条「没有备注的餐食记录」。
            .filter { entry -> entry.path.contains("/$MEALS_DIR_SEGMENT/") }
            .mapIndexedNotNull { index, entry ->
                val name = entry.path.substringAfterLast('/')
                val timestamp = FileNames.dayMillisOf(name)
                if (timestamp == null) {
                    // 只认现行命名（`yyyy-MM-dd_HH_mm_ss[_n].<ext>`），旧命名一律跳过。
                    // 必须留痕：不打日志的话，网盘上明明有老照片、用户却只看到「取回 0 条」，
                    // 完全无从判断是文件没了还是名字不认识。
                    Logger.w("兜底扫描跳过非现行命名的文件：$name")
                    return@mapIndexedNotNull null
                }
                val isVideo = FileNames.isVideoExtension(FileNames.rawExtension(entry.path))
                // 作者从路径里的 `/members/<id>/` 还原：兜底扫描没有快照可读，
                // 这是唯一能知道「这张是谁的」的地方。
                // 作者 id 必须是真实成员 id（红线）。路径里还原不出作者时跳过这个文件，
                // 绝不用空串造一条「无主记录」：空作者最终只会显示成「成员」，
                // 既认不出是谁传的，也没法按人清理。
                val author = authorOfPath(entry.path)?.takeIf { it.isNotBlank() }
                if (author == null) {
                    Logger.w("兜底扫描跳过无法还原作者的文件：" + entry.path)
                    return@mapIndexedNotNull null
                }
                MealRecord(
                    // 用路径哈希消歧而不是遍历下标：下标会随翻页 / 过滤变化而漂移，
                    // 同一条记录两次扫描会拿到两个 id，于是恢复出重复条目。
                    id = "scan-" + entry.path.hashCode().toUInt().toString(16) + "-" + timestamp,
                    timestamp = timestamp,
                    mealType = com.guolongli.dinner.domain.model.MealType.DEFAULT,
                    note = "",
                    createdBy = author,
                    syncState = SyncState.SYNCED,
                    // 加序号消歧：同一秒内的多个文件 mtime 相同，
                    // updated_at 打平会让 mergeByUpdatedAt 判不出先后。
                    updatedAt = entry.serverMtime * 1000 + index,
                    deletedAt = null,
                    rating = null,
                    media = listOf(
                        MediaItem(
                            id = "remote-1",
                            mediaType = if (isVideo) {
                                com.guolongli.dinner.domain.model.MediaType.VIDEO
                            } else {
                                com.guolongli.dinner.domain.model.MediaType.IMAGE
                            },
                            remotePath = entry.path,
                            md5 = entry.md5,
                        ),
                    ),
                )
            }
    }

    /**
     * 只恢复一段时间内的记录。
     *
     * 全程只读一份 records.json（一次 list + 一次下载），再按时间与类型过滤，
     * 所以补一个月和补一年花的请求数一样——对 100 次/小时的开发期配额很关键。
     * 媒体文件不在这里拉：时间线会按需补下载，避免一次恢复把配额烧光。
     */
    override suspend fun restoreRange(
        fromMillis: Long,
        toMillis: Long,
        types: Set<com.guolongli.dinner.domain.model.MealType>,
    ): Result<Int> = restoreRangeInternal(fromMillis, toMillis, types, fetchMedia = true)

    /**
     * [restoreRange] 的实现。
     *
     * [fetchMedia] 单独拎出来是因为 [prefetchRecent] 会调用这里：
     * 自动预取自己有更紧的预算与「封面优先」策略，不该被用户主动取回那一份
     * 大预算的媒体下载抢先跑一遍，否则一次进应用就把配额翻倍烧掉。
     */
    private suspend fun restoreRangeInternal(
        fromMillis: Long,
        toMillis: Long,
        types: Set<com.guolongli.dinner.domain.model.MealType>,
        fetchMedia: Boolean,
    ): Result<Int> = runCatching {
        // 同 restoreFromRemote：先续期再读，别把「token 过期」报成「尚未授权」。
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "需要先完成网盘授权，才能从云端取回记录"
            )
        }
        require(toMillis > fromMillis) { "时间区间不合法" }

        // 未加入群组时没有可读目录。这里同样如实返回 0 条，不要让它崩在
        // `paths.groupRoot` 的 IllegalStateException 上——用户主动点「补一段时间」
        // 却被告知「记录取回失败」，而他其实还没建群。
        if (storageConfig.paths().groupId.isNullOrBlank()) {
            Logger.i("区间取回跳过：尚未加入群组")
            return@runCatching 0
        }

        val snapshot = fetchSnapshot()
        // 快照读不到（还没发布过 / 目录异常）时退回扫描媒体文件，
        // 与 restoreFromRemote 保持同一套兜底：能从云端看见记录，就不该报「没有快照」。
        if (snapshot == null) {
            val scanned = scanMealsFromRemote()
                .filter { it.timestamp in fromMillis until toMillis }
                .filter { types.isEmpty() || it.mealType in types }
            if (scanned.isEmpty()) return@runCatching 0
            mergeByUpdatedAt(scanned)
            val revealedByScan = mealRepository.clearLocalRemovedInRange(fromMillis, toMillis)
            if (revealedByScan > 0) Logger.i("已恢复 $revealedByScan 条本机隐藏的记录")
            Logger.i("云端无快照，按媒体文件恢复了 ${scanned.size} 条记录")
            return@runCatching scanned.size
        }
        val picked = snapshot.records
            .filter { it.timestamp in fromMillis until toMillis }
            .map { it.toDomain() }
            .filter { types.isEmpty() || it.mealType in types }

        if (picked.isEmpty()) return@runCatching 0

        // 与本地做一次合并：以 updated_at 较新者胜，不覆盖本地更新过的内容。
        mergeByUpdatedAt(picked)

        // 用户明确要求补这一段：本机为省空间藏起来的记录也得重新出现，
        // 否则「补了 12 条」和「时间线上多出 0 条」同时成立，没人信。
        val revealed = mealRepository.clearLocalRemovedInRange(fromMillis, toMillis)
        if (revealed > 0) Logger.i("已恢复 $revealed 条本机隐藏的记录")

        // 媒体：照片全部取回，视频只取封面。
        // 用户主动点「补一段时间」就是要能看见内容，只把元数据拉回来、
        // 点开每张都是「还没取回」等于没补。视频正片几十 MB，留给点开时按需下。
        if (fetchMedia) {
            runCatching { fetchMediaForRange(fromMillis, toMillis) }
                .onFailure { error ->
                    rethrowIfCancelled(error)
                    Logger.w("区间媒体补取失败：${error.message}")
                }
        }

        // 评论跟着一起补，否则补回来的记录点开是空的。
        val ids = picked.map { it.id }.toSet()
        snapshot.comments
            .filter { it.mealId in ids }
            .takeIf { it.isNotEmpty() }
            ?.let { dtos ->
                runCatching { commentRepository.mergeFromRemote(dtos.map { it.toDomain() }) }
                    .onFailure { error -> rethrowIfCancelled(error) }
            }

        Logger.i("已从云端恢复 ${picked.size} 条记录（${fromMillis}..${toMillis}）")
        picked.size
    }.onFailure { error -> rethrowIfCancelled(error) }

    /**
     * 冲突处理：updated_at 较新者胜，不静默丢弃本地版本。
     *
     * - 远端比本地新：以远端作为主版本覆盖。
     * - 若本地存在未上传改动（PENDING/FAILED/SYNCING），远端覆盖时保留一条
     *   本地冲突副本（新 id），避免本地改动被静默丢弃（红线）。
     * - 远端恢复的记录一律以 SYNCED 落库：远端是权威来源，删除等意图已由
     *   其他人提交，本端无需再把删除/改动重新排队，防止互相重传造成回落。
     * - 本地被打过「只删本机副本」标记：云端既然还有这条，就让它重新可见。
     *   这是「补一段时间」补完却看不见记录的直接原因——内容不用改，
     *   但 local_removed_at 不清，时间线永远不展示它。
     */
    private suspend fun mergeByUpdatedAt(remote: List<MealRecord>) {
        val localById = remote.associate { it.id to mealRepository.getById(it.id) }

        val toApply = mutableListOf<MealRecord>()
        for (incoming in remote) {
            val existing = localById[incoming.id]
            when {
                existing == null -> toApply += incoming.copy(syncState = SyncState.SYNCED)
                incoming.updatedAt > existing.updatedAt -> {
                    // 远端较新。若本地有未同步改动，保留本地冲突副本，不静默丢弃。
                    if (existing.syncState != SyncState.SYNCED) {
                        // 冲突副本必须显式标 FAILED，绝不能沿用原来的 PENDING：
                        // 续上 PENDING 的话，下一轮自动同步会把这个副本当成新改动推上云，
                        // 等于用冲突副本二次污染云端，别人会看到两条几乎一样的记录。
                        // FAILED 只由用户手动触发重试（红线：自动同步不碰 FAILED）。
                        val localCopy = existing.copy(
                            id = existing.id + "-conflict",
                            syncState = SyncState.FAILED,
                        )
                        toApply += localCopy
                    }
                    toApply += incoming.copy(syncState = SyncState.SYNCED)
                }
                // 远端不新于本地，但本地当初为了省空间把它藏起来了：恢复可见。
                existing.isLocallyRemoved -> toApply += existing.copy(localRemovedAt = null)
                // 其余情况保留本地版本，不覆盖。
            }
        }
        if (toApply.isNotEmpty()) {
            mealRepository.upsertAll(toApply)
            Logger.i("已合并 ${toApply.size} 条远端记录")
        }
    }

    /**
     * 生成上传文件名：`记录创建时间.jpg`，形如 `2026-10-29_11_22_35.jpg`。
     *
     * 一条记录最多 9 段媒体，而它们共用同一个记录时间，秒级精度区分不开，
     * 所以重名时按 `_2`、`_3` 往后加后缀（[FileNames.withDedupSuffix]）。
     *
     * 名字必须唯一：上传走 rtype=3 覆盖写，撞名等于无声盖掉先传上去的那段。
     * 判断依据是本轮缓存的快照里已存在的远端文件名，不再逐段全表扫描。
     */
    private suspend fun remoteFileName(record: MealRecord, item: MediaItem): String {
        // 照片导入时一律转成 JPEG（含 HEIF / PNG）；视频按原格式或压缩后的 mp4 走，
        // 所以扩展名必须从实际落盘文件取，不能写死。
        val extension = if (item.isVideo) {
            FileNames.videoExtensionOf(item.localPath)
        } else {
            "jpg"
        }

        roundSnapshot()
        var sequence = 1
        var candidate = FileNames.timestampedMedia(record.timestamp, extension)
        // 撞上已有名字就往后顺延，直到找到一个没被占用的。
        while (!usedRemoteNames.add(candidate)) {
            sequence += 1
            candidate = FileNames.withDedupSuffix(record.timestamp, sequence, extension)
        }
        return candidate
    }

    /**
     * 本轮同步的快照缓存。
     *
     * 记录在本轮内只会被本地改动，snapshotAll() 的结果复用即可。
     * 早先每个待上传段都调一次 snapshotAll()，一条记录 9 段就是 9 次全表扫描
     * + 逐条解码 media_json；这里每轮只取一次。
     */
    private suspend fun roundSnapshot(): List<MealRecord> {
        val cached = roundRecords
        if (cached != null) return cached
        val loaded = mealRepository.snapshotAll()
        roundRecords = loaded
        // 已被占用的远端文件名：从快照里登记，用于给新文件让路。
        for (record in loaded) {
            for (media in record.media) {
                val remote = media.remotePath.orEmpty()
                if (remote.isBlank()) continue
                usedRemoteNames.add(remote.substringAfterLast('/'))
            }
        }
        return loaded
    }

    /**
     * 预取近 N 天：记录全拉回，图片全下载，视频只拉封面。
     *
     * 顺序刻意是「先拉记录、再补媒体」：
     * 1. 先 `restoreRange` 把 records.json 里这段时间的记录合并进本地库——
     *    时间线立刻就有东西可看，哪怕图还没下完；
     * 2. 再逐段补媒体。图片直接落 localPath；视频只下封面落到 thumbPath，
     *    正片留给用户点开时按需拉（点开走 RecordDetailViewModel 的 fetchRemoteMedia）。
     *
     * 全程幂等：已有本地文件的不重复下载，中断后重跑只会补没下完的那部分。
     * 单条失败只计数不中断——不能因为一张图下不动就让整批预取归零。
     */
    override suspend fun prefetchRecent(days: Int): Result<PrefetchOutcome> = runCatching {
        val to = System.currentTimeMillis()
        val from = to - days * 24L * 60 * 60 * 1000

        // 只合并元数据：媒体由下面的预取逻辑按自己的预算与策略下载，
        // 不能让 restoreRange 那份大预算的下载先跑一遍。
        val restored = restoreRangeInternal(from, to, emptySet(), fetchMedia = false)
            .getOrThrow()
        val records = mealRepository.rangeAll(from, to)

        var images = 0
        var covers = 0
        var skipped = 0
        var failed = 0

        // 不再设「每轮最多 N 张」的硬预算：用户要的就是「这段时间内的文件全部到本机」。
        // 省请求靠的是 md5 校验——本地已有的直接跳过，不重复下载；
        // 真正缺的才发请求。首次使用会一次性下完，之后每轮只补新增的那几张。
        for (record in records) {
            if (cancelled) break
            if (record.isDeleted || record.isLocallyRemoved) continue
            for (item in record.media) {
                if (cancelled) break
                val remote = item.remotePath ?: continue
                val isCoverOnly = item.isVideo
                if (isCoverOnly) {
                    // 视频只补封面；正片留给点开时按需拉。
                    val coverRemote = item.thumbRemotePath ?: continue
                    if (localMediaUsable(item.thumbPath, null)) {
                        skipped += 1
                        continue
                    }
                    mediaCache.resolve(coverRemote)
                        .onSuccess { file ->
                            bindMedia(record.id, item.id, thumbPath = file.absolutePath)
                            covers += 1
                        }
                        .onFailure { failed += 1 }
                } else {
                    // 照片：存在且 md5 对得上才算「已有」，否则重下。
                    if (localMediaUsable(item.localPath, item.md5)) {
                        skipped += 1
                        continue
                    }
                    mediaCache.resolve(remote)
                        .onSuccess { file ->
                            bindMedia(record.id, item.id, localPath = file.absolutePath)
                            images += 1
                        }
                        .onFailure { failed += 1 }
                }
            }
        }

        Logger.i(
            "预取近 $days 天：记录 $restored 条，新下载图片 $images、封面 $covers，" +
                "已存在跳过 $skipped，失败 $failed"
        )
        PrefetchOutcome(
            days = days,
            records = restored,
            imagesDownloaded = images,
            coversDownloaded = covers,
            failed = failed,
        )
    }.onFailure { error -> rethrowIfCancelled(error) }

    /**
     * 区间取回时的媒体补取：**照片全下，视频只下封面**。
     *
     * 与 [prefetchRecent] 的区别只在预算：那个是进应用时自动跑的后台预取，
     * 靠「已缓存即跳过」压住请求量，不设每轮硬上限；
     * 这里是用户明确点了「补回这段时间」，该给的都要给，只受
     * [RESTORE_MEDIA_BUDGET] 兜住极端情况（一次补几年会撑爆配额）。
     *
     * 单条失败只计数不中断：一张图下不动不该让整批归零。
     */
    private suspend fun fetchMediaForRange(fromMillis: Long, toMillis: Long) {
        val records = mealRepository.rangeAll(fromMillis, toMillis)
        var budget = RESTORE_MEDIA_BUDGET
        var images = 0
        var covers = 0
        var failed = 0

        for (record in records) {
            if (budget <= 0) break
            if (record.isDeleted || record.isLocallyRemoved) continue
            for (item in record.media) {
                if (budget <= 0) break
                val remote = item.remotePath ?: continue
                if (item.isVideo) {
                    // 视频只补封面：正片留给用户点开时按需取。
                    if (localMediaUsable(item.thumbPath, null)) continue
                    val coverRemote = item.thumbRemotePath ?: continue
                    budget -= 1
                    mediaCache.resolve(coverRemote)
                        .onSuccess { file ->
                            bindMedia(record.id, item.id, thumbPath = file.absolutePath)
                            covers += 1
                        }
                        .onFailure { failed += 1 }
                } else {
                    if (localMediaUsable(item.localPath, item.md5)) continue
                    budget -= 1
                    mediaCache.resolve(remote)
                        .onSuccess { file ->
                            bindMedia(record.id, item.id, localPath = file.absolutePath)
                            images += 1
                        }
                        .onFailure { failed += 1 }
                }
            }
        }
        Logger.i("区间取回媒体：照片 $images，封面 $covers，失败 $failed")
    }

    /**
     * 本地已有这份媒体、且内容与记录里的 md5 对得上，才允许跳过下载。
     *
     * 只判断「文件存在且非空」是不够的：断网中断下载会留下半截文件，
     * 缓存目录里也可能有同名但内容不同的残留。这类文件一旦被当成有效缓存，
     * 图片就永远显示不出来、而且再也不会重下——正是「省带宽」省出的事故。
     *
     * 记录里没有 md5（老数据 / 还没成功上传过）时退回存在性判断：
     * 没有可比对的基准，重下反而是浪费。
     */
    private fun localMediaUsable(localPath: String?, expectedMd5: String?): Boolean {
        val file = MealRecord.existingFile(localPath) ?: return false
        val expected = expectedMd5?.takeIf { it.isNotBlank() } ?: return true
        return BaiduFileDataSource.localMd5(file).equals(expected, ignoreCase = true)
    }

    /** 把下好的文件回填进记录，让时间线立刻能看到。 */
    private suspend fun bindMedia(
        recordId: String,
        mediaId: String,
        localPath: String? = null,
        thumbPath: String? = null,
    ) {
        val record = mealRepository.getById(recordId) ?: return
        val updated = record.media.map { item ->
            if (item.id != mediaId) return@map item
            item.copy(
                localPath = localPath ?: item.localPath,
                thumbPath = thumbPath ?: item.thumbPath,
            )
        }
        mealRepository.updateMedia(recordId, updated)
    }

    override suspend fun cancelAll() {
        cancelled = true
        Logger.i("同步任务已取消")
    }

    private fun Long.toYearInDefaultZone(): Int =
        java.time.Instant.ofEpochMilli(this)
            .atZone(java.time.ZoneId.systemDefault()).year

    private fun Long.toMonthInDefaultZone(): Int =
        java.time.Instant.ofEpochMilli(this)
            .atZone(java.time.ZoneId.systemDefault()).monthValue

    /**
     * 从远端路径里还原上传者：`.../members/<id>/meals/yyyy/MM/xxx.jpg` -> `<id>`。
     *
     * 兜底扫描时没有 records.json 可读，只能靠目录结构判断这张照片是谁传的。
     * 目录结构对不上（老数据 / 手工挪动过）返回 null，由调用方回落空作者，
     * 不要凭空猜一个 id。
     */
    private fun authorOfPath(path: String): String? {
        val start = path.indexOf(MEMBERS_DIR_SEGMENT)
        if (start < 0) return null
        val rest = path.substring(start + MEMBERS_DIR_SEGMENT.length)
        return rest.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private companion object {
        /** 成员目录的路径段名，兜底扫描时用它从路径里还原作者。 */
        const val MEMBERS_DIR_SEGMENT = "/members/"
        /** 媒体目录的路径段名，扫描兜底时用它区分「照片」与「快照 / 头像」。 */
        const val MEALS_DIR_SEGMENT = "meals"

        /** 视频封面的远端后缀，拼在视频路径之后。 */
        const val COVER_SUFFIX = ".cover.jpg"

        /**
         * 单轮同步的批数上限，兜住「已处理集合」也没拦住的极端情况
         * （例如队列被外部不断写入新记录）。
         *
         * 达到上限只是本轮到此为止，剩下的记录留到下一轮，不算失败。
         */
        const val MAX_SYNC_ROUNDS = 200

        /**
         * 用户主动「补一段时间」时的媒体预算。
         *
         * 比自动预取宽得多（用户点了就是要内容），但仍留上限：
         * 一次补几年、每条九个视频封面，请求数会到四位数，开发期配额直接见底。
         */
        const val RESTORE_MEDIA_BUDGET = 120
    }
}
