package com.guolongli.dinner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.guolongli.dinner.feature.about.AboutScreen
import com.guolongli.dinner.feature.auth.AuthScreen
import com.guolongli.dinner.feature.browse.RecordFilter
import com.guolongli.dinner.feature.browse.RecordListScreen
import com.guolongli.dinner.feature.calendar.CalendarScreen
import com.guolongli.dinner.feature.detail.RecordDetailScreen
import com.guolongli.dinner.feature.edit.EditScreen
import com.guolongli.dinner.feature.group.GroupScreen
import com.guolongli.dinner.feature.mine.MineScreen
import com.guolongli.dinner.feature.pairing.PairingScreen
import com.guolongli.dinner.feature.timeline.TimelineScreen
import com.guolongli.dinner.ui.navigation.DinnerRoute
import com.guolongli.dinner.ui.theme.DinnerDimens

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TOP_DESTS = listOf(
    TopDest(DinnerRoute.TIMELINE, "时间线", Icons.Default.LunchDining),
    TopDest(DinnerRoute.MINE, "我的", Icons.Default.AccountCircle),
)

/**
 * 应用根：导航 + 底部导航栏。
 *
 * 主题不在这里套——由 MainActivity 的 setContent 统一提供一次，
 * 否则与应用锁闸门叠成三层，解锁瞬间整棵树重建会闪一下白。
 */
@Composable
fun DinnerApp() {
    val navController = rememberNavController()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val showBottom = currentRoute in DinnerRoute.TOP_LEVEL
            AnimatedVisibility(
                visible = showBottom,
                enter = fadeIn(tween(DinnerDimens.animStandardMs)) + slideInVertically(
                    animationSpec = tween(DinnerDimens.animStandardMs),
                    initialOffsetY = { it },
                ),
                exit = fadeOut(tween(DinnerDimens.animStandardMs)),
            ) {
                FloatingBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route -> navigateTop(navController, route) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DinnerRoute.TIMELINE,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(DinnerRoute.TIMELINE) {
                TimelineScreen(
                    onAddMeal = { navController.navigate(DinnerRoute.edit()) },
                    onOpenRecord = { id -> navController.navigate(DinnerRoute.detail(id)) },
                    onOpenCalendar = { navController.navigate(DinnerRoute.CALENDAR) },
                    onOpenGroup = { navController.navigate(DinnerRoute.GROUP) },
                )
            }
            composable(DinnerRoute.MINE) {
                MineScreen(
                    onOpenAuth = { navController.navigate(DinnerRoute.AUTH) },
                    onOpenPairing = { navController.navigate(DinnerRoute.PAIRING) },
                    onOpenGroup = { navController.navigate(DinnerRoute.GROUP) },
                    onOpenAbout = { navController.navigate(DinnerRoute.ABOUT) },
                    onOpenRecords = { filter ->
                        navController.navigate(DinnerRoute.records(filter.key))
                    },
                )
            }
            composable(DinnerRoute.CALENDAR) {
                CalendarScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDay = { day ->
                        navController.navigate(
                            DinnerRoute.records(RecordFilter.DAY.key, day)
                        )
                    },
                )
            }
            composable(
                route = DinnerRoute.RECORDS_PATTERN,
                arguments = listOf(
                    navArgument(DinnerRoute.ARG_FILTER) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(DinnerRoute.ARG_DAY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                RecordListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRecord = { id -> navController.navigate(DinnerRoute.detail(id)) },
                )
            }
            composable(
                route = DinnerRoute.DETAIL_PATTERN,
                arguments = listOf(
                    navArgument(DinnerRoute.ARG_RECORD_ID) {
                        type = NavType.StringType
                    },
                ),
            ) {
                // 详情页只读，编辑必须点右上角「编辑」显式进入编辑页。
                RecordDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(DinnerRoute.edit(id)) },
                )
            }
            composable(
                route = DinnerRoute.EDIT_PATTERN,
                arguments = listOf(
                    navArgument(DinnerRoute.ARG_RECORD_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                EditScreen(onBack = { navController.popBackStack() })
            }
            composable(DinnerRoute.PAIRING) {
                PairingScreen(onBack = { navController.popBackStack() })
            }
            composable(DinnerRoute.GROUP) {
                GroupScreen(onBack = { navController.popBackStack() })
            }
            composable(DinnerRoute.AUTH) {
                // 授权页由百度 OAuth SDK 承载；code 从 Activity 的 onActivityResult
                // 经 ActivityOauthLauncher 回到 ViewModel，不再走深链参数。
                AuthScreen(
                    onBack = { navController.popBackStack() },
                    onAuthed = { navController.popBackStack() },
                )
            }
            composable(DinnerRoute.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * 悬浮式底部导航。
 *
 * 比贴底的 NavigationBar 更轻：左右留白 + 大圆角 + 细描边，
 * 选中项用主色容器做胶囊背景，视觉上"浮"在内容之上。
 */
@Composable
private fun FloatingBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = DinnerDimens.screenPadding,
                vertical = DinnerDimens.spaceXs,
            ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(DinnerDimens.bottomBarHeight),
            shape = RoundedCornerShape(DinnerDimens.radiusFloating),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = DinnerDimens.elevationFloating,
            shadowElevation = DinnerDimens.elevationFloating,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DinnerDimens.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
            ) {
                TOP_DESTS.forEach { dest ->
                    BottomBarItem(
                        dest = dest,
                        selected = currentRoute == dest.route,
                        onClick = { onSelect(dest.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 底部导航的一项。
 *
 * 两个刻意的选择：
 * - **文字始终渲染**：只有选中项渲染文字时，weight(1f) 下未选中项会变窄、
 *   选中项变宽，切 tab 时整条栏左右抖一下。选中与否只靠容器色与文字色表达。
 * - **合并节点上补 contentDescription**：图标是装饰（contentDescription = null），
 *   不补的话读屏在这一项上什么都念不出来。
 */
@Composable
private fun BottomBarItem(
    dest: TopDest,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primaryContainer else Color.Transparent,
        animationSpec = tween(DinnerDimens.animStandardMs),
        label = "bottomItemContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        animationSpec = tween(DinnerDimens.animStandardMs),
        label = "bottomItemContent",
    )

    Row(
        modifier = modifier
            .height(DinnerDimens.touchTargetMin)
            .clip(RoundedCornerShape(DinnerDimens.radiusFloating))
            .background(container)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                this.contentDescription = dest.label
            }
            .padding(horizontal = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = dest.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(DinnerDimens.iconMd),
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
        Text(
            text = dest.label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}

private fun navigateTop(navController: NavHostController, route: String) {
    navController.navigate(route) {
        // 顶层目的地只保留一个实例；popUpTo 到起始目的地。
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
