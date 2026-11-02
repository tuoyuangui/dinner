package com.guolongli.dinner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 作者头像：圆形底 + 昵称首字。
 *
 * 不用位图资源，避免为一个占位图形引入额外资产；中文昵称下首字辨识度也足够。
 * 自己用主色容器、对方用次色容器，便于一眼区分。
 */
@Composable
fun AvatarCircle(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = DinnerDimens.avatarMd,
    isMine: Boolean = false,
    image: Any? = null,
) {
    val container = if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = "$name 的头像" },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            coil3.compose.AsyncImage(
                model = image,
                contentDescription = "$name 的头像",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                text = initialOf(name),
                color = content,
                fontSize = initialFontSize(size),
                lineHeight = initialFontSize(size),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** 昵称首字；空白昵称回退为「友」，避免出现空白圆圈。 */
fun initialOf(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.isEmpty()) "友" else trimmed.substring(0, 1)
}

/**
 * 首字字号：按头像尺寸离散映射到字号阶梯。
 *
 * 早先是「按尺寸比例现算 sp」，算出来的值（比如 22.4sp）不在 Type.kt 的阶梯里，
 * 同一个首字在不同页会出现一堆互不相同的字号。改成三档映射后，
 * 字号只可能取阶梯上的值，跨页面一致。
 */
private fun initialFontSize(size: Dp): TextUnit = when {
    size <= DinnerDimens.avatarMd -> DinnerDimens.fontCaption
    size <= DinnerDimens.avatarLg -> DinnerDimens.fontTitle
    else -> DinnerDimens.fontStat
}
