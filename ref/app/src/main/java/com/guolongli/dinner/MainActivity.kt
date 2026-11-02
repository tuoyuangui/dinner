package com.guolongli.dinner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.guolongli.dinner.core.OAuthConfig
import com.guolongli.dinner.data.auth.OauthCallbackBroker
import com.guolongli.dinner.feature.lock.AppLockGate
import com.guolongli.dinner.ui.DinnerApp
import com.guolongli.dinner.ui.theme.DinnerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 唯一 Activity。
 *
 * 同时是授权回调的接收入口：union/oauth 授权完成后，浏览器会把
 * `dinner://oauth/callback?code=…&state=…` 交回这里（Manifest 里注册了
 * 该 scheme 的 intent-filter）。这里只负责把 URL 转交给
 * [OauthCallbackBroker]，不做任何业务判断——state 校验、换 token 都在数据层。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var callbackBroker: OauthCallbackBroker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动就带着回调 URL（授权期间进程被杀）也要处理。
        handleAuthCallback(intent)
        setContent {
            // 主题只在这一处套一次。
            //
            // 早先 AppLockGate 与 DinnerApp 各套一层 DinnerTheme，三层嵌套下来
            // 解锁瞬间整棵树要重建一遍（CompositionLocalProvider + MaterialTheme 全换），
            // 屏幕上表现为一次可见的闪白。现在闸门只负责盖住内容，主题由这里统一提供。
            DinnerTheme {
                // 应用锁包在最外层：锁定期间盖住业务界面，
                // 也不会把底部导航或通知里带出来的内容漏在屏幕上。
                AppLockGate {
                    DinnerApp()
                }
            }
        }
    }

    /**
     * 已在栈中时又收到回调：浏览器把应用从后台拉起来走的是这条路径
     * （Manifest 里 `launchMode="singleTask"`）。
     *
     * 不处理这条，「第二次授权」就永远收不到 code —— 第一次能成，之后再点授权
     * 界面就一直转圈。这是很容易漏掉的一处。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    /** 把回调 URL 交给 broker；不是授权回调就安静地忽略。 */
    private fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != OAuthConfig.REDIRECT_SCHEME) return
        if (data.host != OAuthConfig.REDIRECT_HOST) return
        // 不按「有没有人在等」来筛：进程可能被系统回收过，等待点已经没了，
        // 但回调仍必须被认出来（broker 会用落盘的 state 校验与暂存结果）。
        // 真正拦住过期链接的是 state 校验本身。
        callbackBroker.handleCallback(data)
    }
}
