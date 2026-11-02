package com.guolongli.dinner.di.impl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.ui.profile.LocalProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore 实现的本地身份与昵称。
 *
 * 详述见 [LocalProfileStore]：
 * - [observeAuthorId] 优先返回手机号派生的稳定 id；用户还没绑手机号时，
 *   退回一个设备级随机标识（旧行为），保证任何时刻都有身份可用。
 * - 三个 Flow 都立即发出当前值（DataStore 天然满足），UI 首帧不空白。
 *
 * token / 配对状态绝对不经过这里。
 */
@Singleton
class DataStoreLocalProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalProfileStore {

    private object Keys {
        val authorId = stringPreferencesKey("author_id")
        val myName = stringPreferencesKey("my_name")

        /** 归一化后的手机号明文，只存本机，不上网盘。 */
        val phone = stringPreferencesKey("phone")

        /** 退役的旧 id，JSON 数组。 */
        val retiredIds = stringPreferencesKey("retired_ids")
    }

    private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "dinner_profile"
    )

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val retiredJson = Json { ignoreUnknownKeys = true }
    private val retiredList = ListSerializer(String.serializer())

    override fun observeAuthorId(): Flow<String> =
        context.profileDataStore.data.map { prefs ->
            prefs[Keys.authorId]?.takeIf { it.isNotBlank() }
                ?: generatedAuthorId()
        }

    override fun observeMyName(): Flow<String> =
        context.profileDataStore.data.map { prefs ->
            prefs[Keys.myName]?.takeIf { it.isNotBlank() } ?: "我"
        }

    override suspend fun saveMyName(name: String) {
        context.profileDataStore.edit { it[Keys.myName] = name }
    }

    override fun observePhone(): Flow<String?> =
        context.profileDataStore.data.map { prefs ->
            prefs[Keys.phone]?.takeIf { it.isNotBlank() }
        }

    /**
     * 绑定手机号：把 authorId 换成号码派生的稳定 id。
     *
     * 旧 id 会进退役名单，避免远端 members.json 里的旧条目在下次合并时
     * 又变成「另一个人」冒出来。
     */
    override suspend fun bindPhone(normalizedPhone: String): String? {
        val target = PhoneIdentity.idOf(normalizedPhone)
        var previous: String? = null

        context.profileDataStore.edit { prefs ->
            val current = prefs[Keys.authorId]?.takeIf { it.isNotBlank() }
            if (current != null && current != target) {
                previous = current
                val retired = (decodeRetired(prefs[Keys.retiredIds]) + current)
                    .filterNot { it == target }
                    .toSet()
                prefs[Keys.retiredIds] = encodeRetired(retired)
            }
            prefs[Keys.phone] = normalizedPhone
            prefs[Keys.authorId] = target
        }
        return previous
    }

    override fun observeRetiredIds(): Flow<Set<String>> =
        context.profileDataStore.data.map { prefs -> decodeRetired(prefs[Keys.retiredIds]) }

    private fun decodeRetired(raw: String?): Set<String> =
        raw?.let { runCatching { retiredJson.decodeFromString(retiredList, it) }.getOrNull() }
            ?.filter { id -> id.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun encodeRetired(ids: Set<String>): String =
        retiredJson.encodeToString(retiredList, ids.toList())

    /**
     * 若尚未持久化设备标识，则即时生成一个并异步落盘后返回。
     *
     * 数据流里的多次 map 可能在标识落盘前读到不同值——用内存复用同一值，
     * 保证本进程内标识稳定。
     */
    @Volatile
    private var inMemoryAuthorId: String? = null

    private fun generatedAuthorId(): String {
        inMemoryAuthorId?.let { return it }
        val generated = "dev-" + UUID.randomUUID().toString()
        inMemoryAuthorId = generated
        ioScope.launch {
            context.profileDataStore.edit { it[Keys.authorId] = generated }
        }
        return generated
    }
}