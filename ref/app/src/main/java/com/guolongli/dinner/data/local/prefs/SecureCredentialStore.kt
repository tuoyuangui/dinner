package com.guolongli.dinner.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.data.local.security.KeystoreCipher
import com.guolongli.dinner.domain.model.BaiduCredentials
import com.guolongli.dinner.domain.model.BaiduToken
import com.guolongli.dinner.domain.repository.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭证与 token 的加密落盘。
 *
 * - 开放平台凭证（appKey / secretKey）与 token 都用 [KeystoreCipher] 加密后存储。
 * - token 按设备隔离：本 DataStore 属于该设备的应用私有目录，不做任何跨设备拷贝。
 * - refresh_token 刷新后整体覆盖，绝不保留旧值以备复用。
 * - 明文不写日志。
 */
private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dinner_secure"
)

@Singleton
class SecureCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) : CredentialStore {

    private object Keys {
        val credentials = stringPreferencesKey("credentials_cipher")
        val token = stringPreferencesKey("token_cipher")
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeCredentials(): Flow<BaiduCredentials?> =
        context.secureDataStore.data.map { prefs ->
            prefs[Keys.credentials]?.let { decodeCredentials(it) }
        }

    override suspend fun credentials(): BaiduCredentials? =
        observeCredentials().first()

    override suspend fun saveCredentials(credentials: BaiduCredentials) {
        val payload = json.encodeToString(CredentialsPayload.serializer(), credentials.toPayload())
        val encrypted = cipher.encrypt(payload)
        context.secureDataStore.edit { prefs -> prefs[Keys.credentials] = encrypted }
    }

    override fun observeToken(): Flow<BaiduToken?> =
        context.secureDataStore.data.map { prefs ->
            prefs[Keys.token]?.let { decodeToken(it) }
        }

    override suspend fun token(): BaiduToken? = observeToken().first()

    override suspend fun saveToken(token: BaiduToken) {
        val payload = json.encodeToString(TokenPayload.serializer(), token.toPayload())
        val encrypted = cipher.encrypt(payload)
        // 整体覆盖：刷新后旧 refresh_token 即刻作废，不得保留。
        context.secureDataStore.edit { prefs -> prefs[Keys.token] = encrypted }
    }

    override suspend fun clearToken() {
        context.secureDataStore.edit { prefs -> prefs.remove(Keys.token) }
    }

    private fun decodeCredentials(encrypted: String): BaiduCredentials? = runCatching {
        val payload = json.decodeFromString(CredentialsPayload.serializer(), cipher.decrypt(encrypted))
        BaiduCredentials(appKey = payload.appKey, secretKey = payload.secretKey)
    }.getOrNull()

    private fun decodeToken(encrypted: String): BaiduToken? = runCatching {
        val payload = json.decodeFromString(TokenPayload.serializer(), cipher.decrypt(encrypted))
        BaiduToken(
            accessToken = payload.accessToken,
            refreshToken = payload.refreshToken,
            expiresAt = payload.expiresAt,
            scope = payload.scope,
        )
    }.getOrNull()

    private fun BaiduCredentials.toPayload() = CredentialsPayload(appKey = appKey, secretKey = secretKey)

    private fun BaiduToken.toPayload() = TokenPayload(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        scope = scope,
    )
}

@kotlinx.serialization.Serializable
private data class CredentialsPayload(
    val appKey: String,
    val secretKey: String,
)

@kotlinx.serialization.Serializable
private data class TokenPayload(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String? = null,
)
