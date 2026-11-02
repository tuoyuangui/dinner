package com.guolongli.dinner.data.auth

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guolongli.dinner.core.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 授权回调的中转站：**浏览器怎么把 code 交回应用**，全部收在这里。
 *
 * 这里刻意不碰浏览器、不碰 Activity：
 * - [open] 交给实现方（系统浏览器）打开授权页；
 * - [handleCallback] 由 `MainActivity` 在收到 `dinner://oauth/callback?code=…` 时调用；
 * - [awaitCallback] 让等待方挂起，直到回调到达。
 *
 * **为什么 state 要落盘**（实机踩出来的）：用户点「开始授权」→ 跳去浏览器 →
 * 登录、选目录、点确认，这中间系统完全可能把应用进程回收掉。
 * 早先 state 只存在内存里，进程一没，回调回来时等待点已经消失：
 * 界面停在那里、不报错也不成功，日志里连「收到授权回调」都没有。
 * 所以这里把「本次授权的 state」写进 DataStore，冷启动
 * [MainActivity.onCreate] 收到回调时也能认出来并接住。
 *
 * 只存两样东西：state 与发起时间。不存 code、不存 token。
 */
private val Context.oauthStateStore: DataStore<Preferences> by preferencesDataStore(
    name = "dinner_oauth_state"
)

@Singleton
class OauthCallbackBroker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val browser: ExternalBrowserLauncher,
) {

    private object Keys {
        val pendingState = stringPreferencesKey("pending_state")
        val pendingAt = stringPreferencesKey("pending_at")
    }

    private val waiting = AtomicReference<CancellableContinuation<Result<String>>?>(null)

    /** 浏览器打开授权页。返回 false 表示设备上没有可用浏览器。 */
    fun open(url: String): Boolean = browser.open(url)

    /** 记下本次授权的 state（落盘），供回调校验与进程重建后恢复。 */
    suspend fun rememberState(state: String) {
        context.oauthStateStore.edit { prefs ->
            prefs[Keys.pendingState] = state
            prefs[Keys.pendingAt] = System.currentTimeMillis().toString()
        }
    }

    /** 读取仍在有效期内的待处理 state。 */
    suspend fun pendingState(): String? {
        val prefs = context.oauthStateStore.data.first()
        val state = prefs[Keys.pendingState]?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs[Keys.pendingAt]?.toLongOrNull() ?: 0L
        // 授权码 10 分钟内有效；超过就别再当成「进行中」，免得把过期 code 拿去换 token。
        if (System.currentTimeMillis() - at > PENDING_TTL_MS) {
            forgetState()
            return null
        }
        return state
    }

    suspend fun forgetState() {
        context.oauthStateStore.edit { prefs ->
            prefs.remove(Keys.pendingState)
            prefs.remove(Keys.pendingAt)
        }
    }

    /**
     * 挂起等待回调返回的 code。
     *
     * @param expectedState 发起授权时的 state；回调里的 state 必须与它逐字相同
     */
    suspend fun awaitCallback(expectedState: String): Result<String> =
        suspendCancellableCoroutine { continuation ->
            waiting.set(continuation)
            continuation.invokeOnCancellation { waiting.set(null) }
        }

    /**
     * 处理回调 URL。
     *
     * @return 是否消费了这个回调
     */
    fun handleCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errno = uri.getQueryParameter("errno")
        val errmsg = uri.getQueryParameter("errmsg")

        // state 必须与本次授权登记的值一致；空 state 一律拒绝。
        val expected = runBlocking { pendingState() }
        if (expected == null || state != expected) {
            Logger.w("授权回调的 state 与登记的待处理值不符，忽略（可能是过期链接）")
            return false
        }

        val result: Result<String> = when {
            // 用户主动取消：单独说清楚，不要糊成「失败」。
            error == "access_denied" -> Result.failure(UserCancelledException())
            // 平台返回的错误码：如实展示 errno / errmsg。
            !errno.isNullOrBlank() -> Result.failure(
                OauthCallbackException("授权失败（errno=$errno）：${errmsg.orEmpty()}")
            )
            code.isNullOrBlank() -> Result.failure(
                OauthCallbackException("授权回调缺少 code")
            )
            else -> Result.success(code)
        }
        Logger.i("收到授权回调：hasCode=${!code.isNullOrBlank()}")

        // 无论成功失败，这次授权都结束了，把落盘的 state 清掉。
        runBlocking { forgetState() }

        val continuation = waiting.getAndSet(null)
        if (continuation == null) {
            // 进程重建过、当前没有人等待：把结果放回来，供冷启动路径取值。
            lastResult.set(result)
            return true
        }
        continuation.resume(result)
        return true
    }

    /** 冷启动路径：取出「回调先到、界面后到」时暂存的结果。 */
    fun consumeLateResult(): Result<String>? = lastResult.getAndSet(null)

    /** 当前是否有等待中的授权。 */
    fun hasPending(): Boolean = waiting.get() != null

    /** 主动放弃等待（页面退出时调用），避免协程永远挂着。 */
    fun cancelPending(reason: Throwable = OauthCallbackException("授权已取消")) {
        val continuation = waiting.getAndSet(null) ?: return
        continuation.resume(Result.failure(reason))
    }

    private companion object {
        /** 与文档「code 10 分钟内有效」对齐。 */
        const val PENDING_TTL_MS = 10 * 60 * 1000L

        /**
         * 回调先到、等待者后到时暂存一次结果。
         *
         * 只保留最近一次，用掉即清；不留 code、不落盘。
         */
        val lastResult = AtomicReference<Result<String>?>(null)
    }
}

/** 用户在授权页点了「取消」。 */
class UserCancelledException : RuntimeException("已取消授权")

/** 授权回调里的其它失败（errno / 缺 code 等）。 */
class OauthCallbackException(message: String) : RuntimeException(message)
