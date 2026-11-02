package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.SyncConfig
import kotlinx.coroutines.flow.Flow

/**
 * 存储配置：网盘根目录 + 本地保留天数 + 网盘重试次数。
 *
 * 只存配置字符串与整数，不放任何 token；改这些设置不会动已经授权好的凭证。
 */
interface StorageConfigRepository {

    /** 当前生效的路径集合，UI 首帧即可用（DataStore 立即发值）。 */
    fun observePaths(): Flow<NetdiskPaths>

    suspend fun paths(): NetdiskPaths

    /**
     * 修改根目录。传入值会经 [NetdiskPaths.normalize] 校验：
     * 必须以 `/apps/` 开头，否则返回失败（红线：不得在数据目录外读写）。
     */
    suspend fun setRoot(raw: String): Result<NetdiskPaths>

    /** 恢复默认根目录。 */
    suspend fun resetToDefault()

    /**
     * 群组身份（创建者手机号派生 id）。
     *
     * 它决定所有同步路径：`<root>/group_<管理员手机号>/…`（没有 `groups/` 中间层）。
     * 空表示还没建群组，此时**没有任何可写路径**——不存在「退回根目录」的退路，
     * 否则媒体、快照、头像会散落在 `group_*` 外面。
     */
    fun observeGroupId(): Flow<String?>

    suspend fun groupId(): String?

    /** 写入群组身份；传 null 表示退出群组、回到「还没建群组」状态。 */
    suspend fun setGroupId(id: String?)

    /**
     * 是否在存储前压缩媒体。默认 false：原图原视频直接存，画质不打折。
     *
     * 打开后图片按 JPEG 80% / 最长边 1920 压，视频尽力转码压小；
     * 关掉则图片保留原始字节（非 JPEG 格式仍需转成 JPEG 才能存），视频原样保存。
     */
    fun observeCompressMedia(): Flow<Boolean>

    suspend fun compressMedia(): Boolean

    suspend fun setCompressMedia(enabled: Boolean)

    /** 本机保留天数，[PERSIST_FOREVER] 表示永不清本地。 */
    fun observeRetentionDays(): Flow<Int>

    suspend fun retentionDays(): Int

    suspend fun setRetentionDays(days: Int)

    /**
     * 与网盘通讯时单次请求最多尝试几次，全部失败才向上报错。
     *
     * 只重试瞬时故障（频控 / 网络不可用 / IO 异常），业务性错误（文件已存在、
     * 鉴权失败）不重试——那些重试多少次结果都一样，只会白白烧掉配额。
     */
    fun observeNetworkRetries(): Flow<Int>

    suspend fun networkRetries(): Int

    /**
     * 设置重试次数。越界值一律收敛到 [DEFAULT_NETWORK_RETRIES]：
     * 这个值决定「一次失败要打几个请求」，放开了会把开发期 100 次/小时的配额打空。
     */
    suspend fun setNetworkRetries(times: Int)

    companion object {
        val DEFAULT_ROOT: String get() = NetdiskPaths.DEFAULT_ROOT

        /** 默认不压缩：先保画质，原图原视频直接存。 */
        const val DEFAULT_COMPRESS_MEDIA = false

        /** 默认在本机留 30 天，够翻最近的记录，也不至于把手机塞满。 */
        const val DEFAULT_RETENTION_DAYS = 30

        /** 0 表示永久保留，不做任何本地清理。 */
        const val PERSIST_FOREVER = 0

        /** 可选的保留档位。 */
        val RETENTION_OPTIONS = listOf(7, 30, 90, 180, PERSIST_FOREVER)

        /**
         * 默认重试 3 次：与网盘的瞬时抖动（频控、断线）对得上，又不至于烧配额。
         *
         * 单一来源：与 [SyncConfig.RETRY_MAX_ATTEMPTS] 是同一个值，
         * 分两处定义的话改了设置也不会生效。
         */
        const val DEFAULT_NETWORK_RETRIES = SyncConfig.RETRY_MAX_ATTEMPTS

        /** 重试次数的可选档位。1 表示不重试（失败即报错）。 */
        val NETWORK_RETRY_OPTIONS: List<Int> get() = SyncConfig.RETRY_OPTIONS

        const val NETWORK_RETRY_MIN = SyncConfig.RETRY_MIN
        const val NETWORK_RETRY_MAX = SyncConfig.RETRY_MAX

        fun retentionLabel(days: Int): String =
            if (days == PERSIST_FOREVER) "永久保留" else "$days 天"

        /** 重试次数的文案：1 次是「不重试」，说成「1 次」容易被理解成「试 1 遍」。 */
        fun networkRetriesLabel(times: Int): String = if (times <= 1) "不重试" else "$times 次"
    }
}
