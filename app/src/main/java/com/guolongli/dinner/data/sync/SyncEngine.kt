package com.guolongli.dinner.data.sync

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.netdisk.NetdiskException
import java.io.File

/**
 * 同步引擎。规则只有四条，但每条都有代价，写清楚免得以后改坏：
 *
 * 1. **先拉后推**：先把别人的新记录取回来，再把自己的发出去。
 *    反过来的话，自己刚上传的版本会立刻被远端旧版本覆盖回去（虽然 next 次同步会修正，但界面会闪）。
 * 2. **谁新谁赢**：按 updatedAt 比大小；相等且本地还有待传改动时保留本地。
 *    删除不删行，留墓碑（deleted = true，updatedAt 更新），否则别的设备的旧版本会把删掉的记录复活。
 * 3. **失败分两类**：频控 / 断网这类瞬时故障按 1s、2s、4s… 退避重试；
 *    鉴权错误立刻中止整批（继续传也只是浪费配额），参数类错误直接标 FAILED 让用户处理。
 * 4. **媒体先传再写文档**：文档里只存远端路径，所以照片视频必须在上传 JSON 之前落盘，
 *    否则别人拉到这条记录时引用的是空路径。
 */
class SyncEngine(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val recordDir: String = BaiduConfig.RECORD_DIR,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxAttempts: Int = 5,
    private val batchSize: Int = 50,
    private val backoffMs: (attempt: Int) -> Long = { attempt ->
        (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
    },
) {

    /** 一次完整同步：先拉后推 */
    suspend fun sync(): SyncReport {
        val pull = pull()
        // 授权已经失效，推也没用，直接返回
        if (pull.authRequired) {
            return SyncReport(
                pulled = pull.applied,
                stale = pull.stale,
                skipped = pull.skipped,
                pending = local.pendingCount(),
                authRequired = true,
                errors = pull.errors,
                at = now(),
            )
        }
        val push = push()
        return SyncReport(
            pushed = push.pushed,
            pulled = pull.applied,
            stale = pull.stale,
            skipped = pull.skipped,
            failed = push.failed,
            pending = push.pending,
            authRequired = push.authRequired,
            errors = pull.errors + push.errors,
            at = now(),
        )
    }

    // ------------------------------------------------------------------ 拉

    suspend fun pull(): PullResult {
        var applied = 0
        var stale = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        val refs = try {
            remote.listDocuments()
        } catch (e: NetdiskException) {
            return PullResult(
                authRequired = e.isAuthError(),
                errors = listOf(e.message ?: "列举云端文件失败"),
            )
        }

        for (ref in refs) {
            val id = RecordDocument.idOf(ref.name)
            if (id == null) {
                skipped++
                continue
            }

            // 本地比远端新：连下载都省了（下载走 dlink，一次一个文件，别浪费）
            val localUpdated = local.localUpdatedAt(id)
            if (localUpdated != null &&
                ref.modifiedAtSeconds > 0 &&
                localUpdated / 1000 > ref.modifiedAtSeconds
            ) {
                skipped++
                continue
            }

            try {
                val json = remote.downloadDocument(ref.path)
                val doc = RecordDocument.parse(json)
                if (doc == null) {
                    skipped++
                    continue
                }
                when (local.applyRemote(doc)) {
                    ApplyResult.APPLIED -> applied++
                    ApplyResult.STALE -> stale++
                    ApplyResult.UNCHANGED -> skipped++
                }
            } catch (e: NetdiskException) {
                if (e.isAuthError()) {
                    return PullResult(applied, stale, skipped, true, errors + (e.message ?: "授权失效"))
                }
                errors += "${ref.name}: ${e.message}"
                skipped++
            }
        }
        return PullResult(applied, stale, skipped, false, errors)
    }

    // ------------------------------------------------------------------ 推

    suspend fun push(): PushResult {
        var pushed = 0
        var failed = 0
        var deferred = 0
        val errors = mutableListOf<String>()
        var authRequired = false

        val ops = local.pendingOps(batchSize)
        for (op in ops) {
            val moment = now()
            // 还在退避窗口里，这轮跳过
            if (op.nextAttemptAt > moment) {
                deferred++
                continue
            }
            try {
                when (op.kind) {
                    OpKind.DELETE_RECORD -> executeDelete(op)
                    OpKind.UPSERT_RECORD -> executeUpsert(op)
                }
                local.completeOp(op)
                pushed++
            } catch (e: NetdiskException) {
                if (e.isAuthError()) {
                    authRequired = true
                    errors += "需要重新授权：${e.message}"
                    break
                }
                val message = e.message ?: "网盘接口报错 ${e.errno}"
                if (e.isTransient() && op.attempts + 1 < maxAttempts) {
                    local.rescheduleOp(op, message, moment + backoffMs(op.attempts + 1))
                    errors += "稍后重试：$message"
                    deferred++
                } else {
                    local.failOp(op, message)
                    local.markSyncFailed(op.targetId, message)
                    errors += message
                    failed++
                }
            } catch (e: SyncAbortException) {
                // 本地数据问题，重试没意义
                val message = e.message ?: "本地数据异常"
                local.failOp(op, message)
                local.markSyncFailed(op.targetId, message)
                errors += message
                failed++
            } catch (e: Exception) {
                val message = e.message ?: e::class.java.simpleName
                if (op.attempts + 1 < maxAttempts) {
                    local.rescheduleOp(op, message, moment + backoffMs(op.attempts + 1))
                    errors += "稍后重试：$message"
                    deferred++
                } else {
                    local.failOp(op, message)
                    local.markSyncFailed(op.targetId, message)
                    errors += message
                    failed++
                }
            }
        }

        return PushResult(
            pushed = pushed,
            failed = failed,
            deferred = deferred,
            pending = local.pendingCount(),
            authRequired = authRequired,
            errors = errors,
        )
    }

    /** 发件箱里还剩几条，界面拿它显示「N 条待上传」 */
    suspend fun pendingCount(): Int = local.pendingCount()

    /** 单条记录重试：清掉退避窗口，立刻再推一次（界面上「重试」按钮用） */
    suspend fun retryNow(recordId: String): PushResult {
        local.pendingOps(batchSize)
            .filter { it.targetId == recordId && it.nextAttemptAt > now() }
            .forEach { local.rescheduleOp(it, "手动重试", 0L) }
        return push()
    }

    private suspend fun executeUpsert(op: PendingOp) {
        // 1) 媒体先上去，文档里才有路径可写
        // 缺文件绝不能「静默丢掉这张照片」再继续传：那等于把用户的记录悄悄改了，
        // 而且云端那份（如果之前传过）也会被覆盖成「没有这张图」，照片就真没了。
        // 正确做法是整条记录标失败、原样保留，让用户重新添加——ref 的 SyncRepositoryImpl
        // 在本地媒体缺失时就是 return false 让上层标 FAILED 并保留记录。
        local.mediaToUpload(op.targetId).forEach { media ->
            val file = File(media.localPath)
            if (!file.isFile || file.length() == 0L) {
                throw SyncAbortException("本机已找不到这条记录的媒体文件，需要重新添加")
            }
            val remotePath = remote.uploadMedia(media.localPath, mediaName(op.targetId, media))
            local.markMediaUploaded(op.targetId, media.position, remotePath)
        }
        // 2) 再写文档
        val doc = local.loadDocument(op.targetId)
            ?: throw SyncAbortException("本地记录不存在：${op.targetId}")
        val remotePath = remote.uploadDocument(doc)
        local.markSynced(op.targetId, remotePath)
    }

    private suspend fun executeDelete(op: PendingOp) {
        // 删除不删云端文件，而是把墓碑（deleted = true）传上去。
        // 直接删文件的话，别的设备拉列表时只会「少了一项」，永远不知道这条被删了，
        // 于是各自留着一份已经删掉的记录——这才是最难查的那种不同步。
        val doc = local.loadDocument(op.targetId)
            ?: throw SyncAbortException("本地记录不存在：${op.targetId}")
        val remotePath = remote.uploadDocument(doc)
        local.markSynced(op.targetId, remotePath)
    }

    /**
     * 媒体文件在云端的名字：`media-<记录id>-<序号>.<扩展名>`，可读、可追溯、不会撞。
     *
     * **扩展名必须如实反映文件格式**，不能随便起一个名字。原因是对方下载时
     * 会按扩展名决定怎么解析：`.mp4` 走视频解码，`.jpg` 走图片解码，
     * 而一个叫 `.bin` 的文件谁都认不出来——用户点开就是「无法播放」。
     *
     * 所以这里的策略是：
     * - 已知支持的格式（照片与视频）原样保留；
     * - 不认识的扩展名**按媒体的真实类型兜底**：
     *   视频给 `.mp4`，照片给 `.jpg`。
     *
     * 兜底为什么按类型而不是 `.bin`：MediaCache 导入时已经把照片统一转成
     * `.jpg`、视频统一转成 `.mp4`（压缩开启时）或保留相册原格式。
     * 原格式不被系统解码器支持时，兜底成 `.mp4` 至少让系统按视频去试，
     * 报「无法播放」也比报「格式未知」更贴近真相。
     *
     * **注意这层兜底不能反过来骗人**：兜底改的是「云端文件名」，
     * 上传的内容本身没变。所以本地文件如果真是个 .mkv（转码失败留下的），
     * 云端却叫 .mp4，对方下载后按 mp4 解析一样播不了——
     * 但至少报的是「格式不支持」而不是「文件损坏」，
     * 比顶着 .mkv 让对方完全不认识要好。
     */
    private fun mediaName(recordId: String, media: LocalMedia): String {
        val raw = media.localPath.substringAfterLast('.', "").lowercase()
        val ext = when {
            raw in MEDIA_EXTS -> raw
            media.isVideo -> "mp4"
            else -> "jpg"
        }
        return "media-$recordId-${media.position}.$ext"
    }

    private companion object {
        /**
         * 已知能正常处理的扩展名。
         *
         * 视频这一组是**移动端能解码的容器**：mp4/mov/3gp/m4v 是系统原生支持的，
         * webm/mkv 在部分机型上没有解码器，列进来只会让对方拿到一个播不了的附件。
         */
        val MEDIA_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "heic",
            "mp4", "mov", "3gp", "3gpp", "m4v",
        )
    }
}
