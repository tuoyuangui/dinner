package com.guolongli.dinner.data.remote

import com.guolongli.dinner.core.SyncConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 频控与瞬时故障的退避重试。
 *
 * 频控错误码 20012 必须指数退避重试（初始 1s，最多 3 次）。
 * 超过次数后抛出，绝不做无限重试。
 */
suspend fun <T> withRetry(
    maxAttempts: Int = SyncConfig.RETRY_MAX_ATTEMPTS,
    initialDelayMs: Long = SyncConfig.RETRY_INITIAL_DELAY_MS,
    tag: String = "request",
    block: suspend () -> T,
): T {
    var attempt = 0
    var delayMs = initialDelayMs
    var lastError: Throwable? = null

    while (attempt < maxAttempts) {
        attempt += 1
        try {
            return block()
        } catch (e: DinnerException.FrequencyLimited) {
            lastError = e
            Logger.w("$tag 触发频控(20012)，第 $attempt 次退避 ${delayMs}ms")
        } catch (e: DinnerException.NetworkUnavailable) {
            lastError = e
            Logger.w("$tag 网络不可用，第 $attempt 次退避 ${delayMs}ms")
        } catch (e: IOException) {
            lastError = e
            Logger.w("$tag IO 异常，第 $attempt 次退避 ${delayMs}ms")
        }

        if (attempt >= maxAttempts) break
        delay(delayMs)
        delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
    }

    throw (lastError ?: DinnerException.Local("$tag 重试次数耗尽"))
}

private const val MAX_DELAY_MS = 30_000L
