package com.guolongli.dinner

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.core.CrashGuard
import com.guolongli.dinner.core.Graph
import com.guolongli.dinner.data.SettingsStore
import com.guolongli.dinner.feature.about.AboutScreen
import com.guolongli.dinner.feature.browse.RecordListScreen
import com.guolongli.dinner.feature.calendar.CalendarScreen
import com.guolongli.dinner.feature.crash.CrashReportScreen
import com.guolongli.dinner.feature.detail.RecordDetailScreen
import com.guolongli.dinner.feature.edit.EditScreen
import com.guolongli.dinner.feature.group.GroupScreen
import com.guolongli.dinner.feature.lock.AppLockScreen
import com.guolongli.dinner.feature.mine.MineScreen
import com.guolongli.dinner.feature.netdisk.NetdiskScreen
import com.guolongli.dinner.feature.timeline.TimelineScreen
import com.guolongli.dinner.ui.DinnerViewModel
import com.guolongli.dinner.ui.nav.DinnerRoute
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.theme.PageBackground

/** README 的硬约束：一个群最多 10 位成员 */
private const val MEMBER_LIMIT = 10

/**
 * 唯一 Activity，只做三件事：装配依赖、设置系统栏、把 Compose 树挂上去。
 *
 * 上一版把六屏分页、全部状态、全部业务都写在这个文件的 `DinnerRoot()` 里，
 * 结果一个 461 行的函数承担了导航 + 状态 + 权限 + 存储。现在拆成：
 * [DinnerApp] 只负责「路由 → 页面」的映射，状态在 [DinnerViewModel]，
 * 业务在仓储与同步引擎里。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 依赖在进入界面前装配好，后面每个屏幕都从 Graph 取仓储
        Graph.init(this)
        // 画到系统栏下面（各页面自己让开），浅色界面配深色图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(ANDROID_TRANSPARENT, ANDROID_TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(ANDROID_TRANSPARENT, ANDROID_TRANSPARENT),
        )
        // ViewModel 是在这一步建的。它连锁拉起 Graph → Room → 仓储 → 自动同步，
        // 任何一环失败（库打不开、prefs 损坏）都会抛出来。抛在 setContent 之前，
        // 用户看到的就是一块白底 + 进程挂掉——所以先接着，失败了把原因画到屏幕上。
        val vmResult = runCatching { ViewModelProvider(this)[DinnerViewModel::class.java] }

        setContent {
            // 主题：设置里存的是「浅色 / 深色 / 跟随系统」；跟随时读系统深色模式。
            // themeMode 是可观察状态，用户在「外观」里切一下，这里当场重算
            val systemDark = isSystemInDarkTheme()
            val themeMode = vmResult.getOrNull()?.themeMode ?: Graph.settings.themeMode
            val dark = when (themeMode) {
                SettingsStore.THEME_DARK -> true
                SettingsStore.THEME_LIGHT -> false
                else -> systemDark
            }
            DinnerTheme(dark = dark) {
                // 状态栏图标跟着主题走：浅色主题用深色图标，深色主题用浅色图标
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as? Activity)?.window ?: return@SideEffect
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
                    }
                }
                // 上一次启动如果崩了，先把原因摆出来，而不是让用户对着白屏猜。
                // 这里同步读文件是有意的：崩溃记录只有几 KB，异步读反而会先闪一下
                // 正常界面再跳到报告页，看起来像又一次故障。
                var crashReport by remember { mutableStateOf(CrashGuard.lastReport(this)) }
                val report = crashReport
                val vm = vmResult.getOrNull()
                when {
                    report != null -> CrashReportScreen(
                        report = report,
                        onContinue = {
                            CrashGuard.clear(this)
                            crashReport = null
                        },
                    )

                    vm != null -> DinnerApp(vm)

                    else -> CrashReportScreen(
                        report = Log.getStackTraceString(vmResult.exceptionOrNull()),
                        title = "初始化失败",
                        continueLabel = "退出",
                        onContinue = { finish() },
                    )
                }
            }
        }
    }

    private companion object {
        /** android.graphics.Color.TRANSPARENT，写成常量避免和 Compose 的 Color 撞名 */
        const val ANDROID_TRANSPARENT = android.graphics.Color.TRANSPARENT
    }
}

/**
 * 路由分发。
 *
 * 这里刻意不做动画：底部两个 tab 之间是「换根」，二级页面是「压栈」，
 * 两种情况的手感不一样，用一套统一转场反而都不对。等真要打磨时再按
 * 路由类型分别加，不在这一层提前抽象。
 */
@Composable
internal fun DinnerApp(vm: DinnerViewModel = viewModel()) {
    val context = LocalContext.current

    val records by vm.records.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val adminUnlocked by vm.adminUnlocked.collectAsStateWithLifecycle()

    // 一次性提示语：ViewModel 里攒着，弹完就清，转屏不会重复弹
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        vm.consumeMessage()
    }

    // 首次进入补示例数据 / 补传头像；回到前台补一次同步；进后台记一笔用于上锁
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onStart()
                Lifecycle.Event.ON_RESUME -> vm.onForeground()
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 三级拦截优先级：锁屏 > 二级页面 > 交还系统（退出应用）。
    // 锁着的时候返回键什么都不做，不能让它把下面的页面翻走。
    BackHandler(enabled = vm.locked || vm.navigator.canGoBack) {
        if (!vm.locked) vm.navigator.pop()
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let(vm::onAvatarPicked) }

    val pickAvatar: () -> Unit = remember(avatarPicker) {
        {
            avatarPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }

    val netdiskStatus = remember(syncState, pending) {
        vm.netdiskStatusText(syncState, pending)
    }

    Box(Modifier.fillMaxSize().background(PageBackground)) {
        when (val route = vm.navigator.current) {
            DinnerRoute.Timeline -> TimelineScreen(
                records = records,
                summary = summary,
                groupName = vm.groupName,
                onAddRecord = { vm.navigator.push(DinnerRoute.Edit) },
                onOpenCalendar = { vm.navigator.push(DinnerRoute.Calendar) },
                onOpenRecord = { vm.navigator.push(DinnerRoute.Detail(it)) },
                onToggleStar = vm::toggleStar,
                onSelectTab = { vm.navigator.switchTab(it) },
                onFetchMedia = vm::fetchMedia,
            )

            DinnerRoute.Mine -> MineScreen(
                name = vm.displayName,
                avatarPath = vm.avatarPath,
                isAdmin = vm.iAmAdmin,
                versionLabel = vm.versionLabel(syncState.authorized),
                stats = stats,
                groupName = vm.groupName,
                memberCount = members.size,
                retentionDays = vm.retentionDays,
                compressEnabled = vm.compressEnabled,
                netdiskStatus = netdiskStatus,
                authorized = syncState.authorized,
                paired = vm.paired,
                nameSet = vm.nameSet,
                onboardingDone = vm.onboardingDone,
                onFinishOnboarding = vm::finishOnboarding,
                appLockEnabled = vm.appLockEnabled,
                busy = vm.busy,
                onSelectTab = { vm.navigator.switchTab(it) },
                onOpenGroup = { vm.navigator.push(DinnerRoute.Group) },
                onOpenNetdisk = { vm.navigator.push(DinnerRoute.Netdisk) },
                onOpenAbout = { vm.navigator.push(DinnerRoute.About) },
                onOpenStat = { vm.navigator.push(DinnerRoute.Browse(it)) },
                onPickAvatar = pickAvatar,
                onRename = vm::rename,
                onSetRetention = vm::setRetention,
                onSetCompress = vm::setCompress,
                themeMode = vm.themeMode,
                onSetTheme = vm::changeThemeMode,
                onRestoreMedia = vm::restoreMedia,
                onSetAppLock = vm::setAppLock,
            )

            DinnerRoute.Calendar -> CalendarScreen(
                records = records,
                groupName = vm.groupName,
                onBack = { vm.navigator.pop() },
                onAddRecord = { vm.navigator.push(DinnerRoute.Edit) },
                onOpenRecord = { vm.navigator.push(DinnerRoute.Detail(it)) },
            )

            DinnerRoute.Group -> GroupScreen(
                groupName = vm.groupName,
                members = members,
                memberLimit = MEMBER_LIMIT,
                joinCode = vm.joinCode,
                adminCode = vm.adminCode,
                adminUnlocked = adminUnlocked,
                iAmAdmin = vm.iAmAdmin,
                phoneMask = vm.phoneMask,
                paired = vm.paired,
                adminPhoneMask = vm.adminPhoneMask,
                onBack = { vm.navigator.pop() },
                onUnlock = vm::unlockAdmin,
                onLock = vm::lockAdmin,
                onUpdateGroupName = vm::updateGroupName,
                onUpdateJoinCode = vm::updateJoinCode,
                onUpdateAdminCode = vm::updateAdminCode,
                onTransferAdmin = { vm.transferAdmin(it.id, it.name) },
                onBindPhone = vm::bindPhone,
                onJoinGroup = { phone, adminPhone, code -> vm.joinGroup(phone, adminPhone, code) },
                onCreateGroup = { phone, pwd -> vm.createGroup(phone, pwd) },
                onRotateJoinCode = vm::rotateJoinCode,
            )

            DinnerRoute.Netdisk -> NetdiskScreen(onBack = { vm.navigator.pop() })

            DinnerRoute.About -> AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                authorized = syncState.authorized,
                storageDir = BaiduConfig.APP_DIR,
                onBack = { vm.navigator.pop() },
            )

            DinnerRoute.Edit -> EditScreen(
                compressDefault = vm.compressEnabled,
                onCancel = { vm.navigator.pop() },
                onImportMedia = vm::importMedia,
                onSave = vm::addRecord,
                location = vm.pendingLocation,
                hasLocationPermission = vm.hasLocationPermission(),
                onRequestLocation = { vm.attachLocation() },
                onClearLocation = vm::clearLocation,
            )

            is DinnerRoute.Detail -> {
                val record = records.firstOrNull { it.id == route.recordId }
                if (record != null) {
                    RecordDetailScreen(
                        record = record,
                        canManage = vm.canManage(record.authorId),
                        canTouchCloud = vm.canTouchCloud(record.authorId),
                        adminUnlocked = adminUnlocked,
                        onBack = { vm.navigator.pop() },
                        onRate = { vm.setRating(record.id, it) },
                        onToggleStar = { vm.toggleStar(record.id) },
                        onComment = { vm.addComment(record.id, it) },
                        onDelete = { scope -> vm.deleteRecord(record.id, scope) },
                        canDeleteComment = { authorId -> vm.canManage(authorId) },
                        onDeleteComment = { commentId -> vm.deleteComment(record.id, commentId) },
                        onFetchMedia = vm::fetchMedia,
                    )
                } else if (records.isNotEmpty()) {
                    // 记录已经不在了（自己被删，或别人删完同步下来）。
                    // 判空是为了首帧：库还没读出来时 records 是空的，不能当成「被删了」。
                    LaunchedEffect(route.recordId) { vm.navigator.pop() }
                }
            }

            is DinnerRoute.Browse -> {
                val scoped = remember(route.scope) { vm.recordsFlow(route.scope) }
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                RecordListScreen(
                    scope = route.scope,
                    records = scoped.value,
                    onBack = { vm.navigator.pop() },
                    onOpenRecord = { vm.navigator.push(DinnerRoute.Detail(it)) },
                )
            }
        }

        // 锁屏盖在最上层：不解锁，下面的页面一律不响应
        if (vm.locked) {
            AppLockScreen(
                onVerified = vm::unlockApp,
                onUnavailable = vm::skipLock,
            )
        }
    }
}
