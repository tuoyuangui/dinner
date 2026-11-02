package com.guolongli.dinner.feature.about

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.guolongli.dinner.ui.component.DialogCard
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/** 开发者署名，写在一处：关于页展示的都是这一行 */
private const val DEVELOPER_NAME = "Guolong Li"
private const val DEVELOPER_EMAIL = "guolongli_upc@foxmail.com"

/** MIT 协议全文，「开发协议」卡片点开即看 */
private val MIT_TEXT = """
    MIT License

    Copyright (c) 2026 $DEVELOPER_NAME

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
""".trimIndent()

/**
 * 关于食伴。
 *
 * 这一页只回答四个问题：数据存在哪、删了会怎样、谁做的、什么协议。
 * 隐私说明写在这里而不是塞进首次启动的弹窗——用户想起来的时候要找得到。
 * 应用图标用系统里注册的那个（桌面图标同款），不是另画一个徽标。
 */
@Composable
fun AboutScreen(
    versionName: String,
    authorized: Boolean,
    storageDir: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var licenseOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(PageBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                LineIcon(spec = LineIconSpec.ARROW_LEFT, tint = TextPrimary, size = 20.dp)
            }
            Text(text = "关于食伴", style = DinnerText.screenTitle, color = TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Dimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapXl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
            ) {
                SystemAppIcon(size = Dimens.AvatarLarge)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "食伴", style = DinnerText.appTitle, color = TextPrimary)
                    Text(
                        text = "版本 $versionName · dinner",
                        style = DinnerText.caption,
                        color = TextSecondary,
                    )
                }
            }

            InfoCard(
                title = "数据存在你自己的网盘里",
                body = "记录、照片、视频、评论都放在百度网盘的 $storageDir 目录下，" +
                    "由你自己授权、自己保管。应用不经过任何第三方服务器，" +
                    "也没有账号体系——谁能看到，取决于谁拿到了你的网盘授权。",
            )

            InfoCard(
                title = if (authorized) "当前状态：已授权" else "当前状态：未授权",
                body = if (authorized) {
                    "这台设备已经能读写网盘。换手机时重新授权一次，历史记录会自动取回。"
                } else {
                    "还没连上网盘，现在记的内容只在本机。去「我的 → 网盘连接」扫一次码即可。"
                },
            )

            InfoCard(
                title = "删掉应用会怎样",
                body = "本机的数据库、照片副本和授权凭证会一起消失；网盘上的那份不会。" +
                    "重新安装并授权后，记录会重新拉回来，照片点开时再下载。",
            )

            InfoCard(
                title = "关于评分与评论",
                body = "评分是 0~5 颗星，评论跟着记录一起存在同一个 JSON 里。" +
                    "删除评论会同步给所有人——这也意味着删掉之后无法从别人那里找回。",
            )

            InfoCard(
                title = "开发者",
                body = "$DEVELOPER_NAME（$DEVELOPER_EMAIL）\n" +
                    "有建议或遇到问题，欢迎来信。",
            )

            InfoCard(
                title = "开发协议",
                body = "本项目以 MIT License 开源。",
                linkLabel = "查看 MIT 协议全文",
                onLinkClick = { licenseOpen = true },
            )

            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (licenseOpen) {
        LicenseDialog(onDismiss = { licenseOpen = false })
    }
}

/**
 * 应用图标——用系统里注册的那个（桌面图标同款）。
 * 旧版这里画了个「食」字徽标，和桌面对不上，换掉。
 */
@Composable
private fun SystemAppIcon(size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(size.value.toInt() * 3, size.value.toInt() * 3)
                .asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "食伴应用图标",
                modifier = Modifier.fillMaxSize().padding(Dimens.GapXs),
            )
        } else {
            // 取不到系统图标（预览 / 极端环境）就退回一个「食」字，不留空洞
            Text(text = "食", style = DinnerText.appTitle.copy(fontSize = 22.sp), color = BrandBlue)
        }
    }
}

/** MIT 协议全文弹窗 */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    DialogCard(onDismiss = onDismiss) {
        Text(text = "MIT License", style = DinnerText.navTitle, color = TextPrimary)
        Column(
            modifier = Modifier
                .height(320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = MIT_TEXT,
                style = DinnerText.footnote,
                color = TextSecondary,
            )
        }
        Text(
            text = "关闭",
            style = DinnerText.bodySmallMedium,
            color = BrandBlue,
            modifier = Modifier
                .align(Alignment.End)
                .clickable { onDismiss() }
                .padding(Dimens.GapS),
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    linkLabel: String? = null,
    onLinkClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.RadiusCard))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard))
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
    ) {
        Text(text = title, style = DinnerText.bodyMedium, color = TextPrimary)
        Text(text = body, style = DinnerText.caption, color = TextSecondary)
        if (linkLabel != null && onLinkClick != null) {
            Text(
                text = linkLabel,
                style = DinnerText.caption,
                color = BrandBlue,
                modifier = Modifier
                    .clickable { onLinkClick() }
                    .padding(top = Dimens.GapXs),
            )
        }
    }
}
