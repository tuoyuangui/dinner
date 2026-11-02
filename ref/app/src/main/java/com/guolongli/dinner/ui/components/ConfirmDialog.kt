package com.guolongli.dinner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme

/**
 * 二次确认对话框。
 *
 * 用于不可逆操作（解除授权、解除配对、删除记录等）。
 * 危险操作不说废话：标题上方给一个 errorContainer 圆形图标底，
 * 确认按钮做成实心危险按钮（而不是一枚红色文字），圆角与阴影走令牌。
 * 文案由调用方提供。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String = "取消",
    destructive: Boolean = true,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(DinnerDimens.radiusCard),
        tonalElevation = DinnerDimens.elevationModal,
        title = {
            Column {
                if (destructive) {
                    ConfirmDangerIconBadge()
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                }
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
        },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(DinnerDimens.radiusCard),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(text = confirmText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(DinnerDimens.radiusCard),
            ) {
                // 次要操作文字用强调语义色：primary 直接当文字色在浅底上不达 AA。
                Text(text = dismissText, color = DinnerTheme.colors.textAccent)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** 危险确认的圆形图标底，图标与 [ConfirmDialog] 的语义一致。 */
@Composable
private fun ConfirmDangerIconBadge() {
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
