package com.guolongli.dinner.feature.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagOrangeFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/**
 * 应用锁。
 *
 * 只有一条路：**系统身份验证**。指纹 / 人脸，Android 11 起还能在同一个弹窗里
 * 换成锁屏密码；API 30 以下 `BiometricPrompt` 不允许二选一，没有录指纹的设备
 * 就走 `KeyguardManager` 的系统确认页。
 *
 * 为什么不再保留「应用内 4~6 位密码」：README 写的是「应用本身不保存任何口令」。
 * 自己存一份 PIN，安全性不会超过系统锁屏，却多出一份明文/半明文的口令，
 * 忘记之后还会把用户锁在自己的记录外面。真机没设锁屏时，
 * 应用锁本来就是「防不住」的，如实说清楚比给一个假护栏好。
 *
 * 不做「错三次自毁」这类花活：这是本机自用的抽屉锁，真正的数据保护是网盘授权。
 */
@Composable
fun AppLockScreen(
    onVerified: () -> Unit,
    /** 设备没有可用的系统凭证时调用：不能把用户锁在自己的记录外面 */
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val keyguard = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    // 这台设备能弹哪种系统验证：进页面时算一次。
    // 优先「强生物特征」（指纹 / 安全人脸）——系统指纹验证走的就是这一档；
    // 只录了普通人脸的设备退到 BIOMETRIC_WEAK，Android 11 起再并上锁屏密码。
    val authenticators = remember(context) {
        val manager = BiometricManager.from(context)
        val strongOrDevice = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val weakOrDevice = BIOMETRIC_OR_DEVICE
        fun usable(flags: Int): Boolean =
            manager.canAuthenticate(flags) == BiometricManager.BIOMETRIC_SUCCESS
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && usable(strongOrDevice) -> strongOrDevice
            usable(BiometricManager.Authenticators.BIOMETRIC_STRONG) ->
                BiometricManager.Authenticators.BIOMETRIC_STRONG

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && usable(weakOrDevice) -> weakOrDevice
            usable(BiometricManager.Authenticators.BIOMETRIC_WEAK) ->
                BiometricManager.Authenticators.BIOMETRIC_WEAK

            else -> 0
        }
    }
    val canPrompt = activity != null && authenticators != 0
    val canUseKeyguard = remember(keyguard) { keyguard?.isDeviceSecure == true }
    val available = canPrompt || canUseKeyguard

    var message by remember { mutableStateOf<String?>(null) }
    var autoShown by remember { mutableStateOf(false) }

    fun promptSystem() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onVerified()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户自己关掉不算失败，别拿红字追着喊
                    message = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> null

                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        -> "尝试次数过多，请用锁屏密码解锁手机后再试"

                        else -> "没验过去：${errString.ifBlank { "再试一次" }}"
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁食伴")
            .setSubtitle("验证身份后继续")
            .setAllowedAuthenticators(authenticators)
            .apply {
                // 只允许生物特征时必须留一个出口，否则 build() 直接抛异常
                if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                    setNegativeButtonText("取消")
                }
            }
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { message = "这台设备暂时弹不出身份验证，请稍后再试" }
    }

    val keyguardPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) onVerified() }

    fun promptKeyguard() {
        @Suppress("DEPRECATION")
        val intent = keyguard?.createConfirmDeviceCredentialIntent("解锁食伴", "验证身份后继续")
        if (intent == null) {
            message = "这台设备没有设置锁屏密码，应用锁无法使用"
        } else {
            runCatching { keyguardPrompt.launch(intent) }
                .onFailure { message = "打不开系统验证页，请稍后再试" }
        }
    }

    // 设备根本没有可用凭证：直接放行，并在「我的」里提示把开关关掉
    LaunchedEffect(available) {
        if (!available) onUnavailable()
    }

    // 一进来就把系统验证弹出来：锁屏页多按一次按钮没有意义
    LaunchedEffect(canPrompt) {
        if (canPrompt && !autoShown) {
            autoShown = true
            promptSystem()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PagePadding, vertical = Dimens.GapXl * 4),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "食伴已上锁", style = DinnerText.screenTitle, color = TextPrimary)
            Text(text = "验证身份后继续", style = DinnerText.caption, color = TextSecondary)

            when {
                canPrompt -> SystemUnlockButton(
                    label = when {
                        authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0 ->
                            "用指纹 / 人脸 / 锁屏密码解锁"

                        authenticators and BiometricManager.Authenticators.BIOMETRIC_STRONG != 0 ->
                            "用指纹 / 人脸解锁"

                        else -> "用指纹 / 人脸解锁"
                    },
                    onClick = { message = null; promptSystem() },
                )

                canUseKeyguard -> SystemUnlockButton(
                    label = "用锁屏密码解锁",
                    onClick = { message = null; promptKeyguard() },
                )
            }

            message?.let {
                Text(text = it, style = DinnerText.footnote, color = TagOrangeFg, textAlign = TextAlign.Center)
            }

            Text(
                text = "应用锁用的是手机自己的指纹 / 人脸 / 锁屏密码，食伴不保存任何口令。" +
                    "想关掉它：「我的 → 应用锁」。",
                style = DinnerText.footnote,
                color = TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SystemUnlockButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(BrandBlue)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = DinnerText.bodyLarge, color = SurfaceWhite)
    }
}

/** 生物特征或锁屏密码，Android 11 起才可以放进同一个弹窗 */
private val BIOMETRIC_OR_DEVICE = BiometricManager.Authenticators.BIOMETRIC_WEAK or
    BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * `LocalContext` 可能包了一层 `ContextThemeWrapper`，而 `BiometricPrompt` 只认
 * `FragmentActivity`——沿 wrapper 链往上找。找不到（预览 / 测试环境）返回 null，
 * 界面自动退回锁屏密码那条路。
 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
