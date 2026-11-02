package com.guolongli.dinner.data.group

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.domain.model.GroupConfig
import com.guolongli.dinner.domain.repository.GroupConfigRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `config/passwd.json` 的读写，也就是群组唯一的门禁。
 *
 * 三条贯穿全类的规矩：
 *
 * 1. **先读云端**。管理员登录、入群校验、换入群密码，每次都先 `read()` 云端那份，
 *    不拿本机缓存或界面上的旧值当裁决依据。别人刚换了密码，本机缓存一定是过期的。
 * 2. **写后回读**。百度网盘上传是分片 + create 两步，任何一步失败都可能留下
 *    「本地以为写成功、云端还是旧值」的状态。口令是一次承诺，必须确认云端那份
 *    真的是这次写进去的内容，不一致就抛错。
 * 3. **明文**。这是产品要求：文件在共享网盘目录里，任何成员都能读，
 *    哈希不会带来真实的边界，只会让人以为门禁比实际更强。
 */
@Singleton
class GroupConfigRepositoryImpl @Inject constructor(
    private val cloud: CloudTextFile,
    private val storageConfig: StorageConfigRepository,
) : GroupConfigRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun pathsOf(groupId: String): NetdiskPaths =
        storageConfig.paths().copy(groupId = groupId)

    override suspend fun read(groupId: String): GroupConfig? =
        readDetailed(groupId).getOrThrow()

    /**
     * 读 passwd.json，并保留「为什么读不到」的真实原因。
     *
     * 三种结果必须区分清楚：
     * - `Result.success(null)`：目录里确实没有这个文件（真没建组 / 被删了）；
     * - `Result.failure(...)`：有文件但读不下来（dlink 拿不到、下载失败、内容坏了），带原因；
     * - `Result.success(config)`：读到了。
     *
     * 把「读失败」压成 null 的后果是：用户明明在网盘里看得见这份文件，
     * 界面却只说「群组没创建」，完全无从下手。
     */
    private suspend fun readDetailed(groupId: String): Result<GroupConfig?> {
        if (groupId.isBlank()) {
            return Result.failure(DinnerException.Validation("群组 id 为空"))
        }
        val paths = pathsOf(groupId)
        return runCatching {
            // 读不到抛错、读不到文件返回 null —— 两种结果必须由 CloudTextFile 分清。
            val raw = cloud.read(paths.passwdFile) ?: return Result.success(null)
            json.decodeFromString(PasswdDto.serializer(), raw).toDomain(groupId)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    override suspend fun write(config: GroupConfig): Result<Unit> = runCatching {
        require(config.groupId.isNotBlank()) { "群组 id 不能为空" }
        require(config.adminPassword.isNotBlank()) { "管理员口令不能为空" }
        require(config.joinPassword.isNotBlank()) { "入群密码不能为空" }
        val paths = pathsOf(config.groupId)
        val payload = json.encodeToString(
            PasswdDto.serializer(),
            config.copy(updatedAt = System.currentTimeMillis()).toDto(),
        )
        cloud.write(paths.passwdFile, payload)

        // 写后回读：确认云端那份真的是这次写进去的内容。
        //
        // 这里不能只比 admin_phone 之类的「元信息」——口令本身才是这份文件的意义，
        // 比错的字段会让「密码没写进去」被当成写入成功。
        val verify = readDetailed(config.groupId).getOrElse { error ->
            throw DinnerException.Local(
                "passwd.json 上传后读不回来，不能确认云端已写入：${error.message}。请重试",
                error
            )
        } ?: throw DinnerException.Local(
            "passwd.json 上传后云端找不到这个文件，写入未生效。请重试"
        )
        if (!verify.matchesAdminPassword(config.adminPassword) ||
            !verify.matchesJoinPassword(config.joinPassword) ||
            (config.adminId != null && verify.adminId != config.adminId)
        ) {
            throw DinnerException.Local("云端 passwd.json 与本次写入不一致，写入没有生效。请重试")
        }
        Logger.i("passwd.json 已保存并回读确认：${config.groupId}")
    }.onFailure { error ->
        // 协程取消不是「写失败」，必须原样抛出，不得折进 Result 里当失败原因。
        if (error is CancellationException) throw error
    }

    override suspend fun verifyJoinPassword(groupId: String, input: String): Result<Unit> =
        runCatching {
            // 每次先读云端：管理员刚换过密码时，任何本地/内存里的旧值都必须作废。
            val config = readDetailed(groupId).getOrThrow()
                ?: throw DinnerException.Validation(
                    "云端还没有这个群组的 passwd.json，请让管理员先创建群组"
                )
            if (!config.matchesJoinPassword(input)) {
                throw DinnerException.Validation("入群密码不对")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }

    override suspend fun rotateJoinPassword(groupId: String): Result<String> = runCatching {
        // 1. 先读云端最新口令：不做这一步会拿本机缓存当基线，
        //    管理员在另一台设备改过的值会被直接覆盖掉。
        val current = readDetailed(groupId).getOrThrow()
            ?: throw DinnerException.Validation("云端还没有这个群组的 passwd.json")
        require(current.hasAdminPassword) { "云端 passwd.json 缺少管理员口令，无法换码" }

        // 2. 本地生成新密码并推云端。
        val generated = GroupConfig.randomPassword(GroupConfig.JOIN_PASSWORD_LENGTH)
        write(current.copy(joinPassword = generated)).getOrThrow()

        // 3. 再从云端 read() 回读刷新。界面必须显示这个值，不得显示本地生成的那个：
        //    两者一致才说明真的写进去了；不一致时 [write] 已经抛错，不会走到这里。
        val refreshed = readDetailed(groupId).getOrThrow()
            ?: throw DinnerException.Local("换了入群密码后云端文件消失了，请重试")
        if (refreshed.joinPassword.isBlank()) {
            throw DinnerException.Local("换了入群密码后云端读回来是空的，写入未生效。请重试")
        }
        Logger.i("入群密码已更新并回读确认：$groupId")
        refreshed.joinPassword
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun updateAdminPassword(
        groupId: String,
        newPassword: String,
    ): Result<GroupConfig> = runCatching {
        require(newPassword.length in 1..GroupConfig.MAX_PASSWORD_LENGTH) {
            "管理员口令长度必须在 1..${GroupConfig.MAX_PASSWORD_LENGTH}"
        }
        val current = readDetailed(groupId).getOrThrow()
            ?: throw DinnerException.Validation("云端还没有这个群组的 passwd.json")
        write(current.copy(adminPassword = newPassword)).getOrThrow()
        // 写后回读，返回的就是云端那份，界面据此刷新，不用本地值糊弄。
        readDetailed(groupId).getOrThrow()
            ?: throw DinnerException.Local("管理员口令写入后云端文件消失了，请重试")
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }
}

/**
 * passwd.json 的落盘结构。
 *
 * 字段名与 AGENTS.md 里描述的一致：`admin_password` / `join_password` 明文。
 * `schema` 保留给以后的显式迁移（快照与这里都只允许追加字段）。
 */
@Serializable
data class PasswdDto(
    @SerialName("schema") val schema: Int = SCHEMA,
    @SerialName("group_id") val groupId: String = "",
    @SerialName("admin_phone") val adminPhone: String = "",
    @SerialName("admin_password") val adminPassword: String = "",
    @SerialName("join_password") val joinPassword: String = "",
    @SerialName("admin_id") val adminId: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
) {
    companion object {
        const val SCHEMA = 1
    }
}

/**
 * 落盘结构 -> 领域模型。
 *
 * **群组身份以目录名为准，不信文件里的 `group_id` / `admin_phone`。**
 * 踩过的坑：`group_17380203573/config/passwd.json` 里写着 `group_id` 尾号 3576，
 * 旧实现采信文件值，于是写路径拼到不存在的目录，写失败、读回也失败。
 * 目录名是唯一真实身份：它决定文件实际躺在哪里。
 *
 * 但口令没有第二份来源，只能信文件里的值——所以 `admin_password` / `join_password`
 * 一律原样带回来，空值由 [GroupConfig] 的 `matches*` 判为「永不匹配」。
 */
fun PasswdDto.toDomain(fallbackGroupId: String): GroupConfig {
    val phoneFromDir = PhoneIdentity.adminPhoneOf(fallbackGroupId)
    if (this.groupId.isNotBlank() && this.groupId != fallbackGroupId) {
        Logger.w(
            "passwd.json 里的 group_id（${this.groupId}）与目录名（$fallbackGroupId）不一致，按目录名为准"
        )
    }
    if (this.adminPhone.isNotBlank() && phoneFromDir != null && this.adminPhone != phoneFromDir) {
        Logger.w(
            "passwd.json 里的 admin_phone（${this.adminPhone}）与目录名（$fallbackGroupId）不一致，按目录名为准"
        )
    }
    return GroupConfig(
        groupId = fallbackGroupId,
        adminPhone = phoneFromDir ?: this.adminPhone,
        adminPassword = this.adminPassword,
        joinPassword = this.joinPassword,
        adminId = this.adminId?.takeIf { it.isNotBlank() },
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
}

fun GroupConfig.toDto(): PasswdDto = PasswdDto(
    groupId = groupId,
    adminPhone = adminPhone,
    adminPassword = adminPassword,
    joinPassword = joinPassword,
    adminId = adminId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
