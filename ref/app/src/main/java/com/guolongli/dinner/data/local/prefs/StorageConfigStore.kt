package com.guolongli.dinner.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 存储配置（DataStore）。根目录与本地保留天数放一起，与 token、成员关系分开。 */
private val Context.storageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dinner_storage"
)

@Singleton
class StorageConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : StorageConfigRepository {

    private object Keys {
        val root = stringPreferencesKey("root_dir")
        val retentionDays = intPreferencesKey("retention_days")
        val compressMedia = booleanPreferencesKey("compress_media")
        val networkRetries = intPreferencesKey("network_retries")

        /** 群组身份：创建者手机号派生的 id，决定所有同步路径。 */
        val groupId = stringPreferencesKey("group_id")
    }

    override fun observePaths(): Flow<NetdiskPaths> =
        context.storageDataStore.data.map { prefs ->
            val root = prefs[Keys.root]?.takeIf { it.isNotBlank() }
            val group = prefs[Keys.groupId]?.takeIf { it.isNotBlank() }
            NetdiskPaths(root ?: NetdiskPaths.DEFAULT_ROOT, group)
        }

    override suspend fun paths(): NetdiskPaths = observePaths().first()

    override suspend fun setRoot(raw: String): Result<NetdiskPaths> {
        val normalized = NetdiskPaths.normalize(raw)
        return normalized.map { root ->
            context.storageDataStore.edit { prefs -> prefs[Keys.root] = root }
            NetdiskPaths(root, currentGroupId())
        }
    }

    override suspend fun resetToDefault() {
        context.storageDataStore.edit { prefs ->
            prefs.remove(Keys.root)
        }
    }

    override fun observeGroupId(): Flow<String?> =
        context.storageDataStore.data.map { prefs ->
            prefs[Keys.groupId]?.takeIf { it.isNotBlank() }
        }

    override suspend fun groupId(): String? = currentGroupId()

    override suspend fun setGroupId(id: String?) {
        context.storageDataStore.edit { prefs ->
            val clean = id?.trim()?.takeIf { it.isNotEmpty() }
            if (clean == null) prefs.remove(Keys.groupId) else prefs[Keys.groupId] = clean
        }
    }

    private suspend fun currentGroupId(): String? =
        context.storageDataStore.data.first()[Keys.groupId]?.takeIf { it.isNotBlank() }

    override fun observeCompressMedia(): Flow<Boolean> =
        context.storageDataStore.data.map { prefs ->
            prefs[Keys.compressMedia] ?: StorageConfigRepository.DEFAULT_COMPRESS_MEDIA
        }

    override suspend fun compressMedia(): Boolean = observeCompressMedia().first()

    override suspend fun setCompressMedia(enabled: Boolean) {
        context.storageDataStore.edit { prefs -> prefs[Keys.compressMedia] = enabled }
    }

    override fun observeRetentionDays(): Flow<Int> =
        context.storageDataStore.data.map { prefs ->
            prefs[Keys.retentionDays] ?: StorageConfigRepository.DEFAULT_RETENTION_DAYS
        }

    override suspend fun retentionDays(): Int = observeRetentionDays().first()

    /**
     * 只接受 [StorageConfigRepository.RETENTION_OPTIONS] 里的档位，
     * 越界值一律收敛到默认值——保留天数直接决定「会不会删本地数据」，不接受自由输入。
     */
    override suspend fun setRetentionDays(days: Int) {
        val safe = if (days in StorageConfigRepository.RETENTION_OPTIONS) {
            days
        } else {
            StorageConfigRepository.DEFAULT_RETENTION_DAYS
        }
        context.storageDataStore.edit { prefs -> prefs[Keys.retentionDays] = safe }
    }

    override fun observeNetworkRetries(): Flow<Int> =
        context.storageDataStore.data.map { prefs ->
            prefs[Keys.networkRetries]
                ?.takeIf { it in StorageConfigRepository.NETWORK_RETRY_MIN..StorageConfigRepository.NETWORK_RETRY_MAX }
                ?: StorageConfigRepository.DEFAULT_NETWORK_RETRIES
        }

    override suspend fun networkRetries(): Int = observeNetworkRetries().first()

    /**
     * 只接受 [StorageConfigRepository.NETWORK_RETRY_MIN]..[NETWORK_RETRY_MAX]，
     * 越界收敛到默认值。开发期接口只有约 100 次/小时，放开上限会把配额烧在无谓的重试上。
     */
    override suspend fun setNetworkRetries(times: Int) {
        val range = StorageConfigRepository.NETWORK_RETRY_MIN..StorageConfigRepository.NETWORK_RETRY_MAX
        val safe = if (times in range) times else StorageConfigRepository.DEFAULT_NETWORK_RETRIES
        context.storageDataStore.edit { prefs -> prefs[Keys.networkRetries] = safe }
    }
}
