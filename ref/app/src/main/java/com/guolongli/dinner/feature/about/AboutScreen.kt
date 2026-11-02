package com.guolongli.dinner.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.AvatarCircle
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 关于页。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    versionName: String = "1.0.0",
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "关于食伴", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DinnerDimens.screenPadding, vertical = DinnerDimens.spaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.size(DinnerDimens.spaceLg))
            AvatarCircle(
                name = "食",
                isMine = true,
                size = DinnerDimens.avatarXl,
            )
            Spacer(modifier = Modifier.size(DinnerDimens.spaceSm))
            Text(
                text = "食伴",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "版本 $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.size(DinnerDimens.spaceLg))

            SectionCard(title = "关于") {
                Text(
                    text = "食伴用来记录情侣之间分享的一日三餐和恋爱日常。照片与记录保存在你们共用的百度网盘，数据归你们自己所有，不经过第三方服务器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
            }

            Spacer(modifier = Modifier.size(DinnerDimens.spaceSm))

            SectionCard(title = "开发者") {
                Text(
                    text = "Guolong Li <guolongli_upc@foxmail.com>",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.size(DinnerDimens.spaceSm))

            SectionCard(title = "许可") {
                Text(
                    text = "本项目以 MIT License 开源。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}