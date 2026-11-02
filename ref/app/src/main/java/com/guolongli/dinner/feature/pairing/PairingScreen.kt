package com.guolongli.dinner.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.components.AppIconButton
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.AvatarCircle
import com.guolongli.dinner.ui.components.ConfirmDialog
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 成员管理。
 *
 * 不再假设只有两个人：这里管理上限 10 人的名单，
 * 加人只走「动态入群密码 + 管理员手机号」，真实身份来自网盘上的成员名单。
 */
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "家人与饭搭子",
                subtitle = "${state.members.size}/${state.memberLimit} 人",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(padding)
                    .padding(DinnerDimens.spaceXxl),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = DinnerDimens.screenPadding,
                    vertical = DinnerDimens.spaceSm,
                ),
            verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
        ) {
            SectionCard(title = "成员") {
                state.members.forEach { member ->
                    MemberRow(
                        member = member,
                        onRemove = { viewModel.askRemove(member) },
                        onRename = if (member.isMe) viewModel::openRenameDialog else null,
                    )
                }
                if (state.members.isEmpty()) {
                    Text(
                        text = "还没有成员。加入的人要靠动态入群密码进来，入口在「我的 → 成员 → 群组与入群密码」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 加人只走「动态入群密码 + 管理员手机号」，入口在
            // 「我的 → 成员 → 群组与入群密码」（GroupScreen）。这里只留成员名单。
            SectionCard(title = "怎么加人") {
                Text(
                    text = "要加人，去「我的 → 成员 → 群组与入群密码」" +
                        "换一个新的入群密码，把管理员手机号和密码私下告诉对方；" +
                        "对方在同页选「加入群组」，密码对上就能进来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.successMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (state.showRenameDialog) {
        RenameDialog(
            value = state.nameInput,
            onChange = viewModel::setRenameInput,
            onConfirm = viewModel::rename,
            onDismiss = viewModel::closeRenameDialog,
        )
    }

    state.removeTarget?.let { target ->
        ConfirmDialog(
            title = "移出「${target.name}」",
            message = "只会把 TA 从成员名单里去掉，TA 上传过的记录仍留在时间线上。",
            confirmText = "移出",
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

@Composable
private fun MemberRow(
    member: MemberUi,
    onRemove: () -> Unit,
    onRename: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DinnerDimens.touchTargetMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            name = member.name,
            size = DinnerDimens.avatarMd,
            isMine = member.isMe,
            image = member.avatar,
        )
        Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (member.isMe) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (member.isMe) {
            TextButton(onClick = { onRename?.invoke() }) {
                Text("改名")
            }
        } else {
            AppIconButton(
                onClick = onRemove,
                label = "移出 ${member.name}",
                icon = Icons.Outlined.Close,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(
    value: String,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改个名字", style = MaterialTheme.typography.titleSmall) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                label = { Text("昵称") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

