package com.guolongli.dinner.di

import com.guolongli.dinner.data.auth.BaiduAuthRepository
import com.guolongli.dinner.data.avatar.AvatarRepositoryImpl
import com.guolongli.dinner.data.group.CloudTextFile
import com.guolongli.dinner.data.group.GroupConfigRepositoryImpl
import com.guolongli.dinner.data.group.NetdiskTextFile
import com.guolongli.dinner.data.group.GroupRepositoryImpl
import com.guolongli.dinner.data.identity.IdentityRepositoryImpl
import com.guolongli.dinner.data.location.AndroidLocationProvider
import com.guolongli.dinner.data.local.prefs.PairingPreferences
import com.guolongli.dinner.data.local.prefs.SecureCredentialStore
import com.guolongli.dinner.data.local.prefs.AppLockPreferences
import com.guolongli.dinner.data.local.prefs.StorageConfigStore
import com.guolongli.dinner.data.media.MediaCacheRepository
import com.guolongli.dinner.data.repository.CommentRepositoryImpl
import com.guolongli.dinner.data.repository.MealRepositoryImpl
import com.guolongli.dinner.data.sync.SyncRepositoryImpl
import com.guolongli.dinner.di.impl.AppMediaImporter
import com.guolongli.dinner.di.impl.DataStoreLocalProfileStore
import com.guolongli.dinner.domain.media.MediaImporter
import com.guolongli.dinner.domain.identity.IdentityRepository
import com.guolongli.dinner.domain.location.LocationProvider
import com.guolongli.dinner.domain.repository.AuthRepository
import com.guolongli.dinner.domain.repository.AvatarRepository
import com.guolongli.dinner.domain.repository.CommentRepository
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.GroupConfigRepository
import com.guolongli.dinner.domain.repository.GroupRepository
import com.guolongli.dinner.domain.repository.MealRepository
import com.guolongli.dinner.domain.repository.MediaCache
import com.guolongli.dinner.domain.repository.PairingRepository
import com.guolongli.dinner.domain.repository.AppLockRepository
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.domain.repository.SyncRepository
import com.guolongli.dinner.ui.profile.LocalProfileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 接口到实现的绑定。feature 只依赖接口，具体实现在这里替换。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingPreferences): PairingRepository

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: SecureCredentialStore): CredentialStore

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: BaiduAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMealRepository(impl: MealRepositoryImpl): MealRepository

    @Binds
    @Singleton
    abstract fun bindCommentRepository(impl: CommentRepositoryImpl): CommentRepository

    @Binds
    @Singleton
    abstract fun bindStorageConfig(impl: StorageConfigStore): StorageConfigRepository

    @Binds
    @Singleton
    abstract fun bindAppLock(impl: AppLockPreferences): AppLockRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindLocalProfileStore(impl: DataStoreLocalProfileStore): LocalProfileStore

    @Binds
    @Singleton
    abstract fun bindMediaImporter(impl: AppMediaImporter): MediaImporter

    @Binds
    @Singleton
    abstract fun bindMediaCache(impl: MediaCacheRepository): MediaCache

    @Binds
    @Singleton
    abstract fun bindAvatarRepository(impl: AvatarRepositoryImpl): AvatarRepository

    @Binds
    @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds
    @Singleton
    abstract fun bindGroupConfigRepository(impl: GroupConfigRepositoryImpl): GroupConfigRepository

    @Binds
    @Singleton
    abstract fun bindCloudTextFile(impl: NetdiskTextFile): CloudTextFile

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: AndroidLocationProvider): LocationProvider

}