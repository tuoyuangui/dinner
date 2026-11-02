package com.guolongli.dinner.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 这次同步是被谁叫起来的，决定要不要受「最小间隔」约束 */
enum class SyncReason {
    /** 应用回到前台 */
    STARTUP,

    /** 刚写完一条记录 / 评论 / 删除 */
    DATA_CHANGED,

    /** 断网恢复 */
    NETWORK,

    /** 后台周期任务 */
    PERIODIC,

    /** 用户点了「立即同步」 */
    MANUAL,

    /** 刚授权成功 */
    AUTH,
}

/** 界面直接渲染这个，不用自己拼状态 */
data class SyncUiState(
    val running: Boolean = false,
    val authorized: Boolean = true,
    val lastAt: Long = 0L,
    val summary: String = "",
    val pending: Int = 0,
    val errors: List<String> = emptyList(),
    val trigger: SyncReason? = null,
) {
    /** 「我的」页那行状态文字 */
    val statusText: String
        get() = when {
            running -> "同步中…"
            !authorized -> "未授权，数据只存在本机"
            lastAt == 0L -> "还没同步过"
            pending > 0 -> "$pending 条待上传"
            // 带上第一条失败原因：「上次有 1 条没传成」这句话本身没法让人做任何事，
            // 而原因（目录不存在 / 参数有误 / 容量不足）才是用户需要的线索。
            errors.isNotEmpty() -> "上次有 ${errors.size} 条没传成：" +
                errors.first().take(60)
            else -> summary.ifBlank { "已是最新" }
        }
}

/**
 * 自动同步的协调器。它只做三件事：
 *
 * 1. **合并**：连续写三条记录不该触发三次同步。正在跑的时候再来的请求记一笔，
 *    跑完再补一轮（保证最后一次改动一定被推上去），而不是排队排三条。
 * 2. **限流**：自动触发（启动 / 写入 / 断网恢复）在 `minGapMs` 内只执行一次；
 *    手动同步和刚授权后的首次同步不受限制。
 * 3. **记账**：结果写进 SyncPrefs，界面和后台任务共用。
 *
 * 真正的同步规则在 SyncEngine 里，这里不掺业务判断。
 */
class AutoSync(
    private val engine: SyncEngine,
    private val prefs: SyncPrefs,
    private val authorized: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 同步结束后回调（Android 端用来重排后台任务） */
    private val onChange: () -> Unit = {},
    /** 一轮同步跑完之后要做的事（补齐头像等），失败不影响同步结果 */
    private val afterSync: suspend () -> Unit = {},
    /** 两次自动同步之间的最小间隔 */
    private val minGapMs: Long = 8_000L,
    /** 启动 / 断网恢复这类被动触发，最近刚同步过就跳过 */
    private val freshMs: Long = 60_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val gate = Mutex()

    /** 跑的过程中又来了请求，跑完补一轮 */
    private var rerun: SyncReason? = null

    /** 被「最小间隔」挡下来的那次补救，只留一个，避免排队排成串 */
    private var deferred: Job? = null

    /** 是否已经有一次「间隔后补跑」在排队（与 job.isActive 区分开，见 [scheduleDeferred]） */
    private var deferredPending = false

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** 从持久化的设置里还原一次状态，冷启动时界面不会显示成「还没同步过」 */
    private fun restore(): SyncUiState = SyncUiState(
        running = false,
        authorized = authorized(),
        lastAt = prefs.lastSyncAt,
        summary = prefs.lastSummary,
        errors = prefs.lastError.split(ERROR_SEPARATOR).filter { it.isNotBlank() },
        pending = 0,
    )

    /** 后台 / 界面都可以直接调，不阻塞、不抛异常 */
    fun request(reason: SyncReason) {
        if (!authorized()) {
            _state.value = _state.value.copy(authorized = false)
            return
        }
        scope.launch { run(reason) }
    }

    /**
     * 跑一次同步。返回 null 表示这次没跑（没授权 / 关掉了自动同步 / 离上次太近）。
     *
     * @param force 无视间隔与开关，用户手动点的场景
     */
    suspend fun run(reason: SyncReason, force: Boolean = false): SyncReport? {
        if (!authorized()) {
            _state.value = _state.value.copy(authorized = false, running = false)
            return null
        }
        val manual = force || reason == SyncReason.MANUAL || reason == SyncReason.AUTH
        if (!manual) {
            if (!prefs.autoEnabled) return null
            if (skipByGap(reason)) {
                // 不能直接丢掉：刚写完一条记录、离上次同步只差两秒的情况非常常见。
                // 丢掉的话这条记录会一直躺在发件箱里，等下一个周期任务（十几分钟后）
                // 或者等用户手动点同步——表现就是「记了东西，别人那边一直看不到」。
                if (reason == SyncReason.DATA_CHANGED) scheduleDeferred()
                return null
            }
        }
        // 已经在跑：记下这一轮结束了要补跑
        if (!gate.tryLock()) {
            rerun = reason
            return null
        }
        return try {
            var last: SyncReport? = null
            // 每轮开始前先合并「并发期间又进来的请求」(rerun)，这样 gate 释放前
            // 最后时刻进来的请求也不会丢——之前只在循环外读一次 reason，存在竞态窗口。
            do {
                val next = rerun ?: reason
                rerun = null
                last = execute(next)
            } while (rerun != null)
            last
        } finally {
            gate.unlock()
        }
    }

    /**
     * 等最小间隔过去，再补跑一次。
     *
     * 只留一个：连续写五条记录不该排出五次同步，最后一次就够了——
     * 同步引擎一轮会把发件箱里所有待办都推出去。
     *
     * 用独立的 `deferredPending` 标记「已有一个补跑在排队」，而不是看 job.isActive：
     * 否则补跑任务自己跑起来后、又因为间隔没到被挡下时，会发现「自己还 active」而放弃
     * 再次排程，于是这条本该最终传上去的记录就一直躺在发件箱里，只能等手动同步。
     */
    private fun scheduleDeferred() {
        if (deferredPending) return
        deferredPending = true
        val since = now() - prefs.lastSyncAt
        val wait = (minGapMs - since).coerceIn(200L, minGapMs)
        deferred = scope.launch {
            delay(wait)
            // 进入执行前先把标记清掉：这样就算这次仍被间隔挡下、需要再排一次，
            // 也允许它重新 schedule，不会把自己卡死。
            deferredPending = false
            run(SyncReason.DATA_CHANGED)
        }
    }

    private fun skipByGap(reason: SyncReason): Boolean {
        val since = now() - prefs.lastSyncAt
        if (prefs.lastSyncAt == 0L) return false
        return when (reason) {
            SyncReason.DATA_CHANGED -> since < minGapMs
            SyncReason.STARTUP, SyncReason.NETWORK -> since < freshMs
            SyncReason.PERIODIC -> false
            SyncReason.MANUAL, SyncReason.AUTH -> false
        }
    }

    private suspend fun execute(reason: SyncReason): SyncReport {
        _state.value = _state.value.copy(running = true, authorized = true, trigger = reason)
        val report = try {
            engine.sync()
        } catch (e: Exception) {
            // 引擎内部已经把可预期的错误收进 report 了，走到这里基本是意外
            SyncReport(
                at = now(),
                errors = listOf(e.message ?: e::class.java.simpleName),
                pending = runCatching { engine.pendingCount() }.getOrDefault(0),
            )
        }
        save(report)
        // 同步之后的补充动作（补齐头像、补齐他人推文的图片视频等）。
        //
        // **不看 report.ok**：这些动作跟「本机有没有东西传失败」无关。
        // 以前写成 `if (report.ok)`，结果一条记录上传失败就把整块跳过了——
        // 而「别人推文的照片视频自动下载」恰恰是用户最需要它工作的场景，
        // 偏偏被自己的一条失败记录挡住了。
        //
        // 每一项自己吞掉异常：补头像失败不该连累补媒体。
        runCatching { afterSync() }
        _state.value = SyncUiState(
            running = false,
            authorized = !report.authRequired,
            lastAt = report.at,
            summary = report.summary(),
            pending = report.pending,
            errors = report.errors,
            trigger = reason,
        )
        onChange()
        return report
    }

    private fun save(report: SyncReport) {
        prefs.lastSyncAt = report.at
        prefs.lastSummary = report.summary()
        prefs.lastError = report.errors.joinToString(ERROR_SEPARATOR)
        prefs.lastAuthRequired = report.authRequired
    }

    /**
     * 只刷授权标志，不同步。
     *
     * 刚在网盘页授权成功回到界面时，state 里可能还挂着「未授权」——
     * 那是上次 run() 留下的旧值。不刷的话「我的」页会一直显示未授权，
     * 用户以为授权失败又去点一遍。
     */
    fun refreshAuthorized() {
        _state.value = _state.value.copy(authorized = authorized())
    }

    /** 界面进来先刷一次「待传条数」 */
    fun refreshPending() {
        scope.launch {
            val count = runCatching { engine.pendingCount() }.getOrDefault(0)
            _state.value = _state.value.copy(pending = count)
        }
    }

    companion object {
        private const val ERROR_SEPARATOR = " ; "
    }
}
