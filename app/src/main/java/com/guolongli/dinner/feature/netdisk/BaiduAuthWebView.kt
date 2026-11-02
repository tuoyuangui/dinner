package com.guolongli.dinner.feature.netdisk

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary

/**
 * 百度授权页。
 *
 * 两种回调形态都兜住：
 * - redirect_uri 配成了自己的地址：shouldOverrideUrlLoading 里截 code；
 * - redirect_uri = oob（默认）：百度会在页面上直接显示授权码，页面加载完读正文抓 32 位十六进制。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BaiduAuthWebView(
    url: String,
    redirectUri: String,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    var handled by remember { mutableStateOf(false) }

    fun accept(code: String) {
        if (handled || code.isBlank()) return
        handled = true
        onCode(code)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(SurfaceWhite)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Dimens.PagePadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "百度账号授权", style = DinnerText.navTitle, color = TextPrimary)
                Text(
                    text = "关闭",
                    style = DinnerText.bodyMedium,
                    color = BrandBlue,
                    modifier = Modifier.clickable { onClose() },
                )
            }

            Text(
                text = "授权后只读写 /apps/dinner/ 目录，不碰网盘里的其他文件",
                style = DinnerText.caption,
                color = TextSecondary,
                modifier = Modifier.padding(
                    start = Dimens.PagePadding,
                    end = Dimens.PagePadding,
                    bottom = Dimens.GapM,
                ),
            )

            Box(Modifier.weight(1f).fillMaxWidth().background(BrandBlue.copy(alpha = 0.04f))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String,
                                ): Boolean {
                                    if (redirectUri.isNotBlank() &&
                                        redirectUri != "oob" &&
                                        url.startsWith(redirectUri)
                                    ) {
                                        Regex("code=([^&]+)")
                                            .find(url)
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.let { accept(it) }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    if (redirectUri != "oob") return
                                    view.evaluateJavascript("document.body.innerText") { text ->
                                        Regex("[0-9a-fA-F]{32}")
                                            .find(text.orEmpty())
                                            ?.value
                                            ?.let { accept(it) }
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    },
                )
            }
        }
    }
}
