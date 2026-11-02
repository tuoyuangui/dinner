package com.guolongli.dinner.data.maintenance

import android.content.Context
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一次本地清理的结果。UI 必须如实展示，不能只说「已优化」。 */
data class RetentionOutcome(
    val days: Int,
    val scanned: Int = 0,
    val removed: Int = 0,
    val freedBytes: Long = 0,
    val keptUnconfirmed: Int = 0,
) {
    val isNoop: Boolean get() = removed == 0

    /** 人类可读的结论，直接放进提示条。 */
    val message: String
        get() = when {
            days == StorageConfigRepository.PERSIST_FOREVER -> "当前是永久保留，不会清理本地数据"
            scanned == 0 -> "没有超过 $days 天的本地记录"
            removed == 0 && keptUnconfirmed > 0 ->
                "有 $keptUnconfirmed 条记录没能在云端确认，先留着不删"
            removed == 0 -> "没有超过 $days 天的本地记录"
            else -> "已清理 $removed 条 $days 天前记录的本机副本，释放 ${formatBytes(freedBytes)}，" +
                "云端与记录本身都还在"
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 本地保留策略的执行者。
 *
 * 规则（红线）：**先确认云端有副本，才允许删本地**。
 * 一次清理最多发「每个目录一次」的 list 请求，把确认成本压到最低；
 * 任何一步确认不了（没授权、网络失败、频控），这条记录原样保留，绝不猜。
 *
 * 删除范围严格限定在应用私有目录里的媒体文件 + Room 行；
 * 网盘上的文件一个都不动——那是别人也能看到的数据。
 */
@Singleton
class LocalRetentionTask @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mealRepository: MealRepository,
    private val fileDataSource: BaiduFileDataSource,
    private val sessionManager: SessionManager,
    private val storageConfig: StorageConfigRepository,
) {

    suspend fun run(): RetentionOutcome {
        val days = storageConfig.retentionDays()
        if (days == StorageConfigRepository.PERSIST_FOREVER) {
            return RetentionOutcome(days = days)
        }

        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val candidates = mealRepository.purgeCandidates(cutoff)
        if (candidates.isEmpty()) return RetentionOutcome(days = days)

        // 没有有效 token 就无法确认云端，什么都不删。
        if (!sessionManager.ensureFreshToken()) {
            Logger.i("本地清理跳过：无有效授权，无法确认云端副本")
            return RetentionOutcome(days = days, scanned = candidates.size, keptUnconfirmed = candidates.size)
        }

        val listings = mutableMapOf<String, Set<String>?>()
        var removed = 0
        var freed = 0L
        var unconfirmed = 0
        val cleaned = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (record in candidates) {
            if (!confirmedInCloud(record, listings)) {
                unconfirmed += 1
                continue
            }
            freed += deleteLocalFiles(record)
            // 文件已经删掉，media_json 里的 localPath / thumbPath 就成了悬空路径：
            // 界面拿着它去读一个不存在的文件，于是「已清理」的记录点开是空白或报错，
            // 而 localMediaUsable 判「文件不存在」又说不清「是没下载过还是被清理过」。
            // 先把这两个字段清空再落库，等于明确告诉后面所有逻辑：
            // 本机没有这份媒体，要看就去云端取。
            if (record.media.isNotEmpty()) {
                runCatching {
                    mealRepository.updateMedia(
                        record.id,
                        record.media.map { item -> item.copy(localPath = null, thumbPath = null) },
                    )
                }.onFailure { error ->
                    // 取消信号不能被当成清理失败吞掉。
                    if (error is CancellationException) throw error
                    Logger.w("清理后回填 media_json 失败（${record.id}）：${error.message}")
                }
            }
            cleaned += record.id
            removed += 1
        }

        if (cleaned.isNotEmpty()) {
            // 只打「本机不展示」标记，绝不删记录行。
            //
            // 早先这里调的是 hardDeleteAll：本地行一没，这条记录在 app 里就彻底消失了，
            // 而用户看到的是「只删本机副本，云端不受影响」。云端确实没动，
            // 但下次发布快照时它也从 records.json 里消失，换设备就再也取不回来。
            // 现在记录行留在库里（快照照常发布），只是本机时间线不再展示；
            // 「我的 → 从云端取回历史」可以把标记清掉重新看见。
            cleaned.forEach { id ->
                runCatching { mealRepository.localRemove(id, now) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Logger.w("标记本机隐藏失败（$id）：${error.message}")
                    }
            }
            Logger.i("本地清理完成：${cleaned.size} 条记录的本机副本，释放 $freed 字节")
        }

        return RetentionOutcome(
            days = days,
            scanned = candidates.size,
            removed = removed,
            freedBytes = freed,
            keptUnconfirmed = unconfirmed,
        )
    }

    /**
     * 这条记录的每一段媒体都确实在云端。
     *
     * 纯文字记录没有媒体文件，只要它是 SYNCED（已写进 records.json 快照）就算确认。
     * 还有没上传成功的媒体段，一律视为未确认。
     */
    private suspend fun confirmedInCloud(
        record: MealRecord,
        listings: MutableMap<String, Set<String>?>,
    ): Boolean {
        if (record.pendingMediaCount > 0) return false
        if (record.media.isEmpty()) return true

        for (item in record.media) {
            val remote = item.remotePath ?: return false
            val dir = remote.substringBeforeLast('/')
            val names = listings.getOrPut(dir) { listDirectory(dir) } ?: return false
            if (remote.substringAfterLast('/') !in names) return false
        }
        return true
    }

    /** 目录列表；失败返回 null（= 无法确认），调用方据 null 保留记录。 */
    private suspend fun listDirectory(dir: String): Set<String>? = runCatching {
        fileDataSource.list(dir)
            .filterNot { it.isDirectory }
            .map { it.path.substringAfterLast('/') }
            .toSet()
    }.onFailure { error ->
        // 取消不算「无法确认」，别让它在下面被当成 null 走保留分支。
        if (error is CancellationException) throw error
        Logger.w("目录列表失败，本次不清理该目录下的记录：$dir（${error.message}）")
    }.getOrNull()

    /** 删掉本机文件，返回释放的字节数。只碰应用私有目录下的文件。 */
    private fun deleteLocalFiles(record: MealRecord): Long {
        val privateRoot = context.filesDir.absolutePath
        var freed = 0L
        record.media.forEach { item ->
            listOfNotNull(item.localPath, item.thumbPath).forEach { path ->
                if (!path.startsWith(privateRoot)) return@forEach
                val file = File(path)
                if (!file.isFile) return@forEach
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        return freed
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
