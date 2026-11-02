package com.guolongli.dinner.data.media

import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.local.MediaEntity
import com.guolongli.dinner.data.local.MediaKind
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 补齐媒体时的分页游标。
 *
 * 三级键 (记录时间, 媒体序号, 记录 id) 必须严格单调，否则会漏记录：
 * 两条记录可能同一毫秒创建，它们的第 0 张图 position 也都是 0。
 * 只用两级游标就会把「同时间同序号、但记录 id 更大」的那条整个跳过。
 */
data class MediaCursor(
    val createdAt: Long = 0L,
    val position: Int = -1,
    val recordId: String = "",
) {
    /** 前进到下一条；只有确实更大的位置才更新，保证严格单调 */
    fun advance(nextCreatedAt: Long, nextPosition: Int, nextRecordId: String): MediaCursor {
        if (nextCreatedAt > createdAt) {
            return MediaCursor(nextCreatedAt, nextPosition, nextRecordId)
        }
        if (nextCreatedAt < createdAt) return this
        if (nextPosition > position) {
            return MediaCursor(createdAt, nextPosition, nextRecordId)
        }
        if (nextPosition < position) return this
        if (nextRecordId > recordId) {
            return MediaCursor(createdAt, position, nextRecordId)
        }
        return this
    }
}

/**
 * 把云端有、本地没有的照片视频补回来（别人的推文、换设备后的历史记录都在内）。
 *
 * 两档用法：
 * - [restoreAll]：同步后自动跑，**不设时间窗**，分批把所有缺失媒体下完——
 *   时间线上的图片视频因此总是可以直接打开看，不用等用户翻到那一段再取。
 * - [restoreOne]：点开还没下载的格子时按需取回单个文件，下完立刻能看。
 *
 * 防死循环的规则：一轮里下不来的文件（云端真没了 / 网络抖）记入失败集，
 * 本轮不再重试，下一轮同步重新来过——失败文件不会把整批下载卡成无限循环。
 */
class MediaRestorer(
    private val db: DinnerDatabase,
    private val netdisk: () -> NetdiskRepository,
    private val mediaCache: MediaCache,
) {

    /**
     * 全量补回所有缺失媒体（别人推文的图片视频、自己换设备后的历史都在内）。
     *
     * 分批查询、逐个下载：每成功一个就把 localPath 写回库，
     * 下一轮查询自然少一项，直到没有缺失为止。
     *
     * **为什么要用「按位置往前推」的游标，而不是每次都从头查**：
     * 查询条件是 `localPath IS NULL`，成功的文件写回库之后就不再命中，
     * 但**失败的文件还在库里**。如果每轮都从头查，失败的那批会一直排在最前面，
     * 后面还没试过的文件永远轮不到——补齐就卡在那儿了。
     * 所以这里记住「已经试到哪一条」，失败的不再重复试（下一轮同步自然会重试）。
     *
     * 单轮上限 [perRunCap] 个文件，剩下的交给下一次同步——
     * 一次同步不该因为几百张旧照片拖住几分钟。
     *
     * @return 成功补回的文件数
     */
    suspend fun restoreAll(perRunCap: Int = 300): Int = withContext(Dispatchers.IO) {
        val dao = db.recordDao()
        // 已经试过并且失败的远端路径：本轮不再重试，避免死循环
        val failed = mutableSetOf<String>()
        var restored = 0
        // 游标：记录时间 + 媒体序号 + 记录 id（三级，保证严格单调）
        var cursor = MediaCursor()

        while (restored < perRunCap) {
            val pending = dao.mediaMissingAfter(
                cursor.createdAt,
                cursor.position,
                cursor.recordId,
                QUERY_BATCH,
            ).filterNot { (it.remotePath ?: "") in failed }
            if (pending.isEmpty()) break
            for (media in pending) {
                if (restored >= perRunCap) return@withContext restored
                // 记录时间查不到（理论上不会：SQL 是 INNER JOIN records），
                // 或者远端路径是空串——这两种情况都**必须推进游标**，
                // 否则同一批数据会被反复取出来，构成死循环。
                val createdAt = dao.recordCreatedAt(media.recordId) ?: 0L
                val remote = media.remotePath
                if (!remote.isNullOrBlank()) {
                    if (downloadOne(media, remote) != null) restored++ else failed.add(remote)
                }
                // 游标往前走：即使这条失败了，也不再回头查它
                cursor = cursor.advance(createdAt, media.position, media.recordId)
            }
        }
        restored
    }

    /**
     * 按需取回单个媒体，返回本地路径。
     * 已经在本机的直接返回，不会重复下载。
     */
    suspend fun restoreOne(recordId: String, position: Int): String? = withContext(Dispatchers.IO) {
        val dao = db.recordDao()
        val media = dao.mediaOf(recordId).firstOrNull { it.position == position } ?: return@withContext null
        media.localPath?.let { path ->
            if (java.io.File(path).isFile) return@withContext path
        }
        val remote = media.remotePath
        if (remote.isNullOrBlank()) return@withContext null
        downloadOne(media, remote)
    }

    /**
     * @param sinceMillis 只要这个时间之后的记录
     * @param limit 单次最多取几个文件
     * @return 成功补回的文件数
     */
    suspend fun restore(sinceMillis: Long, limit: Int = 30): Int = withContext(Dispatchers.IO) {
        val dao = db.recordDao()
        val pending = dao.mediaMissing(sinceMillis, limit)
        pending.count { media ->
            val remote = media.remotePath ?: return@count false
            downloadOne(media, remote) != null
        }
    }

    /** 下载一个缺失文件；成功写回 localPath 并返回本地路径，失败删掉半成品返回 null */
    private suspend fun downloadOne(media: MediaEntity, remote: String): String? {
        val dao = db.recordDao()
        val extension = extensionFor(media, remote)
        val target = mediaCache.newFile("restore-${media.recordId}", extension)
        val ok = runCatching {
            netdisk().download(remote, target)
            target.isFile && target.length() > 0
        }.getOrDefault(false)
        return if (ok) {
            dao.setMediaLocalPath(media.recordId, media.position, target.absolutePath)
            target.absolutePath
        } else {
            target.delete()
            null
        }
    }

    /**
     * 下载回来的文件用什么扩展名。
     *
     * **必须和云端那个文件真实的格式一致**：系统播放器是看扩展名决定
     * 用哪种容器去解析的，名字不对就直接罢工（表现是「点开没反应」）。
     *
     * 云端名字是 `media-<记录id>-<序号>.<扩展名>`，正常情况下扩展名是对的；
     * 但老版本上传时把不认识的格式改成了 `.bin`，那份文件拉回来照样放不了。
     * 这时按媒体的真实类型兜底：视频给 `.mp4`、照片给 `.jpg`。
     */
    private fun extensionFor(media: MediaEntity, remote: String): String {
        val raw = remote.substringAfterLast('.', "").lowercase()
        if (raw in KNOWN_EXTS) return raw
        return if (media.kind == MediaKind.VIDEO.name) "mp4" else "jpg"
    }

    private companion object {
        /** 一次向数据库要多少条缺失媒体；只是分批大小，循环会继续取 */
        const val QUERY_BATCH = 100

        /**
         * 系统认识、能直接解码的扩展名。
         *
         * 只有这些能原样沿用云端名字；`bin` 这类占位名要走类型兜底。
         */
        val KNOWN_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "heic",
            "mp4", "mov", "3gp", "3gpp", "m4v",
        )
    }
}
