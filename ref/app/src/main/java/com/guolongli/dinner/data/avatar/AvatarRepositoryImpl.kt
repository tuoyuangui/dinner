package com.guolongli.dinner.data.avatar

import android.content.Context
import android.net.Uri
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.media.MediaImporter
import com.guolongli.dinner.domain.media.MediaSource
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 头像同步实现。
 *
 * 所有成员共用一个网盘账号，头像按各自的稳定 authorId 隔离，
 * 每人一个固定远端文件：`<根目录>/avatars/<authorId>.jpg`，
 * 避免多人争夺同一个文件名互相覆盖。
 *
 * 本地缓存文件名带版本号：`<authorId>__v<时间戳>.jpg`。
 * 为什么不用固定的 `<authorId>.jpg`：头像文件路径不变时，
 * 本地状态（Map<String, File>）内容相等，不会触发任何界面刷新，
 * 图片库也会按路径命中旧缓存，于是「换了头像时间线还是旧图」。
 * 换名即换路径，刷新与缓存失效两件事一起解决；旧版本文件顺手删掉。
 *
 * 缓存统一放 filesDir：cacheDir 会被系统或「清理缓存」抹掉，
 * 抹掉后只能显示文字头像，看起来就像「头像丢了」。
 */
@Singleton
class AvatarRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val fileDataSource: BaiduFileDataSource,
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
    private val mediaImporter: MediaImporter,
    private val profileStore: LocalProfileStore,
    private val storageConfig: StorageConfigRepository,
) : AvatarRepository {

    private val cacheDir: File = File(context.filesDir, "avatars").apply { mkdirs() }

    private val avatars = MutableStateFlow(loadCached())

    override fun observeAvatars(): Flow<Map<String, File>> = avatars.asStateFlow()

    override fun observeMyAvatar(): Flow<File?> = flow {
        val myId = profileStore.observeAuthorId().first()
        emitAll(
            avatars.map { map -> map[myId] }
        )
    }

    override suspend fun setMyAvatar(uri: Uri): Result<Unit> = runCatching {
        val imported = mediaImporter.import(MediaSource.Image(uri))
            .getOrElse { throw it }
            .localPath
        val myId = profileStore.observeAuthorId().first()
        val source = File(imported)
        if (!source.exists()) error("头像文件不存在")

        // 版本号取当前时间：新文件等于新路径，界面与图片缓存都会跟着换。
        val stable = File(cacheDir, AvatarFiles.versionedName(myId, System.currentTimeMillis()))
        source.copyTo(stable, overwrite = true)

        if (sessionManager.ensureFreshToken()) {
            val paths = storageConfig.paths()
            // 头像换了就是新路径（文件名带版本号），但要上传的内容本身可能与上一次相同，
            // 先比 md5 能省掉一次多余上传。头像文件小，省的是请求次数而不是流量。
            val localMd5 = BaiduFileDataSource.localMd5(stable)
            // 只有「确认云端与本地一致」才跳过；拿不到云端现状就照常上传。
            val cloudMd5 = fileDataSource.remoteMd5Result(paths.avatarOf(myId))
                .onFailure { Logger.w("无法确认头像云端状态，改为照常上传：${it.message}") }
                .getOrNull()
            if (cloudMd5 != null && cloudMd5 == localMd5) {
                Logger.i("头像内容未变，跳过上传")
            } else {
                fileDataSource.ensureDirectory(paths.avatarsRoot)
                fileDataSource.upload(stable, paths.avatarOf(myId))
            }
        } else {
            Logger.w("未授权，头像仅保存在本地，授权后自动同步")
        }
        // loadCached 会顺手清掉同一成员的旧版本文件。
        avatars.value = loadCached()
    }.onFailure { error ->
        // 协程取消必须原样抛出，否则 ViewModel 销毁后这里还会继续写文件。
        if (error is CancellationException) throw error
    }

    /**
     * 头像同步用一把互斥锁串起来。
     *
     * 真机实测踩到的坑：建组成功后 `createGroup` 先 `syncNow` 再 `syncAvatars`，
     * 与此同时 `GroupViewModel` 的 `combine(groupId, session, pairing)` 因为成员名单
     * 变化也会触发一次 `syncAvatars`。两路并发时，日志里看到的是
     * `<-- HTTP FAILED: java.io.IOException: Canceled` ——`records.json` 的分片上传
     * 刚发出去就被取消，紧接着又去建 avatars 目录，整轮同步报废。
     *
     * 头像同步是幂等的（覆盖写固定路径），并发跑没有任何收益，只会互相打断。
     * 这里加锁之后，第二路会等第一路跑完，然后照常按 md5 判断「不用重传」直接返回。
     */
    private val syncMutex = Mutex()

    override suspend fun syncAvatars(memberIds: List<String>): Boolean {
        // 用普通函数体而不是表达式函数：里面要拿 withLock 的返回值，
        // 表达式形式容易在协程推断上绕圈子，函数体最直白。
        return syncMutex.withLock { syncAvatarsLocked(memberIds) }
    }

    private suspend fun syncAvatarsLocked(memberIds: List<String>): Boolean {
        if (!sessionManager.ensureFreshToken()) return false

        val myId = profileStore.observeAuthorId().first()
        val paths = storageConfig.paths()

        // 本机若有头像（含离线时已设置但未上传的），覆盖式上传，保证远端与本地一致。
        // 上传前先比 md5：头像同步会在每次成员变化时触发，内容没变就不该反复重传。
        val mine = latestFileOf(myId)
        if (mine != null) {
            runCatching {
                val localMd5 = BaiduFileDataSource.localMd5(mine)
                val cloudMd5 = fileDataSource.remoteMd5Result(paths.avatarOf(myId))
                    .onFailure { Logger.w("无法确认头像云端状态，改为照常上传：${it.message}") }
                    .getOrNull()
                if (cloudMd5 == null || cloudMd5 != localMd5) {
                    fileDataSource.ensureDirectory(paths.avatarsRoot)
                    fileDataSource.upload(mine, paths.avatarOf(myId))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Logger.w("头像上传失败：${error.message}")
            }
        }

        val entries = runCatching {
            fileDataSource.list(paths.avatarsRoot)
        }.onFailure { error ->
            // 取消不能在下面被当成「列目录失败」直接 return false。
            if (error is CancellationException) throw error
        }.getOrElse { return false }

        val fsIds = entries
            .filterNot { it.isDirectory }
            .associate { entry ->
                entry.path.substringAfterLast('/').substringBeforeLast('.') to entry.fsId
            }

        val token = credentialStore.token()?.accessToken ?: return true
        var allOk = true

        // 串行下载：接口频率有限，一屏十几个头像并发会直接触发频控。
        for (id in memberIds.distinct()) {
            if (id.isBlank()) continue
            val fsId = fsIds[id] ?: continue
            // 先用远端 md5 与本机当前那份比对，一致才跳过。
            //
            // 早先只要本地存在这个人的头像文件就 `continue`：
            // 别人换头像后会把同一个远端文件覆盖掉，而本机因为「已经有头像」
            // 永远不去下载，于是对方换了头像，本机时间线还是几个月前那张旧图。
            val current = latestFileOf(id)
            val remoteMd5 = fileDataSource.remoteMd5Result(paths.avatarOf(id)).getOrNull()
            if (remoteMd5 != null && current != null &&
                BaiduFileDataSource.localMd5(current).equals(remoteMd5, ignoreCase = true)
            ) {
                continue
            }
            // 走到这里有两种情况：本机没有、或本机内容与云端不一致（含拿不到云端现状）。
            // 后者也照常下载：覆盖写是幂等的，宁可多花一次请求也不能一直显示旧图。
            val meta = fileDataSource.fileMeta(fsId) ?: continue
            val dlink = meta.dlink ?: continue
            // 文件名带版本号：换了头像就是新路径，界面刷新与图片库缓存失效一起解决，
            // 不会出现「头像文件变了但界面还是旧图」。
            val target = File(cacheDir, AvatarFiles.versionedName(id, System.currentTimeMillis()))
            runCatching { fileDataSource.download(dlink, target, token) }
                .onSuccess {
                    // 只在新文件确实可用时才清旧版本，避免旧图删了又没换上新图。
                    if (target.isFile && target.length() > 0L) {
                        filesOf(id).filter { it != target }.forEach { stale -> stale.delete() }
                    }
                }
                .onFailure { error ->
                    // 取消不是下载失败，必须原样抛出。
                    if (error is CancellationException) throw error
                    target.delete()
                    allOk = false
                }
        }

        avatars.value = loadCached()
        return allOk
    }

    /**
     * 身份迁移：头像从旧 id 复制到新 id 名下，并推送远端。
     *
     * 版本号取当前时间，等于「换了个新文件名」——界面与图片库都会跟着刷新，
     * 不会出现「换完身份还显示旧图」。
     */
    override suspend fun migrateIdentity(fromId: String, toId: String): Result<Unit> = runCatching {
        if (fromId.isBlank() || toId.isBlank() || fromId == toId) return@runCatching

        val source = latestFileOf(fromId) ?: return@runCatching
        val moved = File(cacheDir, AvatarFiles.versionedName(toId, System.currentTimeMillis()))
        source.copyTo(moved, overwrite = true)
        source.delete()

        if (sessionManager.ensureFreshToken()) {
            val paths = storageConfig.paths()
            runCatching {
                fileDataSource.ensureDirectory(paths.avatarsRoot)
                fileDataSource.upload(moved, paths.avatarOf(toId))
                // 旧 id 的文件已经没人引用，删掉算是收尾；删不掉也不影响使用。
                fileDataSource.delete(paths.avatarOf(fromId))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Logger.w("头像迁移上传失败：${error.message}")
            }
        } else {
            Logger.w("未授权，头像已改到新 id 名下，待授权后自动同步")
        }

        avatars.value = loadCached()
    }.onFailure { error ->
        // 协程取消必须原样抛出。
        if (error is CancellationException) throw error
    }

    /** 某个成员当前的头像文件（最新版本），没有则 null。 */
    private fun latestFileOf(id: String): File? =
        filesOf(id).maxByOrNull { AvatarFiles.parse(it)?.version ?: 0L }

    /** 某个成员的全部有效缓存文件（只认现行命名 `<id>__v<版本>.jpg`）。 */
    private fun filesOf(id: String): List<File> =
        cacheDir.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.length() > 0 && AvatarFiles.parse(file)?.id == id
            }

    /**
     * 冷启动时先从缓存目录回填，避免首帧全是文字头像。
     *
     * 同一个人只保留最新版本，其余旧版本文件直接删掉——不清的话，
     * 每换一次头像就在磁盘上多留一份废文件。不是现行命名的文件
     * （更早版本遗留的 `<id>.jpg`）不算有效缓存，同样清掉。
     */
    private fun loadCached(): Map<String, File> {
        val parsed = cacheDir.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val entry = AvatarFiles.parse(file)
            if (entry == null) {
                file.delete()
                return@mapNotNull null
            }
            if (file.length() == 0L) return@mapNotNull null
            Triple(file, entry.id, entry.version)
        }

        val current = LinkedHashMap<String, File>(parsed.size)
        parsed.groupBy { (_, id, _) -> id }.forEach { (id, files) ->
            val newest = files.maxByOrNull { it.third } ?: return@forEach
            current[id] = newest.first
            files.filter { it.first != newest.first }.forEach { (stale, _, _) -> stale.delete() }
        }
        return current
    }
}
