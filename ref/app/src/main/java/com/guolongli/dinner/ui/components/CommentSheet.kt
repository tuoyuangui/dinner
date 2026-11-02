package com.guolongli.dinner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme
import java.io.File

/** 一条评论的展示数据。 */
@Immutable
data class CommentUi(
    val id: String,
    val authorName: String,
    val text: String,
    val timeText: String,
    val isMine: Boolean,
    val avatar: File? = null,
    val canDelete: Boolean = false,
)

/**
 * 评论面板。
 *
 * 用 Dialog 而不是底部弹层：时间线是长列表，弹层容易被列表滚动状态干扰，
 * 且输入法弹起时 Dialog 的 imePadding 行为更可预期。
 */
@Composable
fun CommentSheet(
    title: String,
    comments: List<CommentUi>,
    input: String,
    isSending: Boolean,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onDelete: (CommentUi) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DinnerDimens.screenPadding)
                .imePadding(),
            shape = RoundedCornerShape(DinnerDimens.radiusCard),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = DinnerDimens.elevationRaised,
        ) {
            Column(modifier = Modifier.padding(DinnerDimens.spaceSm)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))

                if (comments.isEmpty()) {
                    Text(
                        text = "还没有人说话，来第一句。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = DinnerDimens.spaceSm),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = DinnerDimens.commentListMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                    ) {
                        items(comments, key = { it.id }) { comment ->
                            CommentRow(comment = comment, onDelete = { onDelete(comment) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么") },
                        maxLines = 3,
                        shape = RoundedCornerShape(DinnerDimens.radiusMd),
                    )
                    Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                    IconButton(
                        onClick = onSend,
                        enabled = !isSending && input.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "发送评论",
                            tint = if (input.isNotBlank()) {
                                // primary 当图标前景色在浅底上只有 2.6:1，换成强调文字色。
                                DinnerTheme.colors.textAccent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: CommentUi, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DinnerDimens.radiusMd))
            .padding(vertical = DinnerDimens.spaceXxs),
        verticalAlignment = Alignment.Top,
    ) {
        AvatarCircle(
            name = comment.authorName,
            size = DinnerDimens.avatarSm,
            isMine = comment.isMine,
            image = comment.avatar,
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                Text(
                    text = comment.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (comment.canDelete) {
            AppIconButton(
                onClick = onDelete,
                label = "删除这条评论",
                icon = Icons.Outlined.DeleteOutline,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
