package com.guolongli.dinner

import android.os.Looper
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 启动冒烟：Activity 能不能立起来、Compose 有没有真的挂上去、首帧有没有炸。
 *
 * 这一条专门守「打开一片空白」这类问题——它不检查界面长什么样，但会在
 * `Graph` 装配、WorkManager 排期、Compose 组合任何一处抛异常时把堆栈亮出来：
 * - `setup()` 覆盖 Application.onCreate，WorkManager 那类「启动即崩」在这里现形；
 * - `idle()` 把主线程上排队的组合任务跑完，组合期抛的异常不会被吞掉。
 *   能跑完本身也说明组合能收敛，不存在「无限重组把界面卡死」。
 *
 * 视图尺寸不在这里断言：Robolectric 不做真实 layout，`width` 恒为 0，
 * 拿它当「有没有画面」的证据只会误报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivitySmokeTest {

    @Test
    fun `启动后 Compose 内容已经挂到窗口上且首帧没有抛异常`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Compose 的首帧是在消息队列里排的，不跑完等于什么都没测
        shadowOf(Looper.getMainLooper()).idle()

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("content 里应该有 ComposeView", content.childCount > 0)

        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `中文环境下应用名是食伴`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("食伴", controller.get().getString(R.string.app_name))
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "en")
    fun `英文环境下应用名是 dinner`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("dinner", controller.get().getString(R.string.app_name))
        controller.pause().stop().destroy()
    }
}
