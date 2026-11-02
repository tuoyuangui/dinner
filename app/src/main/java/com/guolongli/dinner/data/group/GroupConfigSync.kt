package com.guolongli.dinner.data.group

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 一份群组配置：群名、动态入群密码、管理员口令、管理员是谁、建组者号码。
 *
 * [adminId] 是「转让管理权」的落点：谁在这里，谁就是组内管理员。
 * [adminPhone] 只用来展示「这个群是谁建的」，不参与任何鉴权。
 * 早期版本的 passwd.json 没有这两个字段，读不到时沿用本机值。
 */
data class GroupConfig(
    val groupName: String,
    val joinCode: String,
    val adminCode: String,
    val updatedAt: Long,
    val updatedBy: String,
    val adminId: String? = null,
    val adminPhone: String = "",
) {
    /** 口令为空一律视为不可用，调用方必须拒绝放行，不得拿空串当通配 */
    fun matchesAdmin(input: String): Boolean =
        adminCode.isNotBlank() && Passwords.equals(adminCode, input.trim())

    fun matchesJoin(input: String): Boolean =
        joinCode.isNotBlank() && Passwords.equals(joinCode, input.trim())
}

/**
 * 群组配置的云端同步（`/apps/dinner/config/passwd.json`）。
 *
 * 为什么这个文件必须上网盘：入群密码和管理员口令是**整个群共用的一个约定**。
 * 只存在本机的话，管理员在自己手机上改了密码，别人手里那台永远还是旧的，
 * 表现为「密码明明改了却说不对」——这类问题在没有客服的家庭应用里几乎无解。
 *
 * 三条规矩，改这里之前先读：
 *
 * 1. **按时间戳让新的赢**。不做双向合并，也不猜。本机从没改过（[ProfileStore.groupUpdatedAt] 为 0）
 *    时只读不写，避免第二台设备一装就把管理员设好的密码顶掉。
 * 2. **写后回读**。上传是「预上传 + 分片 + 合并」三步，任何一步失败都可能留下
 *    「本地以为写成功、云端还是旧值」。口令是一次承诺，必须确认云端那份真是这次写的内容。
 * 3. **明文**。产品要求如此：文件在共享网盘目录里，任何成员都能读，
 *    加哈希不会带来真实边界，只会让人误以为门禁比实际更强。
 */
class GroupConfigSync(
    private val profile: ProfileStore,
    private val netdisk: () -> NetdiskRepository,
    private val remotePath: String = BaiduConfig.GROUP_CONFIG_FILE,
) {

    private val remoteDir: String = remotePath.substringBeforeLast('/')
    private val remoteName: String = remotePath.substringAfterLast('/')

    /** 本机这份配置 */
    fun local(): GroupConfig = GroupConfig(
        groupName = profile.groupName,
        joinCode = profile.joinCode,
        adminCode = profile.adminCode,
        updatedAt = profile.groupUpdatedAt,
        updatedBy = profile.memberId,
        adminId = profile.adminId,
        adminPhone = profile.adminPhone,
    )

    /**
     * 云端那份配置。
     *
     * 返回 null 只有一种含义：目录里确实没有这个文件（第一次用）。
     * 读不下来（没授权 / 下载失败 / 内容坏了）一律返回 null 并放弃本轮，
     * **绝不当成「云端是空的」去覆盖**——那会把别的设备设好的密码冲掉。
     */
    suspend fun pull(): GroupConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val repo = netdisk()
            val entry = repo.list(remoteDir).firstOrNull { it.path == remotePath && !it.isDir }
                ?: return@runCatching null
            val temp = File.createTempFile("group-", ".json")
            try {
                repo.download(entry.path, temp)
                if (!temp.isFile || temp.length() == 0L) return@runCatching null
                parse(temp.readText())
            } finally {
                temp.delete()
            }
        }.getOrNull()
    }

    /**
     * 把本机配置推到云端，成功后回读确认。
     *
     * @return true 表示云端确认已经是这份内容
     */
    suspend fun push(): Boolean = withContext(Dispatchers.IO) {
        val config = local()
        val payload = toJson(config)
        runCatching {
            val repo = netdisk()
            repo.ensureDirectories(remoteDir)
            val temp = File.createTempFile("group-", ".json")
            try {
                temp.writeText(payload)
                repo.uploadFile(temp, remoteName, remoteDir)
            } finally {
                temp.delete()
            }
            // 写后回读：只有云端回读到的口令与本次一致才算写成功。
            // 比对不能只看 updatedAt —— 时间是元信息，口令才是这份文件的意义。
            // 这里用 [sameAsWritten] 而不是 Passwords.equals：后者把「两边都空」
            // 判为不匹配（防止空口令放行），但写后校验要比的是「写进去的东西回来了没」，
            // 两边都空确实是回来了。
            val verify = pull() ?: return@runCatching false
            sameAsWritten(verify.joinCode, config.joinCode) &&
                sameAsWritten(verify.adminCode, config.adminCode) &&
                verify.groupName == config.groupName &&
                (verify.adminId ?: config.adminId) == config.adminId
        }.getOrDefault(false)
    }

    /** 写后回读比对：允许两边都为空（本来就没设），只要不是「一边有一边没有」 */
    private fun sameAsWritten(remote: String, local: String): Boolean =
        if (remote.isEmpty() && local.isEmpty()) true else Passwords.equals(remote, local)

    /**
     * 一轮同步：谁新用谁。
     *
     * 返回同步后的配置（可能来自云端，也可能就是本机这份）；云端没有文件时
     * 顺手把本机这份推上去，让第二台设备第一次同步就能拿到正确的入群密码。
     */
    suspend fun sync(): GroupConfig? = withContext(Dispatchers.IO) {
        val mine = local()
        val remote = pull()
        if (remote == null) {
            // 云端还没有：只有本机改过才值得写一份，否则等于用默认值占坑
            if (mine.updatedAt > 0L) push()
            return@withContext null
        }
        when {
            remote.updatedAt > mine.updatedAt -> {
                profile.applyGroupConfig(
                    groupName = remote.groupName,
                    joinCode = remote.joinCode,
                    adminCode = remote.adminCode,
                    updatedAt = remote.updatedAt,
                    adminId = remote.adminId,
                    adminPhone = remote.adminPhone,
                )
                remote
            }
            mine.updatedAt > remote.updatedAt -> {
                push()
                mine
            }
            else -> remote
        }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 落盘结构对齐 README 的 `config/passwd.json`：
     * `admin_password` / `join_password` 是两个约定键，改名字等于换一套门禁。
     * 群名与管理员落在同一份文件里——它们本来就是同一次「群组设置」的产物，
     * 分两个文件只会多一次可能不一致的写入。
     */
    private fun toJson(config: GroupConfig): String = JSONObject()
        .put("schema", SCHEMA)
        .put("admin_password", config.adminCode)
        .put("join_password", config.joinCode)
        .put("group_name", config.groupName)
        .put("admin_id", config.adminId ?: JSONObject.NULL)
        .put("admin_phone", config.adminPhone)
        .put("updated_at", config.updatedAt)
        .put("updated_by", config.updatedBy)
        .toString()

    /**
     * 读。两代键名都认：
     * - 现行 `admin_password` / `join_password`；
     * - 早期版本写的 `adminCode` / `joinCode`——还有设备在跑旧版本，
     *   读到就当成同一份口令，不能因为改过名就把人家关在门外。
     */
    private fun parse(text: String): GroupConfig? = runCatching {
        val json = JSONObject(text)
        val joinCode = json.optString("join_password").ifBlank { json.optString("joinCode") }
        // 没有任何口令的配置没有意义：入群靠它，删掉就是一份空文件
        if (joinCode.isBlank()) return@runCatching null
        GroupConfig(
            groupName = json.optString("group_name")
                .ifBlank { json.optString("groupName") }
                .ifBlank { profile.groupName },
            joinCode = joinCode,
            // 老版本 / 手工改过的文件可能没有管理员口令：沿用本机默认值，不要写空
            adminCode = json.optString("admin_password")
                .ifBlank { json.optString("adminCode") }
                .ifBlank { profile.adminCode },
            updatedAt = json.optLong("updated_at", json.optLong("updatedAt")),
            updatedBy = json.optString("updated_by").ifBlank { json.optString("updatedBy") },
            adminId = json.optString("admin_id")
                .ifBlank { json.optString("adminId") }
                .ifBlank { null },
            adminPhone = json.optString("admin_phone")
                .ifBlank { json.optString("adminPhone") }
                .ifBlank { profile.adminPhone },
        )
    }.getOrNull()

    private companion object {
        const val SCHEMA = 1
    }
}
