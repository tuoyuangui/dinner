package com.guolongli.dinner.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.Member
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 成员关系持久化（DataStore）。
 *
 * 只存放成员名单与管理员，绝不存放任何 token——
 * token 与身份关系必须分开存储。
 *
 * 名单本身很小（上限 10 人），直接序列化成 JSON 存一个 key，
 * 避免为几个字段开一张表。
 */
private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dinner_pairing"
)

@Singleton
class PairingPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: LocalProfileStore,
) : PairingRepository {

    private object Keys {
        val members = stringPreferencesKey("members_json")
        val boundAt = longPreferencesKey("bound_at")
        val admin = stringPreferencesKey("admin_id")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val memberListSerializer = ListSerializer(Member.serializer())

    override fun observe(): Flow<GroupState> = combine(
        context.pairingDataStore.data,
        profileStore.observeAuthorId(),
    ) { prefs, myId ->
        val members = decode(prefs[Keys.members], myId)
        GroupState(
            members = members,
            myId = myId,
            boundAt = prefs[Keys.boundAt],
            adminId = sanitizeAdmin(prefs[Keys.admin], members),
        )
    }

    override suspend fun current(): GroupState = observe().first()

    override suspend fun upsertMember(member: Member): Result<GroupState> = runCatching {
        val myId = profileStore.observeAuthorId().first()
        val prefs = context.pairingDataStore.data.first()
        val current = decode(prefs[Keys.members], myId)

        val existing = current.firstOrNull { it.id == member.id }
        if (existing == null && current.size >= GroupState.MAX_MEMBERS) {
            throw IllegalStateException("最多 ${GroupState.MAX_MEMBERS} 人，已经满了")
        }

        val updated = if (existing == null) {
            current + member
        } else {
            current.map { if (it.id == member.id) member else it }
        }
        writeMembers(updated)
        stateOf(
            members = updated,
            boundAt = prefs[Keys.boundAt],
            myId = myId,
            admin = sanitizeAdmin(prefs[Keys.admin], updated),
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun removeMember(id: String): Result<GroupState> = runCatching {
        val myId = profileStore.observeAuthorId().first()
        val prefs = context.pairingDataStore.data.first()
        val updated = decode(prefs[Keys.members], myId).filterNot { it.id == id }
        writeMembers(updated)
        // 被移出的人如果正好是管理员，管理权回落到最早加入的那位，不能让组里没有管理员。
        val admin = sanitizeAdmin(prefs[Keys.admin], updated)
            ?: updated.minByOrNull { it.joinedAt }?.id
        writeAdmin(admin)
        stateOf(updated, prefs[Keys.boundAt], myId, admin)
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun replaceMembers(members: List<Member>) {
        val myId = profileStore.observeAuthorId().first()
        val sanitized = members
            .associateBy { it.id }
            .values
            .sortedBy { it.joinedAt }
            .take(GroupState.MAX_MEMBERS)
        writeMembers(sanitized)

        val prefs = context.pairingDataStore.data.first()
        val admin = sanitizeAdmin(prefs[Keys.admin], sanitized)
            ?: sanitized.minByOrNull { it.joinedAt }?.id
        writeAdmin(admin)

        // 名单有变化即视为已建立关系，记下绑定时间。
        context.pairingDataStore.edit { prefs ->
            if (sanitized.any { it.id != myId } && prefs[Keys.boundAt] == null) {
                prefs[Keys.boundAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun setAdmin(memberId: String): Result<GroupState> = runCatching {
        val myId = profileStore.observeAuthorId().first()
        val prefs = context.pairingDataStore.data.first()
        val members = decode(prefs[Keys.members], myId)
        require(members.any { it.id == memberId }) { "名单里没有这个人" }
        writeAdmin(memberId)
        stateOf(members, prefs[Keys.boundAt], myId, memberId)
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    override suspend fun adoptRemoteAdmin(memberId: String?) {
        val myId = profileStore.observeAuthorId().first()
        val prefs = context.pairingDataStore.data.first()
        val members = decode(prefs[Keys.members], myId)
        // 远端指定的人在名单里才采纳，否则保持现状（远端可能还没拉到新成员）。
        val accepted = memberId?.takeIf { id -> members.any { it.id == id } } ?: return
        writeAdmin(accepted)
    }

    /**
     * 身份迁移：名单里的旧 id 换成新 id。
     *
     * 关键点是「不留下两个人」：如果新 id 已经在名单里（例如迁移过一次，
     * 或者远端先带回来了），合并成一条，取更早的 joinedAt 与更完整的昵称，
     * 避免同一个人在成员列表里出现两次、或者昵称被打回默认值。
     *
     * 这里刻意读「原始名单」而不是 decode()：decode 会先塞一条
     * `name = "我"、joinedAt = 0` 的占位自己，用它去合并会把真实昵称盖掉。
     */
    override suspend fun migrateMemberId(fromId: String, toId: String): Result<GroupState> =
        runCatching {
            if (fromId.isBlank() || toId.isBlank() || fromId == toId) return@runCatching current()

            val myId = profileStore.observeAuthorId().first()
            val prefs = context.pairingDataStore.data.first()
            val stored = runCatching {
                json.decodeFromString(memberListSerializer, prefs[Keys.members].orEmpty())
            }.getOrDefault(emptyList())

            val moving = stored.firstOrNull { it.id == fromId }
            val others = stored.filterNot { it.id == fromId || it.id == toId }
            val existingTarget = stored.firstOrNull { it.id == toId }

            val merged = if (moving == null) {
                stored
            } else {
                val name = listOf(existingTarget?.name, moving.name)
                    .firstOrNull { !it.isNullOrBlank() } ?: "我"
                val joinedAt = listOfNotNull(existingTarget?.joinedAt, moving.joinedAt)
                    .filter { it > 0L }
                    .minOrNull() ?: moving.joinedAt
                (others + moving.copy(id = toId, name = name, joinedAt = joinedAt))
                    .sortedBy { it.joinedAt }
            }

            writeMembers(merged)

            val admin = when (prefs[Keys.admin]) {
                fromId -> toId
                else -> sanitizeAdmin(prefs[Keys.admin], merged)
            } ?: merged.minByOrNull { it.joinedAt }?.id
            writeAdmin(admin)

            stateOf(merged, prefs[Keys.boundAt], myId, admin)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }

    override suspend fun unbind() {
        context.pairingDataStore.edit { prefs ->
            prefs.remove(Keys.members)
            prefs.remove(Keys.boundAt)
            prefs.remove(Keys.admin)
        }
    }

    private suspend fun writeMembers(members: List<Member>) {
        val encoded = json.encodeToString(memberListSerializer, members)
        context.pairingDataStore.edit { prefs -> prefs[Keys.members] = encoded }
    }

    private suspend fun writeAdmin(id: String?) {
        context.pairingDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.admin) else prefs[Keys.admin] = id
        }
    }

    /**
     * 解析成员名单。
     *
     * 名单为空时返回仅仅包含「我」的一条，保证第一次打开就有身份可用，
     * 不必等用户先去配对页走一遍。
     */
    private fun decode(raw: String?, myId: String): List<Member> {
        val parsed = raw?.let {
            runCatching { json.decodeFromString(memberListSerializer, it) }.getOrNull()
        }.orEmpty()
        if (parsed.any { it.id == myId }) return parsed
        val self = Member(id = myId, name = "我", joinedAt = 0L)
        return listOf(self) + parsed
    }

    /** 管理员必须在名单里；不在就返回 null，由调用方回落到「最早加入」。 */
    private fun sanitizeAdmin(raw: String?, members: List<Member>): String? =
        raw?.takeIf { id -> members.any { it.id == id } }

    private fun stateOf(
        members: List<Member>,
        boundAt: Long?,
        myId: String,
        admin: String?,
    ): GroupState = GroupState(
        members = members,
        myId = myId,
        boundAt = boundAt,
        adminId = admin,
    )
}
