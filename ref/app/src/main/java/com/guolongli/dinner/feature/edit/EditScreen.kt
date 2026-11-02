package com.guolongli.dinner.feature.edit

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.domain.media.MediaSource
import com.guolongli.dinner.domain.model.MealType
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.ErrorState
import com.guolongli.dinner.ui.components.LoadingState
import com.guolongli.dinner.ui.components.MealTypeChip
import com.guolongli.dinner.ui.components.MediaPicker
import com.guolongli.dinner.ui.components.MediaThumbnail
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.components.StarRating
import com.guolongli.dinner.ui.components.ThinDivider
import com.guolongli.dinner.ui.components.VideoPlayerDialog
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.util.DateText
import com.guolongli.dinner.ui.util.mealLabelOf

/**
 * 媒体九宫格：每行三格，方图裁切，右上角一枚移除按钮。
 *
 * 点视频格子播放预览；已经在云端但本机没下载的那几段显示占位，
 * 不给播放按钮（点了也没文件）。
 */
@Composable
private fun MediaEditGrid(
    media: List<EditingMedia>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs)) {
        media.chunked(MEDIA_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        val playable = item.mediaType == MediaType.VIDEO && item.localPath.isNotBlank()
                        MediaThumbnail(
                            localPath = item.localPath,
                            thumbnailPath = item.thumbPath,
                            mediaType = item.mediaType,
                            durationText = item.durationText,
                            aspectRatio = 1f,
                            hint = if (item.localPath.isBlank()) "云端" else null,
                            onClick = if (playable) ({ onPlayVideo(item.localPath) }) else null,
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(DinnerDimens.spaceXxs)
                                // 点击区与视觉尺寸分开：圆只有 24dp 好看，
                                // 但手指按不准，也不满足 48dp 的触控下限。
                                .sizeIn(
                                    minWidth = DinnerDimens.touchTargetMin,
                                    minHeight = DinnerDimens.touchTargetMin,
                                )
                                .clip(CircleShape)
                                .clickable(enabled = enabled) { onRemove(item.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(DinnerDimens.touchTargetMin / 2)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.scrim.copy(
                                            alpha = DinnerDimens.alphaOverlaySoft,
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "移除这段媒体",
                                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.size(DinnerDimens.iconSm),
                                )
                            }
                        }
                    }
                }
                repeat(MEDIA_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 类型胶囊每行个数。当前 9 个选项正好铺满三行，
 * 新增枚举值时会自动折行，末行缺位用占位补齐。
 */
private const val TYPE_COLUMNS = 3

/** 媒体九宫格每行个数。 */
private const val MEDIA_COLUMNS = 3

/** 标签数量上限，与 ViewModel 的 MAX_TAGS 保持一致（那里是私有常量）。 */
private const val MAX_TAGS_IN_UI = 6

/**
 * 新增 / 编辑一条记录。
 *
 * 表单按「类型 / 时间 / 媒体 / 备注 / 评分」分区，每区一张卡片；
 * 保存按钮固定在底部，长表单滚到任何位置都能提交。
 */
@Composable
fun EditScreen(
    onBack: () -> Unit,
    viewModel: EditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (state.recordId == null) "记一顿" else "编辑",
                subtitle = DateText.dateLabel(state.dateMillis),
                onBack = onBack,
            )
        },
        bottomBar = {
            SaveBar(
                state = state,
                onSaveOnly = viewModel::saveOnly,
                onSaveAndUpload = viewModel::saveAndUpload,
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            state.loadFailed -> ErrorState(
                modifier = Modifier.padding(padding),
                message = state.errorMessage ?: "加载失败",
                onRetry = onBack,
            )
            else -> EditForm(
                state = state,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onSelectMealType = viewModel::setMealType,
                onToggleMarked = viewModel::toggleMarked,
                onNoteChange = viewModel::setNote,
                onDatePicked = viewModel::setDate,
                onRatingChange = viewModel::setRating,
                onMediaPicked = viewModel::onMediaPicked,
                onRemoveMedia = viewModel::removeMedia,
                onRefreshLocation = viewModel::refreshLocation,
                onDisableLocation = viewModel::disableLocation,
                onEnableLocation = viewModel::enableLocation,
                onTagInputChange = viewModel::setTagInput,
                onAddTag = viewModel::addTag,
                onRemoveTag = viewModel::removeTag,
            )
        }
    }

    // 保存成功后由 ViewModel 回调 onSaved 指导导航，Screen 不直接消费。
    //
    // 用 DisposableEffect 而不是 LaunchedEffect(Unit)：后者的 key 永远不变，
    // lambda 会在第一次组合时被捕获后再也不更新（配置变更 / 重组后拿到的
    // onBack 是过期引用），而且没有清理路径，页面走了回调还挂着。
    DisposableEffect(viewModel) {
        viewModel.onSaved = { onBack() }
        onDispose { viewModel.onSaved = null }
    }
}

@Composable
private fun EditForm(
    state: EditUiState,
    modifier: Modifier = Modifier,
    onSelectMealType: (MealType) -> Unit,
    onToggleMarked: () -> Unit,
    onNoteChange: (String) -> Unit,
    onDatePicked: (Long) -> Unit,
    onRatingChange: (Int?) -> Unit,
    onMediaPicked: (MediaSource) -> Unit,
    onRemoveMedia: (String) -> Unit,
    onRefreshLocation: () -> Unit,
    onDisableLocation: () -> Unit,
    onEnableLocation: () -> Unit,
    onTagInputChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    val saving = state.savePhase != SavePhase.IDLE
    var playingVideoPath by remember { mutableStateOf<String?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = DinnerDimens.screenPadding,
                end = DinnerDimens.screenPadding,
                top = DinnerDimens.spaceXs,
                bottom = DinnerDimens.spaceSm,
            ),
        verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
    ) {
        // 主卡：类型 + 时间 + 媒体是一次记录的三要素，合进一张卡，
        // 打开页面第一眼就是「要记什么、什么时候、有没有图」。
        //
        // 早先是八张等权重的卡一路平铺下来，扫一眼分不清主次：
        // 必填的（类型 / 时间 / 媒体）和可选的（标签 / 评分 / 位置）长得一样重。
        SectionCard(title = "这一顿") {
            MealTypeGrid(
                selected = state.mealType,
                enabled = !saving,
                onSelect = onSelectMealType,
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            ThinDivider()
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))

            DateRow(
                dateMillis = state.dateMillis,
                enabled = !saving,
                onDatePicked = onDatePicked,
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            ThinDivider()
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))

            // 媒体：可以没有，也可以多条（照片与视频混排）
            if (state.media.isNotEmpty()) {
                MediaEditGrid(
                    media = state.media,
                    enabled = !saving,
                    onRemove = onRemoveMedia,
                    onPlayVideo = { path -> playingVideoPath = path },
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                Text(
                    text = "${state.media.size}/${state.mediaLimit} · " + if (state.compressMedia) {
                        "压缩存储：照片长边 1920、视频 1280"
                    } else {
                        "原画质存储：不降分辨率"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.canAddMedia) {
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                }
            }
            if (state.canAddMedia) {
                MediaPicker(enabled = !saving, onSourcePicked = onMediaPicked)
            } else {
                Text(
                    text = "已达 ${state.mediaLimit} 张上限，删一张再加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 备注：唯一的一段自由文本，单独一张卡，不被高级设置收走
        // （很多人记一顿只写这一句话）。
        SectionCard(title = "备注") {
            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("写点什么") },
                supportingText = {
                    Text("${state.noteLength}/${DinnerDimens.noteMaxLength}")
                },
                minLines = DinnerDimens.noteMinLines,
                enabled = !saving,
            )
        }

        // 标签 / 评分与星标 / 位置都是可选项：收进一张默认折起来的卡，
        // 主卡才真正是主角。
        AdvancedSection(
            expanded = advancedExpanded,
            onToggle = { advancedExpanded = !advancedExpanded },
        ) {
            AdvancedLabel("标签")
            TagEditor(
                state = state,
                enabled = !saving,
                onInputChange = onTagInputChange,
                onAdd = onAddTag,
                onRemove = onRemoveTag,
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            ThinDivider()
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            AdvancedLabel("评分与星标")
            StarRating(
                value = state.rating,
                editable = !saving,
                onValueChange = onRatingChange,
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.marked) "已加星标，方便在时间线里筛出来" else "加个星标，方便以后翻回去看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleMarked, enabled = !saving) {
                    Icon(
                        imageVector = if (state.marked) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (state.marked) "取消星标" else "加星标",
                        tint = if (state.marked) {
                            DinnerTheme.colors.starActive
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            ThinDivider()
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            AdvancedLabel("位置")
            LocationRow(
                state = state,
                onRefresh = onRefreshLocation,
                onDisable = onDisableLocation,
                onEnable = onEnableLocation,
            )
        }

        val errorMessage = state.errorMessage
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    playingVideoPath?.let { path ->
        VideoPlayerDialog(path = path, onDismiss = { playingVideoPath = null })
    }
}

/** 类型选择网格：餐别 + 生活场景，每行 [TYPE_COLUMNS] 个。 */
@Composable
private fun MealTypeGrid(
    selected: MealType,
    enabled: Boolean,
    onSelect: (MealType) -> Unit,
) {
    val types = MealType.entries
    Column(verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs)) {
        types.chunked(TYPE_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
            ) {
                row.forEach { type ->
                    MealTypeChip(
                        type = type,
                        label = mealLabelOf(type),
                        selected = selected == type,
                        onClick = { onSelect(type) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                    )
                }
                // 末行不满时补占位，保证所有胶囊等宽对齐
                repeat(TYPE_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 可折叠的高级设置卡。
 *
 * 标签 / 评分 / 位置都不是记一顿的必填项，默认收起：主卡（类型 + 时间 + 媒体）
 * 因此成为页面上唯一的主角，想细调再展开。
 */
@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DinnerDimens.radiusSm))
                .clickable(onClick = onToggle)
                .padding(vertical = DinnerDimens.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(DinnerDimens.iconMd),
            )
            Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
            Text(
                text = "高级设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起高级设置" else "展开高级设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(DinnerDimens.iconMd),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(DinnerDimens.animStandardMs)) +
                fadeIn(tween(DinnerDimens.animStandardMs)),
            exit = shrinkVertically(tween(DinnerDimens.animStandardMs)) +
                fadeOut(tween(DinnerDimens.animStandardMs)),
        ) {
            Column(
                modifier = Modifier.padding(top = DinnerDimens.spaceSm),
                content = content,
            )
        }
    }
}

/** 高级设置里的小标题：比卡片标题弱一档，只负责把这一块叫什么讲清楚。 */
@Composable
private fun AdvancedLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = DinnerDimens.spaceXs),
    )
}

/**
 * 位置行：展示当前位置，可重新取、可关掉。
 *
 * 定位是可选的：没权限、定位关闭、取不到都只让这一行显示提示，
 * 不影响保存记录——一顿饭没有位置照样是一顿饭。
 */
@Composable
private fun LocationRow(
    state: EditUiState,
    onRefresh: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
) {
    val location = state.location
    when {
        state.locationDisabled -> Column {
            Text(
                text = "已关掉位置记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            OutlinedButton(onClick = onEnable) {
                Text("重新开启")
            }
        }
        location != null -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text(
                    text = location.displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (location.hasAddress) {
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Text(
                    text = location.coordinateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            Row(horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs)) {
                OutlinedButton(onClick = onRefresh, enabled = !state.isLocating) {
                    Text("重新定位")
                }
                TextButton(onClick = onDisable) {
                    Text("不记录位置")
                }
            }
        }
        else -> Column {
            Text(
                text = if (state.isLocating) "正在取当前位置…" else "还没拿到位置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            OutlinedButton(onClick = onRefresh, enabled = !state.isLocating) {
                Text(if (state.isLocating) "定位中…" else "获取位置")
            }
        }
    }
}

/**
 * 标签编辑：输入框 + 回车 / 「添加」成一枚标签，点标签上的 × 移除。
 *
 * 标签随记录上云，其他成员在卡片与详情页都能看到；
 * 每条最多 [MAX_TAGS] 个、每条 [MAX_TAG_LENGTH] 字符（上限在 ViewModel 里把关）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    state: EditUiState,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.tagInput,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            label = { Text("加个标签") },
            singleLine = true,
            enabled = enabled && state.canAddTag,
            supportingText = {
                Text("${state.tags.size}/${MAX_TAGS_IN_UI} · 回车或点「添加」")
            },
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
        OutlinedButton(
            onClick = onAdd,
            enabled = enabled && state.canAddTag && state.tagInput.isNotBlank(),
            modifier = Modifier.height(DinnerDimens.touchTargetMin),
        ) {
            Text("添加")
        }
    }
    if (state.tags.isNotEmpty()) {
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
            verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
        ) {
            state.tags.forEach { tag ->
                TagChip(tag = tag, enabled = enabled, onRemove = { onRemove(tag) })
            }
        }
    }
}

@Composable
private fun TagChip(tag: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DinnerDimens.alphaSubtle))
            .padding(start = DinnerDimens.spaceXs),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            // 32dp 的按钮按不准：这里只给下限，图标仍保持 18dp 的视觉尺寸。
            modifier = Modifier.sizeIn(
                minWidth = DinnerDimens.touchTargetMin,
                minHeight = DinnerDimens.touchTargetMin,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除标签 $tag",
                modifier = Modifier.size(DinnerDimens.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 底部固定操作栏：保存 / 保存并上传。
 *
 * 两个动作的差别只在「要不要现在上传」：都先落本地 Room，
 * 保存后立即返回，上传在后台继续，失败会如实体现在卡片的同步徽标上。
 */
@Composable
private fun SaveBar(
    state: EditUiState,
    onSaveOnly: () -> Unit,
    onSaveAndUpload: () -> Unit,
) {
    val idle = state.savePhase == SavePhase.IDLE
    // 没有修改权时按钮直接禁用：不给「点下去再失败」的错觉。
    val enabled = idle && state.canSave && state.canEdit

    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = DinnerDimens.elevationFlat,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(
                    start = DinnerDimens.screenPadding,
                    end = DinnerDimens.screenPadding,
                    top = DinnerDimens.spaceXs,
                    bottom = DinnerDimens.spaceXs,
                ),
        ) {
            if (!state.canEdit) {
                Text(
                    text = "这是" + state.authorName.ifBlank { "别人" } + "记的，只有本人或管理员能改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = DinnerDimens.spaceXs),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs)) {
                OutlinedButton(
                    onClick = onSaveOnly,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(DinnerDimens.touchTargetMin),
                ) {
                    Text(
                        text = when (state.savePhase) {
                            SavePhase.IMPORTING -> "处理中…"
                            SavePhase.SAVING -> "保存中…"
                            else -> "保存"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Button(
                    onClick = onSaveAndUpload,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(DinnerDimens.touchTargetMin),
                ) {
                    Text(
                        text = when (state.savePhase) {
                            SavePhase.IMPORTING -> "处理中…"
                            SavePhase.SAVING -> "保存中…"
                            SavePhase.UPLOADING -> "上传中…"
                            SavePhase.DONE -> "已保存"
                            SavePhase.PARTIAL -> "未同步上云"
                            SavePhase.FAILED -> "上传失败"
                            SavePhase.IDLE -> "保存并上传"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateRow(dateMillis: Long, enabled: Boolean, onDatePicked: (Long) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DinnerDimens.touchTargetMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
        Text(
            text = DateText.dateLabel(dateMillis),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = {
                val year = DateText.yearOf(dateMillis)
                val month = DateText.monthOf(dateMillis)
                val day = DateText.dayOfMonthOf(dateMillis)
                DatePickerDialog(
                    context,
                    { _, y, m, d -> onDatePicked(DateText.fromDateParts(y, m, d)) },
                    year,
                    month,
                    day,
                ).show()
            },
            enabled = enabled,
        ) {
            Text("更改")
        }
    }
}
