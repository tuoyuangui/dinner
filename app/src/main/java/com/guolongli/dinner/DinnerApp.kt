package com.guolongli.dinner

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.guolongli.dinner.core.AutoSyncRuntime
import com.guolongli.dinner.core.CrashGuard
import com.guolongli.dinner.core.Graph

/**
 * 进程一起就把依赖装好。
 *
 * 放在 Application 而不是 Activity，是因为自动同步的后台任务可能在用户没打开界面时
 * 单独把进程拉起来（WorkManager 直接跑 SyncWorker），那时候没有 Activity。
 *
 * 三条纪律，都是踩过坑换来的：
 * 1. **onCreate 里不做重活**。这里只记一个 context、点一下后台同步，毫秒级返回。
 * 2. **任何一步失败都不能让应用打不开**。定时同步是增强功能，用户要的是记一顿饭。
 * 3. **WorkManager 走按需初始化**（实现 [Configuration.Provider]，并在 manifest 里摘掉
 *    默认的 `WorkManagerInitializer`）。默认初始化是 startup 的 ContentProvider 跑的，
 *    比这里还早，一旦它没跑到（进程被直接拉起、ROM 清理自启），后面第一次
 *    `getInstance()` 就会抛 `IllegalStateException`——而它正站在启动路径上，
 *    用户看到的就是「点开一片空白」。按需初始化把这件事推迟到真正要排任务的那一刻。
 */
class DinnerApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // 崩溃记录必须最先装：装晚了就捕不到启动期的崩溃
        CrashGuard.install(this)
        Graph.init(this)
        // 只挂触发时机，排期与首轮同步都在 AutoSyncRuntime 的后台线程里
        AutoSyncRuntime.start(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()
}
