package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.MealRecord

/** 同步的整体结果。UI 只消费该类型，不消费原始异常。 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val failed: Int = 0,
    val skipped: Boolean = false,
    val message: String? = null,
    /**
     * 本轮有「该发出去却没发成」的东西。
     *
     * 典型是记录全传上去了（[failed] == 0），但 `records.json` 快照或
     * `members.json` 名单没写成功：别人那边看不到这一轮的任何变化。
     *
     * 必须单独一个字段，不能靠 [failed] 表达：上传失败和发布失败是两件事，
     * 把后者算进 [failed] 会让 UI 说「N 条上传失败」，而实际上一条都没传失败。
     * 反过来，不记这一位就会让 [isSuccess] 在「快照没发出去」时仍然为真——
     * `TimelineViewModel.deleteBoth` 正是拿 `isSuccess` 决定要不要抹掉本地行，
     * 一旦这里说谎，云端删除意图就再也不会被同步出去。
     */
    val incomplete: Boolean = false,
) {
    val isPartial: Boolean get() = failed > 0 && (uploaded > 0 || downloaded > 0)

    /**
     * 是否成功：无上传失败、无未发布项，且不是「跳过」。
     *
     * 三种情况都不得视为成功：
     * 1. [failed] > 0——有记录没传成；
     * 2. [skipped]——没 token / 已取消，什么都没做；
     * 3. [incomplete]——记录传完了但快照 / 名单没发出去，别人看不到。
     * 少判任何一条，UI 都会「假装成功」。
     */
    val isSuccess: Boolean get() = failed == 0 && !skipped && !incomplete
}

/** 预取默认天数：近 30 天（AGENTS「同步」第 7 条）。再往前的按需用「从云端取回」手动补。 */
const val PREFETCH_DAYS = 30

/**
 * 客户端自建的同步入口。百度网盘不会推送变更，一切同步都由本地发起。
 */
interface SyncRepository {
    /**
     * 触发一次同步。
     *
     * @param auto 是否自动触发（进入应用 / 保存后后台刷）。自动同步只取
     *   [com.guolongli.dinner.domain.model.SyncState.PENDING]，
     *   [com.guolongli.dinner.domain.model.SyncState.FAILED] 仅手动触发时纳入，
     *   避免失败项在每次自动同步里被无限重试（红线）。
     */
    suspend fun syncNow(auto: Boolean = false): SyncOutcome

    /** 恢复：新设备从网盘拉 records.json 或扫描 meals/ 重建本地库。 */
    suspend fun restoreFromRemote(): Result<List<MealRecord>>

    /**
     * 只恢复一段时间内的记录。
     *
     * 配合本地保留策略：老数据过了保留期就从本机清掉，
     * 想翻某个月的时候再按区间补回来，而不是一次性把整份历史拉回手机。
     *
     * @param fromMillis 起始时间（含）
     * @param toMillis   结束时间（不含）
     * @param types      只恢复这些记录类型；空集合表示不过滤
     * @return 本次写入本地的记录条数
     */
    suspend fun restoreRange(
        fromMillis: Long,
        toMillis: Long,
        types: Set<com.guolongli.dinner.domain.model.MealType> = emptySet(),
    ): Result<Int>

    /** 解除授权前必须先取消同步任务，不得在 token 失效或意图不明时继续上传。 */
    suspend fun cancelAll()

    /**
     * 预取最近一段时间的记录与媒体到本机。
     *
     * 语义：**记录与元数据全部拉回**，媒体按类型区别对待——
     * - 图片：全部下载，时间线直接能看，不用点开再等；
     * - 视频：**只拉封面**，几十 MB 的正片留给用户点开时再下，
     *   否则一屏十条视频就把手机流量与开发期 100 次/小时的接口配额一起烧光。
     *
     * 幂等：已缓存的不重复下载。失败只记数不中断，返回值如实回报成功/失败多少。
     */
    suspend fun prefetchRecent(days: Int = PREFETCH_DAYS): Result<PrefetchOutcome>
}

/** 预取结果。UI 要如实展示，不能只说「已优化」。 */
data class PrefetchOutcome(
    val days: Int = PREFETCH_DAYS,
    val records: Int = 0,
    val imagesDownloaded: Int = 0,
    val coversDownloaded: Int = 0,
    val failed: Int = 0,
) {
    val message: String
        get() = buildString {
            append("近 $days 天已同步 $records 条记录")
            if (imagesDownloaded > 0) append("，$imagesDownloaded 张图片已就绪")
            if (coversDownloaded > 0) append("，$coversDownloaded 个视频封面已就绪")
            if (failed > 0) append("，$failed 项待稍后重试")
        }
}
