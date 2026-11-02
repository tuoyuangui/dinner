package com.guolongli.dinner.feature.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.ui.component.PrimaryButton
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/**
 * 上次启动崩了，这次先把原因摆出来。
 *
 * 与其让用户对着白屏猜，不如直接告诉他「哪一行挂了」，并且给一条明确的出路：
 * 继续用（清掉记录，下次再崩还会回来）或者复制发给开发者。
 *
 * 这一屏刻意不碰仓储、不碰 ViewModel——它必须在「别的东西都起不来」的时候也能显示。
 */
@Composable
fun CrashReportScreen(
    report: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "上次启动没成功",
    continueLabel: String = "继续使用",
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        Text(text = title, style = DinnerText.screenTitle, color = TextPrimary)
        Text(
            text = "下面是上一次的崩溃信息。记录都在你自己的百度网盘里，" +
                "重装应用不会丢，别急着清数据。",
            style = DinnerText.caption,
            color = TextSecondary,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.RadiusCard))
                .background(SurfaceWhite)
                .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusCard)),
        ) {
            Text(
                text = report,
                style = DinnerText.footnote.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                ),
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.CardPadding),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(text = continueLabel, onClick = onContinue)
            }
            SecondaryAction(text = "复制详情") { copyToClipboard(context, report) }
        }

        Text(
            text = "如果每次打开都停在这一页，请把上面的内容复制给开发者。",
            style = DinnerText.footnote,
            color = TextTertiary,
        )
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(SurfaceWhite)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusSmall))
            .clickable { onClick() }
            .padding(horizontal = Dimens.GapXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DinnerText.bodySmallMedium, color = BrandBlue)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null) {
        Toast.makeText(context, "复制不了，截图也行", Toast.LENGTH_SHORT).show()
        return
    }
    manager.setPrimaryClip(ClipData.newPlainText("食伴 · 崩溃信息", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
