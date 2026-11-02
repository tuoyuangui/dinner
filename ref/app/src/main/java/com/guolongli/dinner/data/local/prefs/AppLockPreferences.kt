package com.guolongli.dinner.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.domain.repository.AppLockRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用锁开关（DataStore）。
 *
 * 单独一个文件：安全相关的偏好不跟存储路径、成员关系混在一起，
 * 将来要加「后台多久重新锁」之类的选项也放这里。
 */
private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dinner_security"
)

@Singleton
class AppLockPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLockRepository {

    private object Keys {
        val enabled = booleanPreferencesKey("app_lock_enabled")
    }

    override fun observeEnabled(): Flow<Boolean> =
        context.securityDataStore.data.map { prefs -> prefs[Keys.enabled] ?: false }

    override suspend fun enabled(): Boolean = observeEnabled().first()

    override suspend fun setEnabled(on: Boolean) {
        context.securityDataStore.edit { prefs -> prefs[Keys.enabled] = on }
    }
}
