package com.guolongli.dinner.data.maintenance

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.data.netdisk.RemoteFile
import com.guolongli.dinner.data.sync.RecordDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一次云端整理的账目 */
data class JanitorReport(
    /** 清掉的墓碑（`deleted = true` 的记录文件）数量 */
    val tombs: Int = 0,
    /** 清掉的孤儿附件（对应记录已经不在了的照片视频）数量 */
    val media: Int = 0,
    /** 释放的云端空间，字节 */
    val bytes: Long = 0L,
    /** 因为超过单次上限而留到下次的数量 */
    val deferred: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && deferred == 0

    val touched: Boolean get() = tombs > 0 || media > 0

    fun summary(): String = when {
        error != null -> "整理失败：$error"
        tombs == 0 && media == 0 -> "云端没有需要整理的东西"
        deferred > 0 -> "清掉 ${tombs + media} 个文件，还有 $deferred 个下次再说"
        else -> "清掉 $tombs 份墓碑、$media 个附件，释放 ${megabytes(bytes)}"
    }

    private fun megabytes(bytes: Long): String = when {
        bytes <= 0 -> "0 KB"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

/**
 * 云端整理：把没用的文件从网盘里清掉。
 *
 * 只有两类东西会被删，界线必须先讲清楚，否则改两行就会删到活数据：
 *
 * 1. **墓碑**。删掉的记录不删云端文件，而是写一份 `deleted = true` 的 JSON 上去，
 *    这样别的设备拉列表时能看到「这条被删了」，而不是「少了一项」。墓碑留够
 *    [BaiduConfig.TOMBSTONE_RETENTION_DAYS] 天后就只剩占位作用，可以清掉。
 * 2. **孤儿附件**。云端记录已经彻底不存在（连墓碑都被清了），但对应的
 *    `media-<记录id>-<序号>.<扩展名>` 还躺在 media 目录里。这类文件谁都读不到，
 *    只是白占空间。
 *
 * 两条硬保护，别去掉：
 *
 * - **只看「老文件」**。候选文件的服务端修改时间必须早于截止时间。正在上传的附件、
 *   刚建的记录时间都很新，扫不到它们，也就不会出现「删掉别人正在写的东西」。
 * - **记录还在就不动附件**。附件只在「对应记录在云端完全不存在」时才删；
 *   记录还在、只是被删过（墓碑还没到期）的，附件继续留着。
 *
 * 删记录文件本身不是不可逆的：极端情况下（某台设备离线超过墓碑保留期，
 * 回来后又把旧记录推了上去），这条记录会「复活」。这是保留期设计的已知代价，
 * 比云端无限堆积几千份墓碑要好。
 */
class CloudJanitor(
    private val netdisk: () -> NetdiskRepository,
    private val recordDir: String = BaiduConfig.RECORD_DIR,
    private val mediaDir: String = BaiduConfig.MEDIA_DIR,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 单次最多删多少个文件，见 [BaiduConfig.JANITOR_MAX_DELETES] */
    private val maxDeletes: Int = BaiduConfig.JANITOR_MAX_DELETES,
) {

    /** 同一时刻只允许一轮整理：两轮并发会重复下载同一批墓碑，白烧配额 */
    private val mutex = Mutex()

    suspend fun run(
        retentionDays: Int = BaiduConfig.TOMBSTONE_RETENTION_DAYS,
    ): JanitorReport = withContext(Dispatchers.IO) {
        mutex.withLock { sweep(retentionDays) }
    }

    private suspend fun sweep(retentionDays: Int): JanitorReport {
        val repo = netdisk()
        val days = retentionDays.coerceAtLeast(1)
        val cutoff = now() / 1000 - days * SECONDS_PER_DAY

        val docs = runCatching { repo.list(recordDir) }
            .getOrElse { e -> return JanitorReport(error = e.message ?: "列举云端记录失败") }
            .filter { !it.isDir }

        // 云端还有哪些记录（不区分活着的和墓碑）：附件要不要删看它
        val docIds = docs.mapNotNull { RecordDocument.idOf(it.name) }.toSet()

        var tombs = 0
        var media = 0
        var bytes = 0L
        var deferred = 0
        val purged = mutableSetOf<String>()

        for (file in docs) {
            val id = RecordDocument.idOf(file.name) ?: continue
            if (!isOldEnough(file.modifiedAt, cutoff)) continue
            if (tombs + media >= maxDeletes) {
                deferred++
                continue
            }
            // 先确认它真是墓碑再删。名字看不出来，只能读一份——所以先用时间过滤，
            // 只下载「老得可能有资格被清」的那些，正常情况下一天也就几个。
            val doc = readDocument(repo, file) ?: continue
            if (!doc.deleted) continue
            if (!runCatching { repo.delete(file.path) }.isSuccess) continue
            tombs++
            bytes += file.size
            purged += id
        }

        val files = runCatching { repo.list(mediaDir) }.getOrDefault(emptyList())
        for (file in files) {
            if (file.isDir) continue
            if (!isOldEnough(file.modifiedAt, cutoff)) continue
            val recordId = recordIdOfMedia(file.name) ?: continue
            // 记录还在（含刚被清掉墓碑的那些）→ 附件继续留着；
            // 其余都是孤儿：对应记录在云端已经不存在了。
            if (recordId in docIds && recordId !in purged) continue
            if (tombs + media >= maxDeletes) {
                deferred++
                continue
            }
            if (!runCatching { repo.delete(file.path) }.isSuccess) continue
            media++
            bytes += file.size
        }

        return JanitorReport(tombs = tombs, media = media, bytes = bytes, deferred = deferred)
    }

    private suspend fun readDocument(
        repo: NetdiskRepository,
        file: RemoteFile,
    ): RecordDocument? = runCatching {
        val temp = File.createTempFile("janitor-", ".json")
        try {
            repo.download(file, temp)
            if (!temp.isFile || temp.length() == 0L) return@runCatching null
            RecordDocument.parse(temp.readText())
        } finally {
            temp.delete()
        }
    }.getOrNull()

    /**
     * 修改时间是 0 的（老接口/异常返回）一律当成「新文件」跳过。
     * 宁可少清一个，也不要因为拿不到时间就误删。
     */
    private fun isOldEnough(modifiedAt: Long, cutoff: Long): Boolean =
        modifiedAt in 1..cutoff

    companion object {
        private const val SECONDS_PER_DAY = 86_400L

        /** 附件名长这样：`media-<记录id>-<序号>.<扩展名>`，记录 id 是 UUID，本身含短横线 */
        private val MEDIA_NAME = Regex("""^media-(.+)-(\d+)\.[A-Za-z0-9]+$""")

        fun recordIdOfMedia(fileName: String): String? =
            MEDIA_NAME.matchEntire(fileName)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }
}
