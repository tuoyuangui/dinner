package com.guolongli.dinner.data.avatar

import android.content.ContentResolver
import android.net.Uri
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.media.ImageTools
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 头像。它比看上去复杂一点，因为头像要跟着记录走：
 *
 * - 自己上传：裁成 512 方图存本地 → 传到 `apps/dinner/avatars/<deviceId>.jpg` → 记住云端路径；
 * - 记录同步时会把「作者的云端头像路径」一起写进 JSON，别人拉到记录就知道去哪取；
 * - 别人的头像不预下载，等这条记录出现在列表里、且本地没有缓存时再补，
 *   避免第一次同步就为了几张头像跑十几趟网盘。
 *
 * 头像失败从不阻塞主流程：拿不到就退回姓氏首字，这是设计稿里本来就有的兜底。
 */
class AvatarRepository(
    private val profile: ProfileStore,
    private val netdisk: () -> NetdiskRepository,
    private val avatarDir: String = BaiduConfig.AVATAR_DIR,
) {

    fun myId(): String = profile.memberId

    /** 补头像的互斥锁：两路并发补同一个人的头像没有意义，只会互相打断 */
    private val mutex = Mutex()

    fun localPathOf(authorId: String): String? = profile.avatarPathOf(authorId)

    fun remoteOf(authorId: String): String? = profile.avatarRemoteOf(authorId)

    /** 相册里选一张，居中裁方、压到 512，返回本地路径 */
    suspend fun importMine(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ImageTools.squareJpeg(resolver, uri, AVATAR_SIZE)
                ?: return@runCatching null
            profile.saveMyAvatar(bytes)
        }.getOrNull()
    }

    /**
     * 把自己的头像传到网盘。没授权、没换过头像都会直接返回上一次的云端路径。
     * 上传成功返回远端路径，失败返回 null（下次同步还会再试，因为本地文件还在）。
     */
    suspend fun uploadMine(): String? = withContext(Dispatchers.IO) {
        // 用 memberId 而不是 deviceId：记录里的作者 id 是 memberId，
        // 头像远端路径按作者 id 命名才能被别人的列表对上号
        val me = profile.memberId
        val file = profile.avatarFile(me)
        if (!file.isFile || file.length() == 0L) return@withContext null
        runCatching {
            val repo = netdisk()
            repo.ensureDir(avatarDir)
            val remote = repo.uploadFile(file, remoteName(me), avatarDir)
            profile.rememberAvatarRemote(me, remote)
            remote
        }.getOrNull()
    }

    /** 把某个成员的头像从网盘取到本地；已经有就不再下载 */
    suspend fun fetch(authorId: String): String? = withContext(Dispatchers.IO) {
        if (authorId.isBlank()) return@withContext null
        profile.avatarPathOf(authorId)?.let { return@withContext it }
        val remote = profile.avatarRemoteOf(authorId) ?: return@withContext null
        runCatching {
            // 用新的版本路径接文件：换过头像的成员，旧图不会被缓存命中
            val target = profile.newAvatarFile(authorId)
            target.parentFile?.mkdirs()
            netdisk().download(remote, target)
            target.takeIf { it.isFile && it.length() > 0 }?.absolutePath
        }.getOrNull()
    }

    /**
     * 补齐「知道地址但没有本地文件」的头像，一次最多补几张。
     * 由自动同步跑完之后调用，失败静默——头像不值得为它报错。
     *
     * 加锁的原因：同步收尾与群组页各会触发一次补头像，两路并发时
     * 同一个人的头像会被下载两遍、互相覆盖，日志里看到的是被取消的请求。
     * 补头像本身幂等，串起来没有任何损失。
     */
    suspend fun syncMissing(limit: Int = 4): Int = mutex.withLock {
        val candidates = profile.avatarRemotes().keys
            .filter { it != profile.deviceId && profile.avatarPathOf(it) == null }
            .take(limit)
        var fetched = 0
        candidates.forEach { authorId ->
            if (fetch(authorId) != null) fetched++
        }
        fetched
    }

    /** 最近的记录里出现过、但还没拿到云端地址的作者：从记录文档里补 */
    fun rememberFromRecord(authorId: String, remotePath: String?) {
        if (remotePath.isNullOrBlank()) return
        profile.rememberAvatarRemote(authorId, remotePath)
    }

    private fun remoteName(authorId: String): String = "$authorId.jpg"

    private companion object {
        const val AVATAR_SIZE = 512
    }
}
