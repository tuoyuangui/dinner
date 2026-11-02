package com.guolongli.dinner.data.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.guolongli.dinner.core.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用**系统浏览器**打开授权页。
 *
 * 为什么不用应用内的 WebView：百度授权页对 WebView 有风控，且 AGENTS.md
 * 明确不引入自建 WebView UI。系统浏览器是官方推荐的做法，用户也能直接用
 * 已经登录的百度账号，少一次登录。
 *
 * 两个真实踩过的坑：
 * 1. **必须带 FLAG_ACTIVITY_NEW_TASK**。注入的是 Application Context，
 *    不加该 flag 会抛 `AndroidRuntimeException: Calling startActivity() from
 *    outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
 * 2. **必须有兜底**。部分定制系统没有自定义 Tabs provider，
 *    这里退回普通 ACTION_VIEW，仍然失败才判定「没有浏览器」。
 */
@Singleton
class ExternalBrowserLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 返回是否成功唤起浏览器。 */
    fun open(url: String): Boolean {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            Logger.w("没有可用的浏览器：${error::class.java.simpleName}")
            false
        } catch (error: SecurityException) {
            Logger.w("打开浏览器被拒绝：${error::class.java.simpleName}")
            false
        }
    }

    /** 当前设备是否有能处理 https 的 Activity。 */
    fun hasBrowser(): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pan.baidu.com"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    /** 打开应用商店的浏览器分类。返回是否成功唤起。 */
    fun openBrowserStore(): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=browser")),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=web%20browser&c=apps"),
            ),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
