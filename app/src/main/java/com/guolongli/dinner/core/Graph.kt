package com.guolongli.dinner.core

import android.content.Context
import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.RecordRepository
import com.guolongli.dinner.data.SettingsStore
import com.guolongli.dinner.data.auth.BaiduAuthManager
import com.guolongli.dinner.data.auth.BaiduAuthStore
import com.guolongli.dinner.data.avatar.AvatarRepository
import com.guolongli.dinner.data.group.GroupConfigSync
import com.guolongli.dinner.data.group.GroupService
import com.guolongli.dinner.data.location.LocationProvider
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.maintenance.CloudJanitor
import com.guolongli.dinner.data.maintenance.JanitorReport
import com.guolongli.dinner.data.media.MediaCache
import com.guolongli.dinner.data.media.MediaRestorer
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.data.sync.AppSyncPrefs
import com.guolongli.dinner.data.sync.AutoSync
import com.guolongli.dinner.data.sync.NetdiskRemoteStore
import com.guolongli.dinner.data.sync.RoomLocalStore
import com.guolongli.dinner.data.sync.SyncEngine
import com.guolongli.dinner.data.sync.SyncPrefs
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.data.sync.SyncReason
import com.guolongli.dinner.sync.SyncScheduler

/**
 * 极简依赖装配。工程只有一条依赖链（库 → 引擎 → 仓储 → 界面），
 * 上 Hilt 收益不大，先用 object + lazy 把关系写在一处，看代码时不用跳。
 *
 * 注意：所有懒加载都依赖 init() 里拿到的 applicationContext，先 init 再用。
 */
object Graph {

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context =
        appContext ?: error("Graph 还没初始化，先在 Application / Activity 里调 Graph.init(context)")

    val profile: ProfileStore by lazy { ProfileStore(ctx()) }

    val settings: SettingsStore by lazy { SettingsStore(ctx()) }

    val database: DinnerDatabase by lazy { DinnerDatabase.build(ctx()) }

    val mediaCache: MediaCache by lazy { MediaCache(ctx()) }

    val authStore: BaiduAuthStore by lazy { BaiduAuthStore(ctx()) }

    val authManager: BaiduAuthManager by lazy { BaiduAuthManager(authStore) }

    val netdisk: NetdiskRepository by lazy {
        NetdiskRepository(authManager, retryTimes = { settings.retryTimes })
    }

    val remoteStore: NetdiskRemoteStore by lazy { NetdiskRemoteStore(netdisk) }

    val avatarRepository: AvatarRepository by lazy {
        AvatarRepository(profile, { netdisk })
    }

    /**
     * 群组配置（群名 / 入群密码 / 管理员口令）的云端同步。
     * 它的文件落在 /apps/dinner/config/passwd.json，与记录分开——
     * 每次改密码都重写整份记录快照是不必要的。
     */
    val groupConfig: GroupConfigSync by lazy { GroupConfigSync(profile, { netdisk }) }

    /**
     * 群组：建组 / 入群 / 口令校验 / 成员名单。
     *
     * 它要拿数据库做「绑定手机号后把旧身份的记录改名」，所以这里把 database 也传进去——
     * 身份迁移必须是事务，不能拆给调用方各写一半。
     */
    val groupService: GroupService by lazy {
        GroupService(
            profile = profile,
            config = groupConfig,
            netdisk = { netdisk },
            db = { database },
            admin = adminSession,
        )
    }

    /** 一次性定位：只在保存记录时取一个点，不订阅、不追踪 */
    val locationProvider: LocationProvider by lazy { LocationProvider(ctx()) }

    val mediaRestorer: MediaRestorer by lazy {
        MediaRestorer(database, { netdisk }, mediaCache)
    }

    /** 云端整理：清过期墓碑与孤儿附件，规则见 [CloudJanitor] */
    val cloudJanitor: CloudJanitor by lazy { CloudJanitor({ netdisk }) }

    /**
     * 到点了才整理云端。
     *
     * 整理要走一次列表 + 逐个下载墓碑判断，比一次普通同步贵得多，所以按
     * [SettingsStore.CLOUD_SWEEP_INTERVAL_MS]（一周）限流；「我的」页手动点的那次
     * 传 force = true 直接跑，不受间隔限制。
     */
    suspend fun sweepCloudIfDue(force: Boolean = false): JanitorReport? {
        if (!authManager.isAuthorized()) return null
        val moment = System.currentTimeMillis()
        if (!force && moment - settings.lastCloudSweepAt < SettingsStore.CLOUD_SWEEP_INTERVAL_MS) {
            return null
        }
        val report = cloudJanitor.run()
        // 失败了不记时间，下一轮同步再试；否则一次限流会让整理停摆一周
        if (report.ok) settings.lastCloudSweepAt = moment
        return report
    }

    /**
     * 本地的头像地址既用于界面显示，也用于「写记录文档时把作者头像带上」，
     * 所以两个方向都接到 ProfileStore 上：一个读，一个学。
     */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            local = RoomLocalStore(
                db = database,
                avatarRemoteOf = { profile.avatarRemoteOf(it) },
                onAvatarSeen = { authorId, remotePath ->
                    profile.rememberAvatarRemote(authorId, remotePath)
                },
            ),
            remote = remoteStore,
        )
    }

    // ------------------------------------------------------------------ 自动同步

    val syncPrefs: SyncPrefs by lazy { AppSyncPrefs(ctx()) }

    val scheduler: SyncScheduler by lazy { SyncScheduler(ctx(), syncPrefs) }

    /**
     * 自动同步的协调器。设置变了就重排后台任务，所以 onChange 直接连到 scheduler；
     * 每轮跑完顺手把缺的头像补几张、把群组配置对齐一次——
     * 这两件事都很轻（一次 list + 必要时一次下载），但不做就会「密码改了别的设备不认」。
     */
    val autoSync: AutoSync by lazy {
        AutoSync(
            engine = syncEngine,
            prefs = syncPrefs,
            authorized = { authManager.isAuthorized() },
            onChange = { scheduler.reschedule() },
            afterSync = {
                // 每一项都自己吞掉异常。
                // 以前这些动作串在一个块里，头像补齐一抛异常，
                // 后面的「补齐他人推文的图片视频」就永远轮不到——
                // 而那恰恰是用户最需要它工作的场景（别人发了新照片，这边却看不到）。
                // 分开之后，任何一项失败都只影响它自己。
                runCatching { avatarRepository.syncMissing() }
                runCatching { groupConfig.sync() }
                // 名单是「谁在群里」的唯一真相：别人加进来之后这边要能看见
                runCatching { groupService.syncMembers() }
                // 整理是「顺手做」的事，按周限流；放在同步之后跑，
                // 是因为它自己也走网盘接口，同时打两边容易撞频控。
                runCatching { sweepCloudIfDue() }
                // 本地保留策略同理：一天最多扫一次，只删「云端已确认有副本」的旧文件
                runCatching { cleanupLocalIfDue() }
                // 同步刚把别人的记录拉下来，它们的照片视频此刻只在云端。
                // 每轮同步后全量补齐（不限时间窗），时间线上的媒体因此总能直接打开看；
                // 补齐是幂等的——已经下过的不会重复下，一轮最多取 [MediaRestorer] 的上限。
                runCatching { restoreAllMedia() }
            },
        )
    }

    /**
     * 按「本地数据保留期」清理本机旧媒体。
     *
     * 和云端整理一样按天限流：清理要遍历记录 + 逐个查媒体行，
     * 每轮同步都跑一遍纯属浪费。保留期设为「永久」时直接跳过。
     */
    suspend fun cleanupLocalIfDue(force: Boolean = false): Int {
        val days = settings.retentionDays
        if (days <= 0) return 0
        val moment = System.currentTimeMillis()
        if (!force && moment - settings.lastCleanupAt < SettingsStore.CLEANUP_INTERVAL_MS) return 0
        val removed = runCatching { repository.cleanupLocalMedia(days) }.getOrDefault(0)
        settings.lastCleanupAt = moment
        return removed
    }

    /**
     * 同步后把缺失的远端媒体**全量**补回来（别人推文的图片视频、换设备后的历史都在内）。
     *
     * 换设备 / 重装之后，同步只把记录文档（文字、评分、路径）拉下来，照片视频还都在云端。
     * 不补回的话，时间线会是一排空白格子，看起来就像「同步了但照片没来」。
     *
     * 不设时间窗、每次同步都跑：[MediaRestorer.restoreAll] 只下载「云端有、本地没有」的，
     * 补齐之后再跑就是一次空查询，代价可以忽略；单轮下载量有上限，剩下的下轮继续。
     */
    suspend fun restoreAllMedia(): Int {
        if (!authManager.isAuthorized()) return 0
        return runCatching { mediaRestorer.restoreAll() }.getOrDefault(0)
    }

    /** 写库之后立刻催一次同步：改了东西就别等下一个周期 */
    private fun afterWrite() {
        if (syncPrefs.autoEnabled) autoSync.request(SyncReason.DATA_CHANGED)
    }

    /** 管理员判定与「本次运行是否已解锁」，权限只在内存里 */
    val adminSession: AdminSession by lazy { AdminSession(profile) }

    val repository: RecordRepository by lazy {
        RecordRepository(
            db = database,
            engine = syncEngine,
            user = { profile.user() },
            avatarPathOf = { profile.avatarPathOf(it) },
            mediaCache = mediaCache,
            onWrite = { afterWrite() },
            canManage = { authorId -> adminSession.canManage(authorId) },
            canTouchCloud = { authorId -> adminSession.canTouchCloud(authorId) },
            deleteRemote = { path -> netdisk.delete(path) },
            isAdminMember = { memberId -> adminSession.isAdmin(memberId) },
            knownMembers = { profile.members() },
            // 改昵称/头像不动数据库，靠这个版本号让列表当场重映射
            identityChanges = profile.identityTick,
        )
    }

    fun isAuthorized(): Boolean = authManager.isAuthorized()

    /** 退出 / 换 Key 后要连带清掉会话 */
    fun reset() {
        authManager.logout()
        scheduler.cancel()
    }
}
