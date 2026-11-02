package com.guolongli.dinner.ui.nav

import com.guolongli.dinner.ui.component.MainTab
import com.guolongli.dinner.ui.model.StatScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回栈。这是全应用唯一一处「用户按了返回会去哪」的判定，
 * 写错了表现是「返回键失灵」或「退出应用」，都属于一旦发布就很难受的 bug。
 */
class NavigatorTest {

    @Test
    fun `根页面不能再返回，交给系统退出`() {
        val nav = Navigator()

        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
        assertEquals(DinnerRoute.Timeline, nav.current)
    }

    @Test
    fun `压栈之后能逐层退回`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Calendar)
        nav.push(DinnerRoute.Edit)

        assertTrue(nav.canGoBack)
        assertTrue(nav.pop())
        assertEquals(DinnerRoute.Calendar, nav.current)
        assertTrue(nav.pop())
        assertEquals(DinnerRoute.Timeline, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `重复压同一个页面不会堆出两层`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Edit)
        nav.push(DinnerRoute.Edit)

        assertTrue(nav.pop())
        assertEquals(DinnerRoute.Timeline, nav.current)
    }

    @Test
    fun `切到另一个 tab 时清空二级页面`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Calendar)
        nav.switchTab(MainTab.MINE)

        assertEquals(DinnerRoute.Mine, nav.current)
        assertFalse("换 tab 之后返回键应该是退出，而不是回到上一个 tab 的二级页", nav.canGoBack)
    }

    @Test
    fun `再点当前 tab 视为回到这个 tab 的根`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Calendar)
        nav.push(DinnerRoute.Edit)
        nav.switchTab(MainTab.TIMELINE)

        assertEquals(DinnerRoute.Timeline, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `保存成功后回到时间线，返回键不该再回到表单`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Edit)
        nav.push(DinnerRoute.Detail("r1"))
        nav.popTo(DinnerRoute.Timeline)

        assertEquals(DinnerRoute.Timeline, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `popTo 的目标不在栈里时当成新的根，中间页面不再留在返回栈`() {
        val nav = Navigator(DinnerRoute.Mine)
        nav.push(DinnerRoute.About)
        nav.popTo(DinnerRoute.Browse(StatScope.MINE))

        assertEquals(DinnerRoute.Browse(StatScope.MINE), nav.current)
        assertFalse("返回键应该退出应用，而不是退到过期的中间页", nav.canGoBack)
    }

    @Test
    fun `明细页带着 scope 参数，同一个页面不同参数算不同的目的地`() {
        val nav = Navigator()
        nav.push(DinnerRoute.Browse(StatScope.ALL))
        nav.push(DinnerRoute.Browse(StatScope.MINE))

        assertTrue(nav.pop())
        assertEquals(DinnerRoute.Browse(StatScope.ALL), nav.current)
    }
}
