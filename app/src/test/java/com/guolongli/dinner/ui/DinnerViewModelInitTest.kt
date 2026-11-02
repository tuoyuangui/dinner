package com.guolongli.dinner.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.guolongli.dinner.core.Graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 只测「首屏依赖能不能建起来」这一层，不碰界面。
 *
 * 用户说「连界面都进不去」时，可能性只有两类：要么依赖建不起来直接崩，
 * 要么建起来了但界面空。这一条把第一类单独拎出来——`DinnerViewModel` 的构造
 * 会连锁初始化 Graph → Room → 仓储 → 自动同步，任何一环抛异常都会在这里炸，
 * 而不是等到真机上只看到一块白屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DinnerViewModelInitTest {

    @Test
    fun `ViewModel 能构造，数据流能读到第一帧`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Graph.init(app)

        val vm = DinnerViewModel(app)

        // 默认没开应用锁，首屏不该先弹锁屏
        assertFalse("全新安装不该自动上锁", vm.locked)
        assertTrue("默认显示名不该是空的", vm.displayName.isNotBlank())

        // 时间线的第一帧：哪怕库里是空的，也必须能正常返回而不是抛异常。
        // 没调用 onStart()，所以示例数据还没种下去，这里应该是空列表。
        runBlocking {
            val records = vm.records.first()
            assertTrue("首帧应该是空列表", records.isEmpty())
            assertTrue("统计文案不该是 null", vm.summary.first() != null)
        }
    }
}
