package com.guolongli.dinner.domain.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [SyncOutcome.isSuccess] 语义：不得「假装成功」。
 *
 * 回归用例：当同步因未授权 / 已取消而跳过（skipped=true）时，即使 failed==0，
 * 也必须视为未成功，否则 UI 会在没做任何同步的情况下显示「同步完成」。
 */
class SyncOutcomeTest {

    @Test
    fun `上传失败时不是成功`() {
        val outcome = SyncOutcome(uploaded = 2, failed = 1)
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.isPartial)
    }

    @Test
    fun `全部成功且未跳过是成功`() {
        val outcome = SyncOutcome(uploaded = 3, failed = 0)
        assertTrue(outcome.isSuccess)
        assertFalse(outcome.isPartial)
    }

    @Test
    fun `跳过但失败为零仍然不是成功`() {
        val outcome = SyncOutcome(uploaded = 0, failed = 0, skipped = true)
        assertFalse(outcome.isSuccess)
        assertFalse(outcome.isPartial)
    }

    @Test
    fun `取消导致的跳过同样不得视为成功`() {
        val outcome = SyncOutcome(uploaded = 1, failed = 0, skipped = true)
        assertFalse(outcome.isSuccess)
    }

    /**
     * 回归：记录全传上去了，但 `records.json` 快照 / `members.json` 名单没写成功。
     *
     * 这种情况 [SyncOutcome.failed] 是 0，早先 [SyncOutcome.isSuccess] 会给出 true，
     * 而别人那边**什么都看不到**——快照没发布，等于这一轮白同步。
     *
     * 后果最重的一处是 `TimelineViewModel.deleteBoth`：它拿 `outcome.isSuccess`
     * 决定要不要 `hardDelete` 本地行。这里说谎就会在「云端删除意图没发出去」时
     * 抹掉本地行，下一轮同步又把云端那条拉回来——用户看到「删了又自己冒出来」，
     * 而且本地已经没有重试入口。
     */
    @Test
    fun `上传全成但快照没发出去也不算成功`() {
        val outcome = SyncOutcome(
            uploaded = 3,
            failed = 0,
            incomplete = true,
            message = "记录已上传，但同步快照写入失败：网络不可用",
        )
        assertFalse("快照没发出去却被判成功", outcome.isSuccess)
        // 这不是「部分失败」——一条记录都没传失败，文案不能写成「N 条上传失败」。
        assertFalse(outcome.isPartial)
        assertTrue("失败原因必须带出来", outcome.message!!.contains("快照"))
    }

    @Test
    fun `上传全成且快照发布成功才算成功`() {
        val outcome = SyncOutcome(uploaded = 3, failed = 0)
        assertTrue(outcome.isSuccess)
        assertFalse(outcome.incomplete)
    }
}