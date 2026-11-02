package com.guolongli.dinner.domain.repository

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 头像同步。
 *
 * 所有成员共用同一个网盘账号，头像按各自的稳定 authorId 隔离存储到
 * `<根目录>/avatars/<authorId>.jpg`，避免多人互相覆盖。
 *
 * 头像只用于展示，不参与冲突判定，也不需要「所有人同意」才能更新。
 */
interface AvatarRepository {

    /**
     * 按 authorId 索引的本地头像缓存。
     *
     * 时间线卡片按记录的 createdBy 直接查这张表，不再假设「只有我和对方」。
     * 未下载成功的成员不会出现在 map 里，UI 回退为文字头像。
     */
    fun observeAvatars(): Flow<Map<String, File>>

    /** 本机头像；尚未设置时为 null。 */
    fun observeMyAvatar(): Flow<File?>

    /** 把用户挑中的头像压缩并上传，同时更新本地缓存。 */
    suspend fun setMyAvatar(uri: android.net.Uri): Result<Unit>

    /**
     * 同步头像：先上传自己的（若有），再按 [memberIds] 逐个下拉缺失的头像。
     *
     * 串行下载：接口频率有限，一屏十几个头像并发会触发频控。
     */
    suspend fun syncAvatars(memberIds: List<String>): Boolean

    /**
     * 身份 id 变更后，把头像从旧 id 迁到新 id 名下。
     *
     * 绑定手机号会换 authorId，而头像文件名就是 authorId；
     * 不迁的话，用户会看到「刚换完身份，头像没了」。旧 id 的远端文件尽力删掉。
     */
    suspend fun migrateIdentity(fromId: String, toId: String): Result<Unit>
}
