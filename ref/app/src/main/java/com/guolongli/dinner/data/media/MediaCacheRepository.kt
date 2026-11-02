package com.guolongli.dinner.data.media

import android.content.Context
import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.MediaCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远端媒体缓存实现。
 *
 * 下载链路：list(父目录) 找 fs_id -> multimedia/filemetas 取 dlink
 * -> dlink 拼 access_token 下载 -> 落盘到私有目录。
 *
 * 之所以要 list 一次：记录里只存了网盘路径，没存 fs_id；
 * 而获取 dlink 必须用 fs_id。父目录列表做 60 秒短缓存，
 * 一屏十几张照片只花一次 list 调用，避免触发 20012 频控。
 */
@Singleton
class MediaCacheRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDataSource: BaiduFileDataSource,
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
    private val storageConfig: StorageConfigRepository,
) : MediaCache {

    private val root: File
        get() {
            val dir = File(context.filesDir, "media_cache").apply { mkdirs() }
            if (legacySwept.compareAndSet(false, true)) {
                sweepLegacyLayout(dir)
            }
            return dir
        }

    /** 旧版哈希布局的清只做一次：清完就与它无关了。 */
    private val legacySwept = AtomicBoolean(false)

    /**
     * 清掉旧版 `<路径哈希>/<文件名>` 平铺布局的残留目录。
     *
     * 只认 16 位十六进制目录名（旧 hashOf 的输出形态）：现行布局的顶层只有
     * `group_*` 与 `_loose`，子目录是 `config`/`avatars`/`members`/`meals`/年月，
     * 都不可能是恰好 16 位十六进制，不会误伤。旧布局已不再识别，留着只占空间。
     */
    private fun sweepLegacyLayout(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && LEGACY_HASH_DIR.matches(it.name) }
            .forEach { legacy ->
                val deleted = legacy.deleteRecursively()
                Logger.d("清理旧版媒体缓存目录：${legacy.name}（deleted=$deleted）")
            }
    }

    /**
     * 最近一次读到的网盘根目录，用于把远端路径还原成本地相对路径。
     *
     * 存一份是为了让 [localFile] 保持纯函数（它被 [cachedFile] 这种非挂起方法调用）；
     * 根目录改动只影响之后新下载的文件。
     */
    @Volatile
    private var cachedRoot: String = NetdiskPaths.DEFAULT_ROOT

    /** 同一路径并发解析只允许一个下载，其余等待结果。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 父目录列表短缓存：目录 -> (过期时间, 文件名 -> fs_id)。 */
    private val directoryCache = ConcurrentHashMap<String, CachedDirectory>()

    private data class CachedDirectory(
        val expiresAt: Long,
        val files: Map<String, Long>,
    )

    override fun cachedFile(remotePath: String): File? =
        localFile(remotePath).takeIf { it.isFile && it.length() > 0L }

    override suspend fun resolve(remotePath: String): Result<File> {
        val paths = storageConfig.paths()
        // 先刷新根目录再算本地路径：本地目录要与当前网盘结构镜像，
        // 否则改了根目录之后新下载的文件会落到旧前缀对应的目录里。
        cachedRoot = paths.root
        FileNames.requireInsideRoot(remotePath, paths.root)
        cachedFile(remotePath)?.let { return Result.success(it) }

        // 锁条目用完**不能摘**。
        //
        // 早先持锁区间一结束就 `locks.remove`：两个协程几乎同时进来时，
        // 前者建锁 -> 后者 getOrPut 拿到同一个锁 -> 前者结束时把锁摘掉 ->
        // 第三个协程 getOrPut 建出一把新锁，于是同一路径被并发下载两次，
        // 而两边写的是同一个 tmp 文件，互相覆盖后留下半截内容当缓存。
        // 锁的数量受「本次会话解析过的远端文件数」约束，不会无限增长。
        val mutex = locks.getOrPut(remotePath) { Mutex() }
        return mutex.withLock {
            // 双重检查：等锁的这段时间里，可能已经有别的协程把文件下好了。
            cachedFile(remotePath)?.let { return@withLock Result.success(it) }
            runCatching { download(remotePath) }
                .onFailure { error ->
                    // 取消信号不能被当成「下载失败」记下来，否则同步停不下来。
                    if (error is CancellationException) throw error
                }
        }
    }

    private suspend fun download(remotePath: String): File {
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired("尚未完成网盘授权")
        }
        val token = credentialStore.token()?.accessToken
            ?: throw DinnerException.AuthRequired("尚未完成网盘授权")

        val fsId = findFsId(remotePath)
            ?: throw DinnerException.Local("网盘上找不到这个文件，可能已被对端删除")

        val meta = fileDataSource.fileMeta(fsId)
            ?: throw DinnerException.Local("无法获取文件信息")
        val dlink = meta.dlink?.takeIf { it.isNotBlank() }
            ?: throw DinnerException.Local("无法获取下载地址")

        val target = localFile(remotePath)
        target.parentFile?.mkdirs()

        // 先下到临时文件再改名。
        //
        // 直接写目标文件的话，下载中途断网会留下一个「存在且非空」的半截文件，
        // [cachedFile] 就把它当成有效缓存返回了：图片永远显示成一条灰，
        // 而且因为「已经有缓存」，再也不会重新下载。
        //
        // 临时文件名带随机后缀：固定成 `<目标名>.tmp` 时，同一个路径的两次下载
        // （并发解析、或上一次被杀留下的残留）会往同一个临时文件里写，
        // 两边交错写入的结果就是一个内容错乱的文件被改名成正式缓存。
        val temp = File(target.parentFile, "${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            fileDataSource.download(dlink, temp, token)
            if (!temp.isFile || temp.length() == 0L) {
                throw DinnerException.Local("下载失败，文件为空")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }

        if (!target.isFile || target.length() == 0L) {
            target.delete()
            throw DinnerException.Local("下载失败，文件为空")
        }
        Logger.d("媒体已缓存：${target.name} (${target.length()} bytes)")
        return target
    }

    /** 在父目录中按完整路径找到 fs_id，目录列表带 60 秒缓存。 */
    private suspend fun findFsId(remotePath: String): Long? {
        val dir = remotePath.substringBeforeLast('/').ifBlank { storageConfig.paths().root }
        val name = remotePath.substringAfterLast('/')

        val cached = directoryCache[dir]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            cached.files[name]?.let { return it }
        }

        val entries = fileDataSource.list(dir)
        val files = entries
            .filterNot { it.isDirectory }
            .associate { it.path.substringAfterLast('/') to it.fsId }
        directoryCache[dir] = CachedDirectory(
            expiresAt = System.currentTimeMillis() + DIRECTORY_TTL_MS,
            files = files,
        )
        return files[name]
    }

    /**
     * 缓存文件路径：**与云盘目录结构一致**。
     *
     * `/apps/dinner/group_xxx/members/138xxx/meals/2026/10/a.jpg`
     * 对应 `filesDir/media_cache/group_xxx/members/138xxx/meals/2026/10/a.jpg`。
     *
     * 镜像云盘结构后，「哪些已经在本地」退化成一次文件存在性检查，
     * 也能对着网盘目录逐个核对。
     *
     * 文件名仍走 [FileNames.localFileName]，保证落到本地后是合法 ASCII 名。
     */
    private fun localFile(remotePath: String): File {
        // 去掉根目录前缀，只保留群组以下的那一段：本地根就是 media_cache，
        // 没必要再套一层 apps/dinner。
        val relative = remotePath
            .removePrefix(cachedRoot.trimEnd('/') + "/")
            .trimStart('/')
            .ifBlank { FileNames.localFileName(remotePath) }

        val segments = relative.split('/').filter { it.isNotBlank() && it != ".." }
        if (segments.isEmpty()) {
            return File(File(root, "_loose"), FileNames.localFileName(remotePath))
        }
        var dir = root
        segments.dropLast(1).forEach { segment -> dir = File(dir, segment) }
        return File(dir, FileNames.localFileName(segments.last()))
    }

    private companion object {
        const val DIRECTORY_TTL_MS = 60_000L
        val LEGACY_HASH_DIR = Regex("[0-9a-f]{16}")
    }
}
