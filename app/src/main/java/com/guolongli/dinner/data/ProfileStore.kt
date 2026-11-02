package com.guolongli.dinner.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 本机这个人是谁。id 稳定不变，用来判断「这条是不是我记的」。
 * avatarPath 为空时界面回落到姓氏首字头像。
 */
data class LocalUser(
    val id: String,
    val name: String,
    val avatarPath: String? = null,
) {
    val initial: String get() = name.trim().take(1).ifBlank { "我" }
}

/** 名单里的一位成员。id 是手机号（已归一化），未绑定时是设备 id */
data class MemberRecord(
    val id: String,
    val name: String,
    val avatarRemote: String? = null,
    val joinedAt: Long = 0L,
)

/**
 * 身份与头像都存本地：设备首次启动生成一个 UUID，名字默认「我」，可在「我的」里改。
 * 换手机等于换一个 id，这是刻意的——网盘上没有账号体系，身份只能靠本地声明。
 *
 * 头像除了本机文件，还记一份「云端的相对位置」：
 * 别人拉到我的记录时能顺着这个位置把我的头像下载回去，
 * 所以头像不是纯本地功能，它得跟着记录一起在网盘里走。
 */
class ProfileStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val avatarDir = File(app.filesDir, "avatars")

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var displayName: String
        get() = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { DEFAULT_NAME }
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim()).apply()
            bumpIdentity()
        }

    /**
     * 身份版本号：昵称或头像变一次就 +1。
     *
     * 时间线这类列表是从数据库 Flow 推出来的——只改昵称不动库，Flow 不会重发，
     * 界面就一直挂着旧名字。列表层 combine 这个版本号，改完立刻全量重映射。
     */
    val identityTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private fun bumpIdentity() {
        identityTick.value = identityTick.value + 1
    }

    /** 昵称是否被用户显式设过（新手引导第 1 步「更新昵称」的完成判据） */
    val hasCustomName: Boolean
        get() = prefs.getString(KEY_NAME, null).orEmpty().isNotBlank()

    /**
     * 新手引导是否已经走完一遍。
     *
     * 走完就永久记住：以后不再自动判断、也不再自动弹卡——
     * 引导的意义就是「第一次带路」，天天提示就成了打扰。
     * 想再看内容去「我的 → 新手引导」，那里是常驻入口。
     */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()
            bumpIdentity()
        }

    // ------------------------------------------------------------------ 身份

    /**
     * 本机这个人的手机号（已归一化）。空串 = 还没绑定。
     *
     * 绑定之后成员 id 就从设备 id 换成号码：[memberId]。
     * 这不是「多存一个字段」，而是身份从「这台设备」变成「这个人」——
     * 换手机、重装应用之后，记录、名单、头像都还能对上。
     */
    var phone: String
        get() = prefs.getString(KEY_PHONE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PHONE, value.trim()).apply()

    /** 成员 id：绑了手机号就是号码，没绑就是设备 id */
    val memberId: String get() = phone.ifBlank { deviceId }

    /** 群组 id（`group_<管理员手机号>`）。空串表示还没入群 */
    var groupId: String
        get() = prefs.getString(KEY_GROUP_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROUP_ID, value.trim()).apply()

    /** 建组者 / 管理员的手机号：只用来定位群组与展示，不参与鉴权 */
    var adminPhone: String
        get() = prefs.getString(KEY_ADMIN_PHONE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ADMIN_PHONE, value.trim()).apply()

    /** 是否已经加入某个群组。没入群时所有云端目录仍可用，只是名单为空 */
    val isPaired: Boolean get() = groupId.isNotBlank()

    /**
     * 成员名单。存在本机一份，云端 `config/members.json` 一份。
     *
     * 为什么不从记录里反推：一个人入群之后可能好几天没记东西，
     * 「只看记录里出现过谁」会让他在名单里消失，管理员也就无法把管理权转给他。
     */
    fun members(): List<MemberRecord> {
        val raw = prefs.getString(KEY_MEMBERS, null) ?: return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    MemberRecord(
                        id = id,
                        name = item.optString("name").trim(),
                        avatarRemote = item.optString("avatar").ifBlank { null },
                        joinedAt = item.optLong("joinedAt"),
                    ),
                )
            }
        }
    }

    fun saveMembers(list: List<MemberRecord>) {
        val array = org.json.JSONArray()
        list.forEach { member ->
            array.put(
                JSONObject()
                    .put("id", member.id)
                    .put("name", member.name)
                    .put("avatar", member.avatarRemote ?: JSONObject.NULL)
                    .put("joinedAt", member.joinedAt),
            )
        }
        prefs.edit().putString(KEY_MEMBERS, array.toString()).apply()
        bumpIdentity()
    }

    /** 名单里加一个（同 id 覆盖），返回新的名单 */
    fun upsertMember(member: MemberRecord): List<MemberRecord> {
        val current = members().filterNot { it.id == member.id } + member
        val sorted = current.sortedBy { if (it.joinedAt > 0) it.joinedAt else Long.MAX_VALUE }
        saveMembers(sorted)
        return sorted
    }

    fun memberName(id: String): String =
        members().firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
            ?: if (id == memberId) displayName else "成员"

    /**
     * 绑定手机号：把名单里的旧身份（设备 id）并到新身份（号码）上。
     *
     * 只改名单与本地 id；记录作者归属的迁移由 `GroupService` 在数据库里做，
     * 因为那需要事务，不适合放在偏好存储里。
     */
    fun bindPhone(normalizedPhone: String, now: Long = System.currentTimeMillis()) {
        val previous = memberId
        phone = normalizedPhone
        if (adminId.isBlank() || adminId == deviceId || adminId == previous) adminId = normalizedPhone
        val list = members().filterNot { it.id == normalizedPhone }
        val existing = list.firstOrNull { it.id == previous }
        upsertMember(
            MemberRecord(
                id = normalizedPhone,
                name = existing?.name?.takeIf { it.isNotBlank() } ?: displayName,
                avatarRemote = existing?.avatarRemote,
                joinedAt = existing?.joinedAt?.takeIf { it > 0 } ?: now,
            ),
        )
        if (previous != normalizedPhone) {
            saveMembers(members().filterNot { it.id == previous })
            migrateAvatarKeys(previous, normalizedPhone)
        }
    }

    /**
     * 换成员 id（绑手机号）时把头像也迁过去：
     * 本地文件、版本号、云端路径三样都得跟着改键，
     * 否则头像还在磁盘上，按新 id 却找不回来——界面退回首字，像是头像丢了。
     */
    private fun migrateAvatarKeys(from: String, to: String) {
        val version = avatarVersion(from)
        val oldFile = File(avatarDir, if (version > 0L) "$from-v$version.jpg" else "$from.jpg")
        if (oldFile.isFile) {
            val target = File(avatarDir, if (version > 0L) "$to-v$version.jpg" else "$to.jpg")
            if (oldFile.renameTo(target) || (oldFile.copyTo(target, overwrite = true).isFile)) {
                oldFile.delete()
            }
        }
        val editor = prefs.edit()
        if (version > 0L) {
            editor.putLong(KEY_AVATAR_VERSION_PREFIX + to, version)
            editor.remove(KEY_AVATAR_VERSION_PREFIX + from)
        }
        val remotes = avatarRemotes()
        remotes.remove(from)?.let { remotes[to] = it }
        val json = org.json.JSONObject()
        remotes.forEach { (key, value) -> json.put(key, value) }
        editor.putString(KEY_AVATAR_REMOTES, json.toString())
        editor.apply()
        bumpIdentity()
    }

    fun user(): LocalUser = LocalUser(memberId, displayName, avatarPathOf(memberId))

    // ------------------------------------------------------------------ 头像

    /**
     * 某个成员头像的本地文件位置。
     *
     * 文件名带版本号（`<id>-v<时间戳>.jpg`），不是固定的 `<id>.jpg`。
     * 原因很实际：头像换过之后路径如果不变，图片缓存按路径命中旧图、
     * 界面状态也认为「没有变化」，于是时间线上还是旧头像。
     * 换名等于换路径，缓存失效与界面刷新两件事一起解决。
     */
    fun avatarFile(authorId: String): File {
        val version = avatarVersion(authorId)
        return File(avatarDir, if (version > 0L) "$authorId-v$version.jpg" else "$authorId.jpg")
    }

    /** 有本地文件才算「有头像」，否则界面用首字兜底 */
    fun avatarPathOf(authorId: String): String? =
        avatarFile(authorId).takeIf { it.isFile && it.length() > 0 }?.absolutePath

    /**
     * 存我自己的头像（已裁成方图、已压缩的字节）。
     *
     * 注意按 [memberId] 而不是 deviceId 落盘：记录里的作者 id 是 memberId，
     * 两边不一致的话，绑完手机号时间线上就再也找不到自己的头像了。
     */
    fun saveMyAvatar(bytes: ByteArray): String {
        val file = newAvatarFile(memberId)
        file.writeBytes(bytes)
        prefs.edit().putLong(KEY_AVATAR_AT, System.currentTimeMillis()).apply()
        return file.absolutePath
    }

    /**
     * 给某个成员分配一个新的头像文件，并清掉同一成员的历史版本。
     *
     * 只改版本号不碰磁盘内容：调用方拿到这个 [File] 自己去写（本地上传是 writeBytes，
     * 远端拉取是 download 直接落盘）。写到一半失败时文件不存在 =
     * [avatarPathOf] 返回 null = 界面退回首字，下次同步会再试，不会留下半张图。
     */
    fun newAvatarFile(authorId: String, version: Long = System.currentTimeMillis()): File {
        avatarDir.mkdirs()
        val file = File(avatarDir, "$authorId-v$version.jpg")
        prefs.edit().putLong(KEY_AVATAR_VERSION_PREFIX + authorId, version).apply()
        // 顺手清掉这个成员的历史头像，避免私有目录越攒越大
        avatarDir.listFiles()?.forEach { candidate ->
            val name = candidate.name
            val mine = name == "$authorId.jpg" || name.startsWith("$authorId-v")
            if (mine && candidate.name != file.name) candidate.delete()
        }
        bumpIdentity()
        return file
    }

    private fun avatarVersion(authorId: String): Long =
        prefs.getLong(KEY_AVATAR_VERSION_PREFIX + authorId, 0L)

    /** 头像最后一次修改时间：上传时拿它比较，避免重复传同一张 */
    var avatarUpdatedAt: Long
        get() = prefs.getLong(KEY_AVATAR_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_AVATAR_AT, value).apply()

    /** 头像在网盘上的路径（/apps/dinner/avatars/<id>.jpg），没传过就是 null */
    fun avatarRemoteOf(authorId: String): String? = avatarRemotes()[authorId]

    fun rememberAvatarRemote(authorId: String, remotePath: String?) {
        if (authorId.isBlank()) return
        val map = avatarRemotes()
        if (remotePath.isNullOrBlank()) {
            if (map.remove(authorId) == null) return
        } else {
            if (map[authorId] == remotePath) return
            map[authorId] = remotePath
        }
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_AVATAR_REMOTES, json.toString()).apply()
    }

    /** 所有已知的「成员 → 云端头像」，同步时用来补齐别人没下过的头像 */
    fun avatarRemotes(): MutableMap<String, String> {
        val raw = prefs.getString(KEY_AVATAR_REMOTES, null) ?: return mutableMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val result = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.optString(key)
            if (value.isNotBlank()) result[key] = value
        }
        return result
    }

    // ------------------------------------------------------------------ 群组

    var groupName: String
        get() = prefs.getString(KEY_GROUP_NAME, null).orEmpty().ifBlank { DEFAULT_GROUP }
        set(value) = prefs.edit().putString(KEY_GROUP_NAME, value.trim()).apply()

    /** 动态入群密码：首次启动随机生成一次，之后由管理员改 */
    var joinCode: String
        get() {
            prefs.getString(KEY_JOIN_CODE, null)?.let { return it }
            val created = randomCode()
            prefs.edit().putString(KEY_JOIN_CODE, created).apply()
            return created
        }
        set(value) = prefs.edit().putString(KEY_JOIN_CODE, value.trim()).apply()

    /** 管理员口令：初次是下面这个固定值，解锁后可改 */
    var adminCode: String
        get() = prefs.getString(KEY_ADMIN_CODE, null).orEmpty().ifBlank { DEFAULT_ADMIN }
        set(value) = prefs.edit().putString(KEY_ADMIN_CODE, value.trim()).apply()

    /**
     * 群组配置最后一次改动的时间。
     *
     * 网盘上那份 passwd.json 与本机这份谁新，就按这个时间比。
     * 0 表示「本机从没主动改过」，此时不去覆盖云端——否则第二台设备一装
     * 就会把管理员在别的机器上设好的入群密码顶掉。
     */
    var groupUpdatedAt: Long
        get() = prefs.getLong(KEY_GROUP_UPDATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_GROUP_UPDATED_AT, value).apply()

    /**
     * 管理员是谁。默认是这个人自己（未绑定时是设备 id）；转让时改成对方成员 id，
     * 随 passwd.json 一起同步。
     */
    var adminId: String
        get() = prefs.getString(KEY_ADMIN_ID, null).orEmpty().ifBlank { memberId }
        set(value) = prefs.edit().putString(KEY_ADMIN_ID, value.trim()).apply()

    /** 把云端那份配置写到本机（云端更新时用） */
    fun applyGroupConfig(
        groupName: String,
        joinCode: String,
        adminCode: String,
        updatedAt: Long,
        adminId: String? = null,
        adminPhone: String? = null,
    ) {
        val editor = prefs.edit()
            .putString(KEY_GROUP_NAME, groupName.trim())
            .putString(KEY_JOIN_CODE, joinCode.trim())
            .putString(KEY_ADMIN_CODE, adminCode.trim())
            .putLong(KEY_GROUP_UPDATED_AT, updatedAt)
        if (!adminId.isNullOrBlank()) editor.putString(KEY_ADMIN_ID, adminId.trim())
        if (!adminPhone.isNullOrBlank()) editor.putString(KEY_ADMIN_PHONE, adminPhone.trim())
        editor.apply()
    }

    fun randomCode(): String {
        val alphabet = "ACDEFGHJKLMNPQRSTUVWXY3456789"
        val raw = (1..6).map { alphabet.random() }.joinToString("")
        return "${raw.take(3)}-${raw.drop(3)}"
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_NAME = "display_name"
        const val KEY_PHONE = "phone"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_ADMIN_PHONE = "admin_phone"
        const val KEY_MEMBERS = "members"
        const val KEY_AVATAR_AT = "avatar_updated_at"
        const val KEY_AVATAR_VERSION_PREFIX = "avatar_version_"
        const val KEY_AVATAR_REMOTES = "avatar_remotes"
        const val KEY_GROUP_NAME = "group_name"
        const val KEY_JOIN_CODE = "join_code"
        const val KEY_ADMIN_CODE = "admin_code"
        const val KEY_ADMIN_ID = "admin_id"
        const val KEY_GROUP_UPDATED_AT = "group_updated_at"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        const val DEFAULT_NAME = "我"
        const val DEFAULT_GROUP = "我们的家"
        const val DEFAULT_ADMIN = "dinner"
    }
}
