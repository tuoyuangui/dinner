package com.guolongli.dinner.di

import android.content.Context
import androidx.room.Room
import com.guolongli.dinner.data.image.ImageCompressor
import com.guolongli.dinner.data.local.db.DinnerDatabase
import com.guolongli.dinner.data.local.db.dao.CommentDao
import com.guolongli.dinner.data.local.db.dao.MealRecordDao
import com.guolongli.dinner.data.local.security.KeystoreCipher
import com.guolongli.dinner.data.media.VideoTranscoder
import com.guolongli.dinner.data.remote.NetworkModule
import com.guolongli.dinner.data.remote.TokenCache
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.api.BaiduMultimediaApi
import com.guolongli.dinner.data.remote.api.BaiduOAuthApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/**
 * 实例构造（构建器/工厂），供依赖图直接提供非接口实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object ProvidesModule {

    // ---------- 数据库 ----------
    /**
     * 数据库版本为 1，没有历史迁移。
     *
     * 2.0.0 是「当成新软件重写」：表结构去掉了 v1..v6 的单媒体旧列，schema 无法沿用，
     * 因此版本重置为 1。已装过旧版的设备覆盖安装时，Room 会发现磁盘上的库是旧 schema，
     * 这里用 [fallbackToDestructiveMigration] 直接重建空库——而不是让它抛
     * `IllegalStateException` 把应用挡在启动页。
     *
     * 重建后本地只剩空表，记录从云端 records.json 取回即可（授权成功会自动拉一次）。
     * 代价是本地未同步到云端的改动会丢，这是明确的取舍，已写进 AGENTS.md 与 README。
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DinnerDatabase =
        Room.databaseBuilder(context, DinnerDatabase::class.java, DinnerDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideMealRecordDao(db: DinnerDatabase): MealRecordDao = db.mealRecordDao()

    @Provides
    fun provideCommentDao(db: DinnerDatabase): CommentDao = db.commentDao()

    // ---------- 本地安全存储 ----------
    @Provides
    @Singleton
    fun provideKeystoreCipher(): KeystoreCipher = KeystoreCipher()

    @Provides
    @Singleton
    fun provideTokenCache(): TokenCache = TokenCache()

    // ---------- 网络 ----------
    @Provides
    @Singleton
    @Named("pan")
    fun providePanClient(tokenCache: TokenCache): OkHttpClient =
        NetworkModule.panClient(tokenCache)

    @Provides
    @Singleton
    @Named("oauth")
    fun provideOauthClient(): OkHttpClient = NetworkModule.oauthClient()

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadClient(): OkHttpClient = NetworkModule.downloadClient()

    @Provides
    @Singleton
    fun provideFileApi(@Named("pan") panClient: OkHttpClient): BaiduFileApi =
        NetworkModule.fileApi(NetworkModule.panRetrofit(panClient))

    @Provides
    @Singleton
    fun provideMultimediaApi(@Named("pan") panClient: OkHttpClient): BaiduMultimediaApi =
        NetworkModule.multimediaApi(NetworkModule.panRetrofit(panClient))

    @Provides
    @Singleton
    fun provideOauthApi(@Named("oauth") oauthClient: OkHttpClient): BaiduOAuthApi =
        NetworkModule.oauthApi(NetworkModule.oauthRetrofit(oauthClient))

    // ---------- 图片处理 ----------
    @Provides
    @Singleton
    fun provideImageCompressor(@ApplicationContext context: Context): ImageCompressor =
        ImageCompressor(context)

    // ---------- 视频处理 ----------
    /** 无状态，构造便宜；但转码慢且吃 CPU，保持单例避免并发多份同时转。 */
    @Provides
    @Singleton
    fun provideVideoTranscoder(): VideoTranscoder = VideoTranscoder()
}