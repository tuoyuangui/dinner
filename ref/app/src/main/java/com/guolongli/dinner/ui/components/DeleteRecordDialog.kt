package com.guolongli.dinner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 删除一条记录时的三档选择。
 *
 * 分档的意义在于「本机不想看」和「让所有人都删掉」是两件事：
 * 只删本地不动云端，别人仍能看到；删云端会连带删掉网盘上的原文件。
 *
 * 选定档位后还要再确认一次：删除不可撤销，一次误触不该造成不可恢复的数据丢失。
 * 第二步只留一个危险按钮，默认焦点落在「再想想」上。
 *
 * [canDeleteCloud] 为 false 时（不是自己上传的记录）只保留「只删本地」，
 * 避免误删他人的内容。
 */
@Composable
fun DeleteRecordDialog(
    canDeleteCloud: Boolean,
    onDeleteLocal: () -> Unit,
    onDeleteCloud: () -> Unit,
    onDeleteBoth: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf<DeleteMode?>(null) }

    val mode = pending
    if (mode == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(DinnerDimens.radiusCard),
            tonalElevation = DinnerDimens.elevationModal,
            title = { Text("删除这条记录", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    if (canDeleteCloud) {
                        DeleteOption(
                            title = DeleteMode.LOCAL.title,
                            subtitle = "这台设备不再显示，云端和别人都不受影响",
                            onClick = { pending = DeleteMode.LOCAL },
                        )
                        DeleteOption(
                            title = DeleteMode.CLOUD.title,
                            subtitle = "通知所有人删除，并删掉网盘上的原文件",
                            onClick = { pending = DeleteMode.CLOUD },
                            destructive = true,
                        )
                        DeleteOption(
                            title = DeleteMode.BOTH.title,
                            subtitle = "两边一起清掉，不可恢复",
                            onClick = { pending = DeleteMode.BOTH },
                            destructive = true,
                        )
                    } else {
                        Text(
                            text = "这条不是你上传的，只能从本机移除；云端内容由上传者决定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                        DeleteOption(
                            title = DeleteMode.LOCAL.title,
                            subtitle = "这台设备不再显示，其他人的时间线不变",
                            onClick = { pending = DeleteMode.LOCAL },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    } else {
        AlertDialog(
            onDismissRequest = { pending = null },
            shape = RoundedCornerShape(DinnerDimens.radiusCard),
            tonalElevation = DinnerDimens.elevationModal,
            title = {
                Column {
                    // 危险图标底：先看到一个醒目的红底圆，再读标题。
                    DangerIconBadge()
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                    Text("再确认一次", style = MaterialTheme.typography.titleSmall)
                }
            },
            text = {
                Column {
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                    Text(
                        text = if (mode == DeleteMode.LOCAL) {
                            "记录仍留在云端，随时可以再从网盘取回。"
                        } else {
                            "删掉之后找不回来，也不能恢复。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                // 真正执行的那一档做成实心危险按钮：一枚红色文字太容易被当成普通链接。
                Button(
                    onClick = {
                        pending = null
                        when (mode) {
                            DeleteMode.LOCAL -> onDeleteLocal()
                            DeleteMode.CLOUD -> onDeleteCloud()
                            DeleteMode.BOTH -> onDeleteBoth()
                        }
                    },
                    shape = RoundedCornerShape(DinnerDimens.radiusCard),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text("确认删除", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                    shape = RoundedCornerShape(DinnerDimens.radiusCard),
                ) {
                    Text("再想想", color = DinnerTheme.colors.textAccent)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/** 危险确认对话框顶部的圆形图标底，用 errorContainer / onErrorContainer 成对取色。 */
@Composable
private fun DangerIconBadge() {
    Box(
        modifier = Modifier
            .size(DinnerDimens.decorCircleMd)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(DinnerDimens.iconLg),
        )
    }
}

/** 三档删除各自的后果说明，第二步确认直接复用，避免文案两处走偏。 */
private enum class DeleteMode(val title: String, val description: String) {
    LOCAL(
        title = "只删本地",
        description = "这条记录会从这台设备移除，云端和其他人的时间线都不受影响。",
    ),
    CLOUD(
        title = "只删云端",
        description = "所有人都不再看到这条记录，网盘上的照片与视频会被删掉。",
    ),
    BOTH(
        title = "本地和云端都删",
        description = "这台设备与云端一起清除，两边都不再保留。",
    ),
}

@Composable
private fun DeleteOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DinnerDimens.radiusMd))
            // 点击区必须 >= 48dp（AGENTS UI 红线）：只靠文字行高撑不起来，
            // 尤其两步确认的第一步，选项本身是唯一的入口。
            .heightIn(min = DinnerDimens.touchTargetMin)
            .clickable(onClick = onClick)
            .padding(vertical = DinnerDimens.spaceXs, horizontal = DinnerDimens.spaceXxs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
