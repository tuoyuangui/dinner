package com.guolongli.dinner.ui.util

import kotlinx.coroutines.CancellationException

/**
 * 与 [runCatching] 等价的挂起版本，但绝不吞掉协程取消。
 *
 * 普通 runCatching 会把 CancellationException 也包成 failure，
 * 导致 ViewModel 清除时留下悬挂的状态更新，这里显式重抛。
 */
internal suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(throwable)
}
