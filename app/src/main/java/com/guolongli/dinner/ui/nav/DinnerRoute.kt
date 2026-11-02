package com.guolongli.dinner.ui.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.guolongli.dinner.ui.component.MainTab
import com.guolongli.dinner.ui.model.StatScope

/**
 * 全部目的地。用密封接口而不是字符串路由：
 * 参数类型编译期就能检查，改签名时不会漏掉某个调用点。
 */
sealed interface DinnerRoute {

    /** 底部导航的两个根 */
    data object Timeline : DinnerRoute

    data object Mine : DinnerRoute

    // ---- 二级页面，都压在根之上 ----

    data object Calendar : DinnerRoute

    data object Group : DinnerRoute

    data object Netdisk : DinnerRoute

    data object About : DinnerRoute

    /** 新建一条记录。记录写下来就不再改（改的是星标与评论），所以没有带 id 的编辑页 */
    data object Edit : DinnerRoute

    data class Detail(val recordId: String) : DinnerRoute

    /** 「我的」顶部三个数字点进来的明细列表 */
    data class Browse(val scope: StatScope) : DinnerRoute
}

/** 根路由对应的 tab；二级页面返回 null */
val DinnerRoute.tab: MainTab?
    get() = when (this) {
        DinnerRoute.Timeline -> MainTab.TIMELINE
        DinnerRoute.Mine -> MainTab.MINE
        else -> null
    }

/**
 * 一个够用的返回栈。
 *
 * 应用只有「底部两个根 + 若干二级页」这一种结构，引入 Navigation Compose
 * 需要多一层依赖和一套字符串路由，收益不足以抵消成本；这里用列表栈，
 * 行为与系统返回键、深色遮罩动画都对得上。
 */
@Stable
class Navigator(start: DinnerRoute = DinnerRoute.Timeline) {

    private val stack = mutableStateListOf(start)

    var current: DinnerRoute by mutableStateOf(start)
        private set

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: DinnerRoute) {
        if (route == current) return
        stack.add(route)
        current = route
    }

    /** 返回上一页；已经在根上就返回 false，交给系统处理（退出应用） */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        current = stack.last()
        return true
    }

    /**
     * 切 tab。
     *
     * 点当前 tab 视为「回到这个 tab 的根」——这是各家 App 的通行手感，
     * 也顺便给用户一个从深层页面快速脱身的出口。
     */
    fun switchTab(tab: MainTab) {
        val target: DinnerRoute = when (tab) {
            MainTab.TIMELINE -> DinnerRoute.Timeline
            MainTab.MINE -> DinnerRoute.Mine
        }
        if (stack.first() == target) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            stack.clear()
            stack.add(target)
        }
        current = stack.last()
    }

    /**
     * 保存成功后回到某个页面（例如从「新建」回时间线），避免返回时又回到表单。
     *
     * 栈里有这个目的地：退回到它，把压在它上面的页面丢掉。
     * 栈里没有（比如这次是从别的 tab 进来的）：把它当根页面，整栈换成它——
     * 否则中间那些页面会留在返回栈里，返回键退过去时看到的是一份已经过期的界面。
     */
    fun popTo(route: DinnerRoute) {
        val index = stack.indexOfLast { it == route }
        if (index >= 0) {
            while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
        } else {
            stack.clear()
            stack.add(route)
        }
        current = stack.last()
    }
}
