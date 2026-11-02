package com.guolongli.dinner.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.guolongli.dinner.domain.model.SyncState
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.theme.DinnerColors
import com.guolongli.dinner.ui.util.syncLabelOf

private val VISIBLE_STATES = setOf(SyncState.PENDING, SyncState.SYNCING, SyncState.FAILED)

private fun syncColor(colors: DinnerColors, state: SyncState): Color = when (state) {
    SyncState.PENDING -> colors.syncPending
    SyncState.SYNCING -> colors.syncSyncing
    SyncState.SYNCED -> colors.syncSynced
    SyncState.FAILED -> colors.syncFailed
}

/**
 * 单条记录的同步状态徽标。
 *
 * 只在 PENDING / SYNCING / FAILED 时显示——已同步 (SYNCED) 是正常态，
 * 不显示徽标，避免视觉噪音（同步状态仍如实反映在「我的」页面的统计里）。
 *
 * 颜色既用于文字也用于圆点，保证对比度与语义一致，不假装成功。
 */
@Composable
fun SyncBadge(
    state: SyncState,
    modifier: Modifier = Modifier,
) {
    val colors = DinnerTheme.colors
    AnimatedVisibility(
        visible = state in VISIBLE_STATES,
        enter = fadeIn(animationSpec = tween(DinnerDimens.animStandardMs)),
        exit = fadeOut(animationSpec = tween(DinnerDimens.animStandardMs)),
    ) {
        val tint = syncColor(colors, state)
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(DinnerDimens.dotSize)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
            Text(
                text = syncLabelOf(state),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
        }
    }
}
