package com.guolongli.dinner.feature.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.ui.components.AppIconButton
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.ConfirmDialog
import com.guolongli.dinner.ui.components.DayHeader
import com.guolongli.dinner.ui.components.EmptyState
import com.guolongli.dinner.ui.components.ErrorState
import com.guolongli.dinner.ui.components.TimelineSkeleton
import com.guolongli.dinner.ui.components.CommentSheet
import com.guolongli.dinner.ui.components.DeleteRecordDialog
import com.guolongli.dinner.ui.components.MealCard
import com.guolongli.dinner.ui.components.MealCardUi
import com.guolongli.dinner.ui.components.PhotoViewer
import com.guolongli.dinner.ui.components.SummaryHero
import com.guolongli.dinner.ui.components.VideoPlayerDialog
import com.guolongli.dinner.ui.components.mediaModel
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme
import kotlinx.coroutines.launch

/**
 * 时间线主页。
 *
 * 顶部聚合摘要 + 按天分组的记录卡片。底部悬浮「新增」进入编辑页。
 * 空 / 加载 / 错误态都有设计。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    onAddMeal: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenGroup: () -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 订阅整份 state 的代价：任何一次 _uiState.update 都换一个新实例，
    // 于是评论输入框敲一个字，索引条 + 月份条 + 所有可见卡片都要重走一遍组合。
    //
    // 这里每个字段各自派生：值没变（引用相等）的字段不会让这一层重组，
    // 敲字因此只重走评论区那一小块。
    val isLoading by remember { derivedStateOf { state.isLoading } }
    val errorMessage by remember { derivedStateOf { state.errorMessage } }
    val needsGroup by remember { derivedStateOf { state.needsGroup } }
    val hasRecords by remember { derivedStateOf { state.hasRecords } }
    val syncMessage by remember { derivedStateOf { state.syncMessage } }
    val isSyncing by remember { derivedStateOf { state.isSyncing } }
    val scopeChips by remember { derivedStateOf { state.scopeChips } }
    val months by remember { derivedStateOf { state.months } }
    val entries by remember { derivedStateOf { state.entries } }
    val deleteTarget by remember { derivedStateOf { state.deleteTarget } }
    val player by remember { derivedStateOf { state.player } }

    var photoModelPicked by remember { mutableStateOf<Any?>(null) }
    // 滚动位置跨旋转保留：不加 saver 的话转一次屏就回到顶部。
    val listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val coroutineScope = rememberCoroutineScope()

    // 进入应用同步一次（AGENTS.md：百度网盘没有推送，同步只能由客户端发起）。
    // 之前这个入口压根没人调用，refresh() 是死代码，于是进应用既不上传也不拉取，
    // 对方新记的那顿要等到自己编辑一条记录才会出现。
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 换筛选条件后回到顶部：否则停在原来的位置，看到的是一批「换过之后」的随机中间段。
    LaunchedEffect(state.scope) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "食伴",
                subtitle = "一起吃过的每一顿",
                trailing = {
                    // 同步中就把按钮换成转圈，避免连点把开发期 100 次/小时的配额打空。
                    if (isSyncing) {
                        Box(
                            modifier = Modifier.size(DinnerDimens.touchTargetMin),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(DinnerDimens.spinnerSm),
                                strokeWidth = DinnerDimens.progressStroke,
                            )
                        }
                    } else {
                        AppIconButton(
                            onClick = { viewModel.refresh(force = true) },
                            label = "同步",
                            icon = Icons.Outlined.Sync,
                        )
                    }
                    AppIconButton(
                        onClick = onOpenCalendar,
                        label = "日历",
                        icon = Icons.Outlined.CalendarMonth,
                    )
                },
            )
        },
        floatingActionButton = {
            // 没有群组就不给「记一顿」：记录只能落在 group_<管理员手机号>/ 之内，
            // 未入组时记下来既无处可存、也无处可传，让按钮消失比点了再失败诚实。
            if (!needsGroup) {
                ExtendedFloatingActionButton(
                    onClick = onAddMeal,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("记一顿") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val message = errorMessage
            when {
                // 骨架屏而不是一个孤立转圈：进应用时屏幕已经有了大致形状，
                // 不再是一整片空白加一个圈。
                isLoading -> TimelineSkeleton()
                message != null -> ErrorState(
                    message = message,
                    onRetry = { viewModel.refresh(force = true) },
                )
                // 未入组：推文属于群组，没群组就没东西可看。
                // 给明确出口，而不是让用户对着空白页猜。
                needsGroup -> EmptyState(
                    message = "还没有群组",
                    hint = "建一个群，或者用管理员转告的入群密码加入。进了群才能看到大家记的每一顿",
                    icon = Icons.Outlined.Group,
                    action = {
                        Button(onClick = onOpenGroup) {
                            Text("建群 / 加入群组")
                        }
                    },
                )
                // 一条都没有：空状态放进列表区，**顶部索引条仍然常驻**（AGENTS 红线）。
                // 早先这里不渲染筛选条，于是零记录时整个索引区消失，
                // 用户会以为筛选功能坏了，也失去了「点一下回全部」的出口。
                !hasRecords -> Column(modifier = Modifier.fillMaxSize()) {
                    SyncNoticeSlot(message = syncMessage)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DinnerDimens.screenPadding),
                        shape = RoundedCornerShape(DinnerDimens.radiusMd),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            DinnerDimens.borderThin,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(vertical = DinnerDimens.spaceXxs)) {
                            ScopeBar(
                                chips = scopeChips,
                                onSelect = viewModel::setScope,
                            )
                        }
                    }
                    EmptyState(
                        modifier = Modifier.weight(1f),
                        message = "还没有记录",
                        hint = "点右下角「记一顿」，把今天的第一顿拍下来",
                        icon = Icons.Outlined.RestaurantMenu,
                    )
                }
                // 有记录但当前筛选为空：筛选条必须还在，否则用户被卡死在这一屏。
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    SyncNoticeSlot(message = syncMessage)
                    // 索引区：筛选条与月份条合成一个视觉块。
                    // 两块各自贴边时看起来是两排散落的胶囊，合成一块才有「这是顶部索引」的层级感。
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DinnerDimens.screenPadding),
                        shape = RoundedCornerShape(DinnerDimens.radiusMd),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            DinnerDimens.borderThin,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = DinnerDimens.spaceXxs),
                        ) {
                            ScopeBar(
                                chips = scopeChips,
                                onSelect = viewModel::setScope,
                            )
                            if (months.size > 1) {
                                MonthBar(
                                    months = months,
                                    onJump = { index ->
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(index)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (entries.isEmpty()) {
                        EmptyState(
                            modifier = Modifier.weight(1f),
                            message = "这个筛选下没有记录",
                            hint = "换个类型看看，或者点「看全部」回到完整时间线",
                            icon = Icons.Outlined.RestaurantMenu,
                            action = {
                                OutlinedButton(
                                    onClick = { viewModel.setScope(TimelineScope.All) },
                                ) {
                                    Text("看全部记录")
                                }
                            },
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = DinnerDimens.screenPadding,
                                end = DinnerDimens.screenPadding,
                                bottom = DinnerDimens.spaceListBottom,
                                top = DinnerDimens.spaceXs,
                            ),
                            verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                        ) {
                            items(
                                items = entries,
                                key = { entry -> entryKey(entry) },
                                // 整数常量而不是 entry::class.simpleName：
                                // 反射取类名会在滚动时对每一个可见项各跑一次。
                                contentType = { entry -> entryContentType(entry) },
                            ) { entry ->
                                when (entry) {
                                    is TimelineEntry.Hero -> SummaryHero(
                                        count = entry.count,
                                        days = entry.days,
                                        subtitle = entry.subtitle,
                                        onClick = onOpenCalendar,
                                    )
                                    is TimelineEntry.Header -> DayHeader(
                                        title = entry.title,
                                        subtitle = entry.subtitle,
                                    )
                                    is TimelineEntry.Card -> TimelineCardRow(
                                        item = entry.item,
                                        viewModel = viewModel,
                                        onOpen = onOpenRecord,
                                        onPhoto = { model -> photoModelPicked = model },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除：三档选择（只删本地 / 只删云端 / 都删），非本人只能删本地
    deleteTarget?.let { target ->
        DeleteRecordDialog(
            canDeleteCloud = target.canDeleteCloud,
            onDeleteLocal = viewModel::deleteLocalOnly,
            onDeleteCloud = viewModel::deleteCloudOnly,
            onDeleteBoth = viewModel::deleteBoth,
            onDismiss = viewModel::cancelDelete,
        )
    }

    // 评论面板与评论删除确认：单独一层，只订阅评论相关的两个字段。
    // 输入框每敲一个字都会让整份 state 换实例，放在上面那片会拖着整屏重组。
    TimelineCommentLayer(viewModel = viewModel)

    // 照片查看器
    photoModelPicked?.let { model ->
        PhotoViewer(
            model = model,
            imageDesc = "餐食照片",
            onDismiss = { photoModelPicked = null },
        )
    }

    // 视频播放
    player.path?.let { path ->
        VideoPlayerDialog(path = path, onDismiss = viewModel::dismissPlayer)
    }
    if (player.isLoading) {
        Dialog(
            onDismissRequest = viewModel::dismissPlayer,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DinnerTheme.colors.mediaScrim.copy(alpha = DinnerDimens.alphaOverlay)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = DinnerTheme.colors.onMedia)
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    Text(
                        text = "正在从网盘取回视频…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DinnerTheme.colors.onMedia,
                    )
                }
            }
        }
    }
    player.errorMessage?.let { message ->
        ConfirmDialog(
            title = "视频打不开",
            message = message,
            confirmText = "知道了",
            dismissText = "关闭",
            destructive = false,
            onConfirm = viewModel::dismissPlayer,
            onDismiss = viewModel::dismissPlayer,
        )
    }
}

/**
 * 时间线里的一张卡片。
 *
 * 四个回调按 id 固化（[remember]）：不固化的话每次重组都会新建 lambda，
 * 卡片拿到的参数每次都是新对象，列表里每一项都被判定为「变了」而重走组合。
 */
@Composable
private fun LazyItemScope.TimelineCardRow(
    item: MealCardUi,
    viewModel: TimelineViewModel,
    onOpen: (String) -> Unit,
    onPhoto: (Any) -> Unit,
) {
    val id = item.id
    val onClick = remember(id) { { onOpen(id) } }
    // 媒体这一路按 item 固化而不是只按 id：媒体路径会随「从云端取回」回填而改变，
    // 只认 id 会让卡片一直拿着取回前的旧路径，点一下走的是重试而不是看图。
    val onMediaClick = remember(item) {
        { index: Int ->
            val media = item.media.getOrNull(index)
            if (media != null) {
                if (media.mediaType == MediaType.VIDEO) {
                    viewModel.playVideo(id, media.id)
                } else {
                    val model = mediaModel(media.localPath, media.thumbPath, media.mediaType)
                    if (model != null) {
                        onPhoto(model)
                    } else {
                        // 本地没有、远端有：触发一次补下载，而不是干瞪眼。
                        viewModel.retryMedia(id)
                    }
                }
            }
        }
    }
    val onComments = remember(id) { { viewModel.openComments(id) } }
    val onDelete = remember(id) { { viewModel.requestDelete(id) } }
    val onToggleMark = remember(id) { { viewModel.toggleMark(id) } }

    MealCard(
        item = item,
        modifier = Modifier.animateItem(
            fadeInSpec = tween(DinnerDimens.animStandardMs),
            placementSpec = tween(DinnerDimens.animStandardMs),
            fadeOutSpec = tween(DinnerDimens.animStandardMs),
        ),
        onClick = onClick,
        onMediaClick = onMediaClick,
        onComments = onComments,
        onDelete = onDelete,
        onToggleMark = onToggleMark,
    )
}

/**
 * 评论层：评论面板 + 删除评论的二次确认。
 *
 * 独立成一个 composable，内部用 [derivedStateOf] 只取 commentSheet 与
 * pendingCommentDelete 两个字段——其余字段变化（同步、取回媒体）不再把
 * 面板拖着重组，反之敲字也不会影响上面的列表。
 */
@Composable
private fun TimelineCommentLayer(viewModel: TimelineViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet by remember { derivedStateOf { state.commentSheet } }
    val pendingDelete by remember { derivedStateOf { state.pendingCommentDelete } }

    sheet?.let { commentSheet ->
        CommentSheet(
            title = commentSheet.title,
            comments = commentSheet.comments,
            input = commentSheet.input,
            isSending = commentSheet.isSending,
            errorMessage = commentSheet.errorMessage,
            onInputChange = viewModel::setCommentInput,
            onSend = viewModel::sendComment,
            onDelete = { comment -> viewModel.requestDeleteComment(comment) },
            onDismiss = viewModel::closeComments,
        )
    }

    // 评论删除确认：删除同步给所有人，误触代价不小，所以多问一句。
    pendingDelete?.let { comment ->
        ConfirmDialog(
            title = "删除这条评论？",
            message = "「${comment.text.take(DinnerDimens.commentPreviewChars)}」\n\n删除后会同步给所有人，不能恢复。",
            confirmText = "删除",
            onConfirm = viewModel::confirmDeleteComment,
            onDismiss = viewModel::cancelDeleteComment,
        )
    }
}

private fun entryKey(entry: TimelineEntry): String = when (entry) {
    is TimelineEntry.Hero -> "hero"
    is TimelineEntry.Header -> "H:${entry.dayKey}"
    is TimelineEntry.Card -> "C:${entry.item.id}"
}

/** contentType 用整数常量：同类条目才能复用同一个组合槽位。 */
private const val CONTENT_HERO = 0
private const val CONTENT_HEADER = 1
private const val CONTENT_CARD = 2

private fun entryContentType(entry: TimelineEntry): Int = when (entry) {
    is TimelineEntry.Hero -> CONTENT_HERO
    is TimelineEntry.Header -> CONTENT_HEADER
    is TimelineEntry.Card -> CONTENT_CARD
}

/**
 * 索引条：全部 / 星标 / 九种记录类型。
 *
 * 单选，横向滚动——类型多，不硬塞进一屏，也不做成下拉菜单（多点一次才能看到）。
 */
@Composable
private fun ScopeBar(
    chips: List<ScopeChip>,
    onSelect: (TimelineScope) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
        contentPadding = PaddingValues(
            horizontal = DinnerDimens.spaceXs,
            vertical = DinnerDimens.spaceXxs,
        ),
    ) {
        items(items = chips, key = { it.label }) { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = { onSelect(chip.scope) },
                label = { Text(chip.label, style = MaterialTheme.typography.labelLarge) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/** 月份索引：记录跨了多个月才出现，点一下滚到那个月。 */
@Composable
private fun MonthBar(
    months: List<MonthIndex>,
    onJump: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
        contentPadding = PaddingValues(
            start = DinnerDimens.spaceXs,
            end = DinnerDimens.spaceXs,
            bottom = DinnerDimens.spaceXxs,
        ),
    ) {
        items(items = months, key = { it.label }) { month ->
            SuggestionChip(
                onClick = { onJump(month.itemIndex) },
                label = { Text(month.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * 同步结论条的插槽：出现 / 消失都带高度动画。
 *
 * 它是 Column 的第一个子项，直接塞进列表时「有结论 / 没结论」会把下面
 * 整屏上下顶一下（顶部索引、列表全部跟着跳）。做成展开 / 收起动画，
 * 视觉上就只是这一条自己撑开与收回。
 */
@Composable
private fun SyncNoticeSlot(message: String?) {
    // 退出动画期间 message 已经是 null，靠这里留住最后一句，
    // 否则文字会在收缩开始的那一帧直接消失，只剩一个空框在缩。
    val lastMessage: MutableState<String?> = remember { mutableStateOf(message) }
    if (message != null) {
        lastMessage.value = message
    }
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(tween(DinnerDimens.animStandardMs)) +
            fadeIn(tween(DinnerDimens.animStandardMs)),
        exit = shrinkVertically(tween(DinnerDimens.animStandardMs)) +
            fadeOut(tween(DinnerDimens.animStandardMs)),
    ) {
        lastMessage.value?.let { SyncNotice(message = it) }
    }
}

/**
 * 同步结论条。
 *
 * 成功也留一句话：「已从云端取回 3 条记录」比什么都不说强，
 * 否则用户点完刷新只看到界面没动静，会认为同步根本没跑。
 */
@Composable
private fun SyncNotice(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DinnerDimens.screenPadding,
                vertical = DinnerDimens.spaceXxs,
            ),
        shape = RoundedCornerShape(DinnerDimens.radiusSm),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DinnerDimens.spaceXs,
                vertical = DinnerDimens.spaceXs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(DinnerDimens.iconSm),
            )
            Spacer(modifier = Modifier.size(DinnerDimens.spaceXs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
