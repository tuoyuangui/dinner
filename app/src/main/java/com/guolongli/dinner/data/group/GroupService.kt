package com.guolongli.dinner.data.group

import androidx.room.withTransaction
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.MemberRecord
import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.identity.PhoneIdentity
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.data.session.AdminSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 建组 / 入群的结果。失败用异常表达，这里只带「云端到底写成功没有」 */
data class GroupResult(
    val phone: String,
    val groupId: String,
    val joinCode: String,
    /** true 表示 passwd.json 已经确认落到云端；false 表示只存在本机，联网后自动补 */
    val synced: Boolean,
)

/**
 * 群组身份、入群流程与成员名单。
 *
 * 三件事必须放在一处，否则它们会互相打架：
 *
 * 1. **身份**：成员 id 就是手机号（[PhoneIdentity]）。绑定手机号要把设备 id
 *    名下的历史记录整体改名，这是事务，只能在数据层做。
 * 2. **门禁**：两份口令都以明文落在云端 `config/passwd.json`。
 *    入群只验 `join_password`；改删别人的内容要 `admin_password`。
 *    两者都先读云端再比对——只在本地比，等于别人换了口令这边永远不知道。
 * 3. **名单**：`config/members.json` 是「谁在这个群里」的唯一真相。
 *    不从记录反推：一个人入群后几天没记东西，不该从名单里消失。
 *
 * 写路径一律「先落本机 → 再推云端」：没有网络也能建组、改密码，
 * 推失败会如实告诉调用方，下一轮同步补上。
 */
class GroupService(
    private val profile: ProfileStore,
    private val config: GroupConfigSync,
    private val netdisk: () -> NetdiskRepository,
    private val db: () -> DinnerDatabase,
    private val admin: AdminSession,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val membersPath: String = BaiduConfig.MEMBERS_FILE

    // ------------------------------------------------------------------ 建组

    /**
     * 建组：手机号 + 管理员口令。
     *
     * 顺序有讲究，换一步就会留下一个修不好的状态：
     * 1. 先把手机号绑上并把历史记录改名（身份先统一，否则名单里会出现两个人）；
     * 2. 再落 groupId / adminPhone / adminId；
     * 3. 生成动态入群密码，连同管理员口令写进 passwd.json；
     * 4. 把自己写进名单并推一次。
     *
     * 第 3 步失败不回滚身份：身份本身没有错，错的只是「云端还没写上」，
     * 联网之后重推即可。回滚反而会把刚改好名的记录留在一个半截状态里。
     */
    suspend fun createGroup(rawPhone: String, rawAdminPassword: String): GroupResult {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw IllegalArgumentException("请填写 11 位手机号")
        val adminPassword = rawAdminPassword.trim()
        if (!Passwords.isValidAdmin(adminPassword)) {
            throw IllegalArgumentException("管理员口令至少 ${Passwords.ADMIN_MIN_LENGTH} 位")
        }

        bindIdentity(phone)
        profile.adminPhone = phone
        profile.adminId = phone
        val joinCode = Passwords.randomJoinCode()
        profile.joinCode = joinCode
        profile.adminCode = adminPassword
        profile.groupName = profile.groupName
        profile.groupUpdatedAt = now()

        val synced = runCatching { config.push() }.getOrDefault(false)
        profile.upsertMember(
            MemberRecord(
                id = phone,
                name = profile.displayName,
                avatarRemote = profile.avatarRemoteOf(phone),
                joinedAt = now(),
            ),
        )
        runCatching { pushMembers() }
        // 刚亲手设过这个口令，按「已验过」处理；但下次冷启动仍要重新输
        admin.markVerified(adminPassword)
        return GroupResult(phone, profile.groupId, joinCode, synced)
    }

    /**
     * 入群：我的手机号 + 管理员手机号 + 入群密码。
     *
     * **只验 join_password**：管理员口令是「改别人东西」那道门，和「能不能进群」无关。
     * 入群时顺手把云端的群名、入群密码、管理员口令抄回本机，
     * 之后离线也能比对（口令本来就是明文，多存一份不增加风险）。
     */
    suspend fun joinGroup(
        rawPhone: String,
        rawAdminPhone: String,
        rawJoinPassword: String,
    ): GroupResult {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw IllegalArgumentException("请填写你的 11 位手机号")
        val adminPhone = PhoneIdentity.normalize(rawAdminPhone)
            ?: throw IllegalArgumentException("请填写管理员的 11 位手机号")
        if (phone == adminPhone) {
            throw IllegalArgumentException("这是管理员本人的号码。想当建组者请用「创建群组」")
        }
        val input = rawJoinPassword.trim()
        if (input.isEmpty()) throw IllegalArgumentException("请填写管理员转告的入群密码")

        val remote = config.pull()
            ?: throw IllegalStateException("这个网盘里还没有群组：先让管理员建组，或确认已授权同一个网盘账号")
        if (!remote.matchesJoin(input)) {
            throw IllegalArgumentException("入群密码不对，向管理员再确认一次")
        }

        bindIdentity(phone)
        profile.groupId = PhoneIdentity.groupIdOf(adminPhone)
        profile.adminPhone = adminPhone
        profile.adminId = remote.adminId?.takeIf { it.isNotBlank() } ?: adminPhone
        profile.applyGroupConfig(
            groupName = remote.groupName,
            joinCode = remote.joinCode,
            adminCode = remote.adminCode,
            updatedAt = remote.updatedAt,
            adminId = profile.adminId,
            adminPhone = adminPhone,
        )

        val members = profile.members().toMutableList()
        if (members.none { it.id == phone } && members.size >= BaiduConfig.MAX_MEMBERS) {
            throw IllegalStateException("群成员已满 ${BaiduConfig.MAX_MEMBERS} 人，请联系管理员")
        }
        members.removeAll { it.id == phone }
        members += MemberRecord(
            id = phone,
            name = profile.displayName,
            avatarRemote = profile.avatarRemoteOf(phone),
            joinedAt = now(),
        )
        profile.saveMembers(members.sortedBy { it.joinedAt })
        val synced = runCatching { pushMembers() }.getOrDefault(false)
        return GroupResult(phone, profile.groupId, remote.joinCode, synced)
    }

    /** 只绑定手机号（不建组）：已有群组的设备补绑号码时用 */
    suspend fun bindPhone(rawPhone: String): String {
        val phone = PhoneIdentity.normalize(rawPhone)
            ?: throw IllegalArgumentException("请填写 11 位手机号")
        bindIdentity(phone)
        runCatching { pushMembers() }
        return phone
    }

    /**
     * 身份迁移：设备 id → 手机号。
     *
     * 记录与评论的作者归属一起改名，否则同一个人会在时间线里分成两份，
     * 「我记的」统计也会少一半。改名与名单更新在同一个事务里提交。
     */
    private suspend fun bindIdentity(phone: String) {
        val previous = profile.memberId
        if (previous == phone) {
            if (profile.phone.isBlank()) profile.bindPhone(phone)
            return
        }
        db().withTransaction {
            db().recordDao().migrateRecordAuthor(previous, phone)
            db().recordDao().migrateCommentAuthor(previous, phone)
        }
        val existing = profile.members().firstOrNull { it.id == previous }
        profile.bindPhone(phone)
        profile.saveMembers(profile.members().filterNot { it.id == previous })
        if (existing != null) {
            profile.upsertMember(existing.copy(id = phone))
        }
    }

    // ------------------------------------------------------------------ 门禁

    /**
     * 校验管理员口令：**先读云端那份，再比对**。
     *
     * 只在本地比是不行的——别人在另一台设备换了口令，这台会一直认为旧口令有效，
     * 那等于口令没有失效机制。云端口令读不到（没网 / 没授权）时退回本机这份，
     * 但下次同步会把状态纠正过来（[refreshAdminState]）。
     */
    suspend fun verifyAdmin(rawPassword: String): Boolean {
        val input = rawPassword.trim()
        if (input.isEmpty()) return false
        val remote = runCatching { config.pull() }.getOrNull()
        val expected = remote?.adminCode?.takeIf { it.isNotBlank() } ?: profile.adminCode
        if (expected.isBlank() || !Passwords.equals(expected, input)) return false
        admin.markVerified(input)
        return true
    }

    /**
     * 回到前台时对齐一次解锁态：云端口令换过就立刻作废。
     *
     * @return true 表示这次把解锁态作废了
     */
    suspend fun refreshAdminState(): Boolean {
        if (!admin.isUnlocked) return false
        val remote = runCatching { config.pull() }.getOrNull() ?: return false
        val changed = admin.invalidateIfChanged(remote.adminCode)
        if (changed) return true
        // 云端是更新的那一份，顺手把本机口令换成它，离线比对才不会一直用旧值
        if (remote.updatedAt > profile.groupUpdatedAt) {
            profile.applyGroupConfig(
                groupName = remote.groupName,
                joinCode = remote.joinCode,
                adminCode = remote.adminCode,
                updatedAt = remote.updatedAt,
                adminId = remote.adminId,
                adminPhone = remote.adminPhone,
            )
        }
        return false
    }

    /** 换入群密码是管理员动作：界面灰着按钮不算权限，服务端也得拦一道 */
    suspend fun updateJoinCode(rawCode: String): Boolean {
        if (!admin.requireAdmin()) throw IllegalStateException("请先输入管理员口令解锁")
        val code = rawCode.trim()
        if (!Passwords.isValidJoin(code)) throw IllegalArgumentException("入群密码至少 4 位")
        profile.joinCode = code
        return pushConfig()
    }

    suspend fun updateAdminCode(rawCode: String): Boolean {
        if (!admin.requireAdmin()) throw IllegalStateException("请先输入管理员口令解锁")
        val code = rawCode.trim()
        if (!Passwords.isValidAdmin(code)) {
            throw IllegalArgumentException("管理员口令至少 ${Passwords.ADMIN_MIN_LENGTH} 位")
        }
        profile.adminCode = code
        // 改完立刻用新口令重置解锁态：刚才是本人操作，理应继续可用
        admin.markVerified(code)
        return pushConfig()
    }

    suspend fun updateGroupName(rawName: String): Boolean {
        if (!admin.requireAdmin()) throw IllegalStateException("请先输入管理员口令解锁")
        val name = rawName.trim()
        if (name.isEmpty()) throw IllegalArgumentException("群名不能为空")
        profile.groupName = name
        return pushConfig()
    }

    /**
     * 转让管理权。必须先解锁——否则任何拿到手机的人都能把群接管走。
     */
    suspend fun transferAdmin(memberId: String): Boolean {
        if (!admin.requireAdmin()) throw IllegalStateException("请先输入管理员口令解锁")
        val target = profile.members().firstOrNull { it.id == memberId }
            ?: throw IllegalArgumentException("名单里没有这位成员")
        profile.adminId = target.id
        profile.groupUpdatedAt = now()
        admin.adoptAdmin(target.id)
        pushMembers()
        return pushConfig()
    }

    /**
     * 换一个新的动态入群密码（管理员操作：加人前重新生成，用完即换）。
     *
     * 「动态」就体现在这里：这串密码不该长期有效——每加完一批人就换一次，
     * 旧密码立刻失效。与手改 [updateJoinCode] 一样要先解锁管理员。
     */
    fun rotateJoinCode(): String {
        if (!admin.requireAdmin()) throw IllegalStateException("请先输入管理员口令解锁")
        val code = Passwords.randomJoinCode()
        profile.joinCode = code
        return code
    }

    private suspend fun pushConfig(): Boolean {
        profile.groupUpdatedAt = now()
        return runCatching { config.push() }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 名单

    private fun membersDir(): String = membersPath.substringBeforeLast('/')
    private fun membersName(): String = membersPath.substringAfterLast('/')

    /** 名单文件在云端的样子 */
    private fun membersToJson(members: List<MemberRecord>): String {
        val array = JSONArray()
        members.forEach { member ->
            array.put(
                JSONObject()
                    .put("id", member.id)
                    .put("name", member.name)
                    .put("avatar", member.avatarRemote ?: JSONObject.NULL)
                    .put("joinedAt", member.joinedAt),
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("admin_id", profile.adminId)
            .put("updated_at", now())
            .put("members", array)
            .toString()
    }

    private fun parseMembers(text: String): List<MemberRecord> = runCatching {
        val array = JSONObject(text).optJSONArray("members") ?: return@runCatching emptyList()
        buildList {
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
    }.getOrDefault(emptyList())

    /** 云端名单 */
    suspend fun pullMembers(): List<MemberRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = netdisk()
            val entry = repo.list(membersDir()).firstOrNull { it.path == membersPath && !it.isDir }
                ?: return@runCatching emptyList()
            val temp = File.createTempFile("members-", ".json")
            try {
                repo.download(entry.path, temp)
                if (!temp.isFile || temp.length() == 0L) return@runCatching emptyList()
                parseMembers(temp.readText())
            } finally {
                temp.delete()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 一轮名单同步：并集 + 上限 + 自己一定在名单里。
     *
     * 并集而不是「远端覆盖本地」：离线期间新加入的人只在本地有一行，
     * 覆盖会把他抹掉，而他本人下次同步又会推上来——名单就这样来回抖。
     */
    suspend fun syncMembers(): List<MemberRecord> = withContext(Dispatchers.IO) {
        val remote = runCatching { pullMembers() }.getOrDefault(emptyList())
        val mine = profile.members()
        val merged = LinkedHashMap<String, MemberRecord>()
        remote.forEach { merged[it.id] = it }
        mine.forEach { local ->
            val existing = merged[local.id]
            merged[local.id] = if (existing == null) {
                local
            } else {
                // 名字与头像以本地为准（本人刚改过），加入时间以更早的为准
                existing.copy(
                    name = local.name.ifBlank { existing.name },
                    avatarRemote = local.avatarRemote ?: existing.avatarRemote,
                    joinedAt = listOf(existing.joinedAt, local.joinedAt).filter { it > 0 }.minOrNull() ?: 0L,
                )
            }
        }
        val me = profile.memberId
        if (profile.isPaired && merged[me] == null) {
            merged[me] = MemberRecord(me, profile.displayName, joinedAt = now())
        }
        // 本人条目以「当前昵称 / 头像」为准：members 快照里存的可能是改名前的旧值，
        // 不覆盖的话刚改的名字会在下一次合并里被旧快照顶回去
        merged[me]?.let { self ->
            merged[me] = self.copy(
                name = profile.displayName.ifBlank { self.name },
                avatarRemote = profile.avatarRemoteOf(me) ?: self.avatarRemote,
            )
        }
        val list = merged.values
            .sortedBy { if (it.joinedAt > 0) it.joinedAt else Long.MAX_VALUE }
            .take(BaiduConfig.MAX_MEMBERS)
        profile.saveMembers(list)
        // 两种情况要推：远端还缺人（本地有、云端没有），
        // 或者谁改了昵称 / 头像——原来只比 id 集合，改名改头像永远推不出去，
        // 别人那边就一直挂着旧名字旧头像。
        val idChanged = remote.map { it.id }.toSet() != list.map { it.id }.toSet()
        val profileChanged = list.any { member ->
            val remoteMember = remote.firstOrNull { it.id == member.id }
            remoteMember != null &&
                (remoteMember.name != member.name || remoteMember.avatarRemote != member.avatarRemote)
        }
        if (idChanged || profileChanged) {
            runCatching { pushMembers() }
        }
        list
    }

    private suspend fun pushMembers(): Boolean = withContext(Dispatchers.IO) {
        if (profile.members().isEmpty()) return@withContext false
        runCatching {
            val repo = netdisk()
            repo.ensureDirectories(membersDir())
            val temp = File.createTempFile("members-", ".json")
            try {
                temp.writeText(membersToJson(profile.members()))
                repo.uploadFile(temp, membersName(), membersDir())
            } finally {
                temp.delete()
            }
            true
        }.getOrDefault(false)
    }
}
