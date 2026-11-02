package com.guolongli.dinner.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.guolongli.dinner.domain.model.MediaType
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.CommentSheet
import com.guolongli.dinner.ui.components.CommentUi
import com.guolongli.dinner.ui.components.ConfirmDialog
import com.guolongli.dinner.ui.components.EmptyState
import com.guolongli.dinner.ui.components.LoadingState
import com.guolongli.dinner.ui.components.MediaThumbnail
import com.guolongli.dinner.ui.components.MediaThumbnailAdaptive
import com.guolongli.dinner.ui.components.PhotoViewer
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.components.SyncBadge
import com.guolongli.dinner.ui.components.ThinDivider
import com.guolongli.dinner.ui.components.VideoPlayerDialog
import com.guolongli.dinner.ui.components.mediaModel
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.util.syncLabelOf
import java.io.File

/**
 * 记录详情（只读）。
 *
 * 从时间线、日历、统计明细点进来，先看到「这条记录长什么样」：
 * 一张 Hero 大图、完整备注，再是一张收拢了时间 / 评分 / 位置 / 标签 / 作者 / 同步的
 * 元信息卡，最下面是评论区。
 * 要改内容必须显式点右上角「编辑」——展示与编辑分开，
 * 既避免误触改动别人的记录，也能安心看图（编辑页点开就是表单与选图控件）。
 */
@Composable
fun RecordDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: RecordDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "记录详情",
                subtitle = if (state.found) "${state.typeLabel} · ${state.dateLabel}" else null,
                onBack = onBack,
                trailing = {
                    if (state.canModify && !state.isDeleted) {
                        TextButton(onClick = { onEdit(state.recordId) }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(DinnerDimens.iconSm),
                            )
                            Spacer(modifier = Modifier.size(DinnerDimens.spaceXxs))
                            Text("编辑")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                LoadingState()
            }

            !state.found -> EmptyState(
                modifier = Modifier.padding(padding),
                message = "这条记录不在了",
                hint = "可能已在其他设备上删除",
            )

            else -> DetailContent(
                state = state,
                padding = padding,
                onRetryMedia = viewModel::retryMedia,
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: RecordDetailUiState,
    padding: PaddingValues,
    onRetryMedia: (String) -> Unit,
) {
    // 图片走查看器，视频走全屏播放器：两件事分开存，
    // 免得点视频只弹出一张封面（以前就是一个 viewerIndex 无条件进 PhotoViewer）。
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var videoPath by remember { mutableStateOf<String?>(null) }
    var commentsOpen by remember { mutableStateOf(false) }
    var commentInput by remember { mutableStateOf("") }
    var commentToDelete by remember { mutableStateOf<CommentUi?>(null) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scroll)
            .padding(
                start = DinnerDimens.screenPadding,
                end = DinnerDimens.screenPadding,
                bottom = DinnerDimens.spaceXl,
            ),
        verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
    ) {
        // 媒体：第一张做 Hero，其余三列方图。
        DetailMediaHero(
            media = state.media,
            onRetryMedia = onRetryMedia,
            onOpenImage = { index -> viewerIndex = index },
            onPlayVideo = { path -> videoPath = path },
        )

        if (state.note.isNotBlank()) {
            Text(
                text = state.note,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 元信息：时间 / 评分 / 位置 / 标签 / 作者 / 同步收进一张卡。
        DetailMetaCard(state = state)

        // 评论区：AGENTS.md §5 要求评论（含预览）在卡片与详情页一视同仁地展示，
        // 评论面板对全员开放。
        DetailCommentSection(
            comments = detailComments,
            onOpen = { commentsOpen = true },
        )
    }

    // 图片查看器：只有 IMAGE 会走到这里。
    viewerIndex?.let { index ->
        val media = state.media.getOrNull(index)
        if (media != null && media.mediaType == MediaType.IMAGE) {
            PhotoViewer(
                model = mediaModel(
                    localPath = media.localPath,
                    thumbnailPath = media.thumbPath,
                    mediaType = media.mediaType,
                ),
                imageDesc = "记录照片",
                onDismiss = { viewerIndex = null },
            )
        }
    }

    videoPath?.let { path ->
        VideoPlayerDialog(path = path, onDismiss = { videoPath = null })
    }

    if (commentsOpen) {
        CommentSheet(
            title = "${state.typeLabel} · ${state.timeLabel}",
            comments = detailComments,
            input = commentInput,
            isSending = false,
            errorMessage = null,
            onInputChange = { commentInput = it },
            // 发送与删除都要写库并推一次同步，属于 ViewModel 的职责。
            // RecordDetailViewModel 现在既没有 comments 状态也没有 sendComment /
            // deleteComment（详见修复报告），Screen 层无法凭空实现，
            // 这里先把面板与删除确认接好，不伪造「已发送」。
            onSend = {},
            onDelete = { comment -> commentToDelete = comment },
            onDismiss = { commentsOpen = false },
        )
    }

    // 删除评论也要二次确认：删除会同步给所有人。
    commentToDelete?.let { comment ->
        ConfirmDialog(
            title = "删除这条评论？",
            message = "「${comment.text.take(DinnerDimens.commentPreviewChars)}」\n\n" +
                "删除后会同步给所有人，不能恢复。",
            confirmText = "删除",
            onConfirm = { commentToDelete = null },
            onDismiss = { commentToDelete = null },
        )
    }
}

/**
 * 详情页的评论列表。
 *
 * [RecordDetailViewModel] 目前没有 comments 字段，也没有 sendComment /
 * deleteComment（缺什么见报告），Screen 层拿不到真实评论，只能给空列表。
 * ViewModel 补上之后把这一处换成 `state.comments` 即可，
 * 预览区与面板的渲染不用再动。
 */
private val detailComments: List<CommentUi> = emptyList()

/**
 * 媒体区：第一张做 Hero（4:3、radiusLg、跟随图片真实比例完整可见），
 * 其余三列方图。
 *
 * 之前是每张都全宽平铺，一条带四五张图的记录要滚很久才看到文字，
 * 观众也不知道哪张是主角。
 */
@Composable
private fun DetailMediaHero(
    media: List<DetailMediaUi>,
    onRetryMedia: (String) -> Unit,
    onOpenImage: (Int) -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    val first = media.firstOrNull() ?: return
    DetailMediaCell(
        media = first,
        hero = true,
        onRetryMedia = onRetryMedia,
        onOpenImage = { onOpenImage(0) },
        onPlayVideo = onPlayVideo,
    )

    val rest = media.withIndex().drop(1)
    if (rest.isEmpty()) return

    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
    // 分组结果按输入缓存：否则每次重组都要重新切一遍列表。
    val rows = remember(media) { rest.chunked(MEDIA_COLUMNS) }
    Column(verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
            ) {
                row.forEach { indexed ->
                    Box(modifier = Modifier.weight(1f)) {
                        DetailMediaCell(
                            media = indexed.value,
                            hero = false,
                            modifier = Modifier.aspectRatio(1f),
                            onRetryMedia = onRetryMedia,
                            onOpenImage = { onOpenImage(indexed.index) },
                            onPlayVideo = onPlayVideo,
                        )
                    }
                }
                repeat(MEDIA_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailMediaCell(
    media: DetailMediaUi,
    hero: Boolean,
    onRetryMedia: (String) -> Unit,
    onOpenImage: () -> Unit,
    onPlayVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 还在取 / 取失败要说清楚，不能让用户对着「待下载」发呆。
    val hint = when {
        media.fetchFailed -> "取回失败，轻点重试"
        media.isFetching -> "正在从云端取回…"
        media.remotePath == null -> "这件媒体只在本机有过，云端没有副本"
        else -> "正在从云端取回…"
    }
    val onClick: () -> Unit = {
        when (media.mediaType) {
            MediaType.VIDEO -> {
                // 视频要看正片：本地有文件就进全屏播放器（封面只是封面，
                // 放大一帧没有意义）；还没有就触发一次取回。
                val local = media.localPath
                if (local != null) onPlayVideo(local) else onRetryMedia(media.id)
            }
            MediaType.IMAGE -> {
                // ViewModel 给的 localPath / thumbPath 已经过存在性校验。
                if (media.localPath != null || media.thumbPath != null) {
                    onOpenImage()
                } else {
                    onRetryMedia(media.id)
                }
            }
        }
    }

    if (hero) {
        // Hero：跟随图片真实比例完整可见（极端长图会被夹在 mediaAspectMin..Max 之间），
        // 没解出尺寸前按 4:3 兜底，不为一张图把整页撑开。
        MediaThumbnailAdaptive(
            localPath = media.localPath,
            thumbnailPath = media.thumbPath,
            mediaType = media.mediaType,
            durationText = media.durationText,
            hint = hint,
            shape = DinnerDimens.radiusLg,
            aspectRatio = DinnerDimens.photoAspectRatio,
            onClick = onClick,
        )
    } else {
        MediaThumbnail(
            localPath = media.localPath,
            thumbnailPath = media.thumbPath,
            mediaType = media.mediaType,
            durationText = media.durationText,
            hint = hint,
            shape = DinnerDimens.radiusMd,
            aspectRatio = 1f,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

/** 元信息卡：时间 / 评分 / 位置 / 标签 / 作者 / 同步。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailMetaCard(state: RecordDetailUiState) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.typeLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(DinnerDimens.spaceXs))
            Text(
                text = "${state.dateLabel} ${state.timeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.marked) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "已加星标",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
            }
        }

        if (state.rating != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
            Text(
                text = "评分 ${state.rating}/5",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.locationText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
                Spacer(modifier = Modifier.size(DinnerDimens.spaceXxs))
                Text(
                    text = state.locationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
                verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs),
            ) {
                state.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(DinnerDimens.radiusFull))
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = DinnerDimens.alphaSubtle,
                                ),
                            )
                            .padding(
                                horizontal = DinnerDimens.spaceXs,
                                vertical = DinnerDimens.spaceXxs,
                            ),
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        ThinDivider()
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AuthorAvatar(name = state.authorName, file = state.authorAvatar)
            Spacer(modifier = Modifier.size(DinnerDimens.spaceXs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isMine) "${state.authorName}（我）" else state.authorName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "同步状态：${syncLabelOf(state.syncState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
            SyncBadge(state = state.syncState)
        }

        if (state.isDeleted) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            Text(
                text = "这条记录已删除，仅保留在本地",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 详情页底部的评论区：预览两条 + 「写评论」入口。
 *
 * 面板（[CommentSheet]）与删除确认都复用时间线那一套，行为两处一致。
 */
@Composable
private fun DetailCommentSection(
    comments: List<CommentUi>,
    onOpen: () -> Unit,
) {
    SectionCard(title = "评论") {
        if (comments.isEmpty()) {
            // 只给入口，不说「还没有人说话」：拿不到评论数据时那句话是一句
            // 无从核对的断言，等于在界面上猜。
            Text(
                text = "说点什么，评论会同步给所有人。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXxs)) {
                comments.takeLast(DinnerDimens.commentPreviewCount).forEach { comment ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${comment.authorName}：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = comment.text.let { raw ->
                                if (raw.length > DinnerDimens.commentPreviewChars) {
                                    raw.take(DinnerDimens.commentPreviewChars) + "…"
                                } else {
                                    raw
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (comments.size > DinnerDimens.commentPreviewCount) {
                    Text(
                        text = "共 ${comments.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        // 评论面板对全员开放：自己记的、别人记的，都能在这里说一句。
        if (comments.isEmpty()) {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text("写评论")
            }
        } else {
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text("看全部 / 写评论")
            }
        }
    }
}

@Composable
private fun AuthorAvatar(name: String, file: File?) {
    Box(
        modifier = Modifier
            .size(DinnerDimens.avatarSm)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                // 头像走裁切：圆形头像只需要人脸区域，不算「改变图片比例展示」。
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).ifBlank { "友" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 详情页里 Hero 之后的媒体每行几列。 */
private const val MEDIA_COLUMNS = 3
