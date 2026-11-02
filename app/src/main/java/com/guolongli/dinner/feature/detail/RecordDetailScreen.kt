package com.guolongli.dinner.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.data.DeleteScope
import com.guolongli.dinner.feature.timeline.MediaGrid
import com.guolongli.dinner.ui.component.ConfirmDialog
import com.guolongli.dinner.ui.component.DialogOption
import com.guolongli.dinner.ui.component.InputField
import com.guolongli.dinner.ui.component.MediaViewerDialog
import com.guolongli.dinner.ui.component.MemberAvatar
import com.guolongli.dinner.ui.component.OptionsDialog
import com.guolongli.dinner.ui.component.StarRating
import com.guolongli.dinner.ui.component.TypeTag
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.RecordUiModel
import com.guolongli.dinner.ui.model.dayLabel
import com.guolongli.dinner.ui.model.timeOfDay
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DividerColor
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagRedFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import java.io.File

/**
 * 记录详情。
 *
 * 设计稿里卡片只负责「看」，真正要改点什么得有个落点——这一页就是那个落点：
 * 全量评论、打分、星标、删除。删除是唯一不可撤销的动作，所以走「选档位 → 再确认」两步。
 *
 * 权限不是在这里判断的：能不能删、能删到哪一层由上层（AdminSession）算好传进来，
 * 这一页只负责把不允许的选项藏掉——真正的拦截在仓储里。
 */
@Composable
fun RecordDetailScreen(
    record: RecordUiModel,
    canManage: Boolean,
    canTouchCloud: Boolean,
    adminUnlocked: Boolean,
    onBack: () -> Unit,
    onRate: (Int) -> Unit,
    onToggleStar: () -> Unit,
    onComment: (String) -> Unit,
    onDelete: (DeleteScope) -> Unit,
    /** 这条评论能不能删（自己的评论，或已解锁管理员） */
    canDeleteComment: (authorId: String) -> Boolean = { false },
    onDeleteComment: (commentId: String) -> Unit = {},
    /**
     * 按需取回还没下载的媒体（recordId, position → 本地路径）。
     * 点开云端那一份的格子时先取回再打开，取不到给提示，不静默无反应。
     */
    onFetchMedia: suspend (recordId: String, position: Int) -> String? = { _, _ -> null },
    modifier: Modifier = Modifier,
) {
    var draft by remember(record.id) { mutableStateOf("") }
    var viewer by remember(record.id) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var deleteStep by remember(record.id) { mutableStateOf(DeleteStep.NONE) }
    var pickedScope by remember(record.id) { mutableStateOf<DeleteScope?>(null) }
    var fetching by remember(record.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().background(PageBackground)) {
        // 顶部导航：返回 / 标题 / 星标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PageBackground)
                .statusBarsPadding()
                .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                LineIcon(spec = LineIconSpec.ARROW_LEFT, tint = TextPrimary, size = 20.dp)
            }
            Text(text = "这条记录", style = DinnerText.navTitle, color = TextPrimary)
            Text(
                text = if (record.starred) "取消星标" else "加星标",
                style = DinnerText.bodySmallMedium,
                color = if (record.starred) TagRedFg else BrandBlue,
                modifier = Modifier
                    .clickable(enabled = canManage) { onToggleStar() }
                    .padding(Dimens.GapXs),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapXl),
        ) {
            AuthorRow(record = record)

            if (record.note.isNotBlank()) {
                Text(text = record.note, style = DinnerText.body, color = TextPrimary)
            }

            // 位置：有地名就显示地名，没有就显示坐标——绝不显示一行空白
            record.locationLabel?.let { place ->
                Text(
                    text = "📍 $place",
                    style = DinnerText.caption,
                    color = TextSecondary,
                )
            }

            MediaGrid(
                media = record.media,
                modifier = Modifier.fillMaxWidth(),
                tileHeight = Dimens.MediaTileHeight * 1.2f,
                onMediaClick = { media ->
                    // localPath 有值 ≠ 文件还在：文件被系统回收时路径仍留在库里，
                    // 这种情况下要当「没下载」处理，按需重新取回，
                    // 否则点开就是一片空白 / 一片黑。
                    val local = media.localPath?.takeIf { File(it).isFile }
                    if (local != null) {
                        viewer = local to media.isVideo
                    } else if (!fetching) {
                        // 只有云端那一份：先按需取回，取到就直接打开
                        fetching = true
                        scope.launch {
                            try {
                                val path = onFetchMedia(media.recordId, media.position)
                                if (path != null) {
                                    viewer = path to media.isVideo
                                }
                            } finally {
                                fetching = false
                            }
                        }
                    }
                },
            )

            if (fetching) {
                Text(
                    text = "正在从网盘取回…",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                )
            }

            // 打分：点第几颗就是几分；点已经点亮的那颗清零
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                StarPicker(
                    rating = record.rating,
                    enabled = canManage,
                    onPick = { star -> onRate(if (record.rating == star) 0 else star) },
                )
                Text(
                    text = if (record.rating == 0) "还没打分" else "${record.rating} 分",
                    style = DinnerText.caption,
                    color = TextSecondary,
                )
            }

            HorizontalDivider(thickness = 1.dp, color = DividerColor)

            CommentsSection(
                record = record,
                canDelete = canDeleteComment,
                onDelete = onDeleteComment,
            )

            if (!canManage) {
                Text(
                    text = "这是 ${record.authorName} 记的，只有本人或解锁后的管理员能修改",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                )
            }

            Text(
                text = "删除这条记录",
                style = DinnerText.footnote,
                color = TagRedFg,
                modifier = Modifier
                    .clickable(enabled = canManage) { deleteStep = DeleteStep.PICK }
                    .padding(vertical = Dimens.GapM),
            )
            Spacer(Modifier.height(Dimens.GapXl))
        }

        // 评论区输入条，跟着键盘走
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceWhite)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Box(Modifier.weight(1f)) {
                InputField(
                    value = draft,
                    hint = "写句评论…",
                    onChange = { draft = it.take(200) },
                )
            }
            Text(
                text = "发送",
                style = DinnerText.bodySmallMedium,
                color = if (draft.isBlank()) TextTertiary else BrandBlue,
                modifier = Modifier
                    .clickable(enabled = draft.isNotBlank()) {
                        onComment(draft)
                        draft = ""
                    }
                    .padding(Dimens.GapS),
            )
        }
    }

    viewer?.let { (path, isVideo) ->
        MediaViewerDialog(path = path, isVideo = isVideo, onDismiss = { viewer = null })
    }

    DeleteScopeDialogs(
        step = deleteStep,
        canTouchCloud = canTouchCloud,
        pending = pickedScope,
        onPick = { scope ->
            pickedScope = scope
            deleteStep = DeleteStep.CONFIRM
        },
        onConfirm = {
            deleteStep = DeleteStep.NONE
            pickedScope?.let(onDelete)
        },
        onDismiss = { deleteStep = DeleteStep.NONE },
    )
}

/**
 * 可点的五星。
 *
 * 用一排 28dp 的点击格而不是给 Canvas 算坐标：手指按到哪一格就是几分，
 * 视觉上的星形仍由 [StarRating] 画，命中区与显示区各管各的。
 */
@Composable
private fun StarPicker(rating: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            val star = index + 1
            Text(
                text = if (star <= rating) "★" else "☆",
                style = DinnerText.bodyMedium.copy(fontSize = 24.sp),
                color = if (star <= rating) BrandBlue else TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = enabled) { onPick(star) },
            )
        }
    }
}

@Composable
private fun AuthorRow(record: RecordUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        MemberAvatar(
            initial = record.authorInitial,
            color = record.authorColor,
            size = Dimens.AvatarMedium,
            fontSize = 14.sp,
            avatarPath = record.authorAvatarPath,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = record.authorName, style = DinnerText.bodyStrong, color = TextPrimary)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                TypeTag(record.type)
                Text(
                    text = "${dayLabel(record.createdAt)} ${timeOfDay(record.createdAt)}",
                    style = DinnerText.caption,
                    color = TextTertiary,
                )
                record.syncLabel?.let {
                    Text(text = it, style = DinnerText.footnote, color = TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun CommentsSection(
    record: RecordUiModel,
    canDelete: (String) -> Boolean,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
        Text(
            text = if (record.comments.isEmpty()) "还没有评论" else "评论 · ${record.comments.size}",
            style = DinnerText.bodySmallMedium,
            color = TextSecondary,
        )
        record.comments.forEach { comment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                MemberAvatar(
                    initial = comment.authorInitial,
                    color = comment.authorColor,
                    size = Dimens.AvatarSmall,
                    fontSize = 10.sp,
                )
                Text(
                    text = comment.text,
                    style = DinnerText.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Dimens.RadiusSmall))
                        .background(SurfaceMuted)
                        .padding(horizontal = Dimens.GapL, vertical = Dimens.GapM),
                )
                // 只有自己的评论、或解锁后的管理员才看得到这个入口；
                // 真正的权限判定在仓储层，这里只是不给无权限的人一个按不动的按钮。
                if (canDelete(comment.authorId)) {
                    Text(
                        text = "删",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                        modifier = Modifier
                            .clickable { onDelete(comment.id) }
                            .padding(vertical = Dimens.GapM, horizontal = Dimens.GapXs),
                    )
                }
            }
        }
    }
}

/** 删除的两步：先选范围，再确认 */
internal enum class DeleteStep { NONE, PICK, CONFIRM }

/**
 * 删除范围的选择弹窗。抽成一个内部 composable，是为了让上面的主体保持可读——
 * 详情页真正的逻辑只有「点了什么」，弹窗长什么样不该占满一屏。
 */
@Composable
internal fun DeleteScopeDialogs(
    step: DeleteStep,
    canTouchCloud: Boolean,
    pending: DeleteScope?,
    onPick: (DeleteScope) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (step) {
        DeleteStep.NONE -> Unit

        DeleteStep.PICK -> {
            val options = buildList {
                add(DialogOption(DeleteScope.LOCAL.label, DeleteScope.LOCAL.explanation))
                if (canTouchCloud) {
                    add(DialogOption(DeleteScope.CLOUD.label, DeleteScope.CLOUD.explanation, destructive = true))
                    add(DialogOption(DeleteScope.BOTH.label, DeleteScope.BOTH.explanation, destructive = true))
                }
            }
            OptionsDialog(
                title = "删除这条记录",
                message = if (canTouchCloud) {
                    "选一个范围。云端那一份删掉之后，别人下次同步就看不到了。"
                } else {
                    "这条不是你上传的，只能从本机移除。"
                },
                options = options,
                onDismiss = onDismiss,
                onPick = { index -> onPick(options[index].let { scopeOf(it.title) }) },
            )
        }

        DeleteStep.CONFIRM -> {
            val scope = pending ?: return
            ConfirmDialog(
                title = "再确认一次",
                message = "${scope.label}：${scope.explanation}。这一步不可撤销。",
                confirmLabel = "确定删除",
                destructive = true,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

private fun scopeOf(label: String): DeleteScope =
    DeleteScope.entries.firstOrNull { it.label == label } ?: DeleteScope.LOCAL
