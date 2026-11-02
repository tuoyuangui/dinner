package com.guolongli.dinner.data.sync

/**
 * 合并规则单独拎出来，因为它是最容易被改坏、也最值得测的一段：
 * 判断「这份远端文件该不该覆盖本地」，只依赖三个数，不依赖数据库。
 *
 * - 本地没有 → 收下
 * - 本地更新 → 不要（STALE），顺带省掉一次下载
 * - 时间相同但本地还有没传出去的改动 → 保留本地，等下次推上去
 * - 时间相同且本地没有待传改动 → 一模一样，什么都不做
 */
object MergePolicy {

    fun decide(
        localUpdatedAt: Long?,
        localDirty: Boolean,
        remoteUpdatedAt: Long,
    ): ApplyResult = when {
        localUpdatedAt == null -> ApplyResult.APPLIED
        localUpdatedAt > remoteUpdatedAt -> ApplyResult.STALE
        localUpdatedAt == remoteUpdatedAt ->
            if (localDirty) ApplyResult.STALE else ApplyResult.UNCHANGED
        else -> ApplyResult.APPLIED
    }
}
