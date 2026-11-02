package com.guolongli.dinner.feature.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 应用锁闸门：关着就直接放行，开着且未解锁就在内容上盖一层不透明验证页。
 *
 * 只盖住内容，不做任何导航拦截——解锁后原来的页面还在原处，滚动位置也不丢。
 *
 * 两个刻意的选择：
 * - 主题由 MainActivity 统一提供，这里不再套 DinnerTheme：套了就多一层
 *   CompositionLocalProvider，解锁时整棵树要重建，屏幕上会闪一下白。
 * - 锁定时**仍然渲染 content**，只是用不透明 Surface 盖住。
 *   解锁时少了「整棵子树从无到有」的一次性组合，也就没有闪白。
 */
@Composable
fun AppLockGate(
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ready = false 是「还没读到开关状态」：这时也盖住，避免开着锁却先闪出内容。
    val covered = !state.ready || state.locked

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (covered) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (state.ready) {
                    LockContent(
                        errorMessage = state.errorMessage,
                        onUnlock = viewModel::unlock,
                        onError = viewModel::reportError,
                        onDisable = viewModel::disableAndUnlock,
                    )
                }
            }
        }
    }
}

@Composable
private fun LockContent(
    errorMessage: String?,
    onUnlock: () -> Unit,
    onError: (String?) -> Unit,
    onDisable: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context.findActivity()
    val deviceSecure = context.isDeviceSecure()

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onUnlock() else onError("未通过验证")
    }

    val requestAuth: () -> Unit = {
        when {
            activity == null -> onError("当前界面无法启动身份验证，请重新打开应用")
            context.hasBiometricHardware() && deviceSecure ->
                startBiometric(activity, onUnlock, onError) {
                    // 生物识别不可用（被锁死 / 硬件暂时不可用）时退回锁屏密码。
                    launchDeviceCredential(activity, credentialLauncher, onError)
                }
            deviceSecure ->
                launchDeviceCredential(activity, credentialLauncher, onError)
            else -> onError("系统里还没设置锁屏密码或指纹，请先去系统设置里设置")
        }
    }

    // 进来就弹一次，省得用户还要多点一下；失败后可以手动再点按钮。
    LaunchedEffect(Unit) { requestAuth() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DinnerDimens.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(DinnerDimens.iconXl),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        Text(
            text = "食伴已上锁",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        Text(
            text = "用指纹、人脸或锁屏密码验证后才能看到里面的记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        val message = errorMessage
        if (message != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(DinnerDimens.spaceLg))
        Button(
            onClick = requestAuth,
            modifier = Modifier
                .fillMaxWidth()
                .height(DinnerDimens.touchTargetMin),
        ) {
            Text("验证身份")
        }

        if (!deviceSecure) {
            TextButton(onClick = onDisable) {
                Text("系统未设置锁屏，先关掉应用锁")
            }
        }
    }
}

/** 弹系统生物识别。成功回调解锁，硬件层面不可用时回调 fallback。 */
private fun startBiometric(
    activity: Activity,
    onSuccess: () -> Unit,
    onError: (String?) -> Unit,
    onFallback: () -> Unit,
) {
    val executor = activity.mainExecutor
    val builder = BiometricPrompt.Builder(activity)
        .setTitle("验证身份")
        .setSubtitle("解锁食伴")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10+ 可以一次弹窗覆盖指纹 / 人脸 / 锁屏密码。
        //
        // `setDeviceCredentialAllowed` 在 Android 11 起被标 deprecated，
        // 官方建议改用 `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`。
        // 但后者在 API 30 才可用，而本项目 minSdk = 28：为了不引入
        // androidx.biometric（AGENTS 技术栈未列此项），这里保留旧 API，
        // 只在「确实只有它可用」的这个分支上抑制告警。
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    } else {
        builder.setNegativeButton("取消", executor) { _, _ -> onError(null) }
    }

    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            when (errorCode) {
                BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
                -> onFallback()

                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                -> onError(null)

                else -> onError(errString?.toString() ?: "验证失败，请重试")
            }
        }
    }

    runCatching {
        builder.build().authenticate(CancellationSignal(), executor, callback)
    }.onFailure {
        // 例如设备根本没有生物识别硬件：直接走密码那条路。
        onFallback()
    }
}

/** 退到系统锁屏凭据（PIN / 图案 / 密码）。 */
private fun launchDeviceCredential(
    activity: Activity,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    onError: (String?) -> Unit,
) {
    val keyguard = activity.getSystemService(KeyguardManager::class.java)
    // 同上：`createConfirmDeviceCredentialIntent` 在 Android 11 起 deprecated，
    // 取代它的 BiometricPrompt 凭据组合要 API 30，minSdk = 28 仍需旧路径兜底。
    @Suppress("DEPRECATION")
    val intent = keyguard?.createConfirmDeviceCredentialIntent("验证身份", "解锁食伴")
    if (intent == null) {
        onError("这台设备没有可用的锁屏凭据，请先去系统设置里设置")
        return
    }
    runCatching { launcher.launch(intent) }
        .onFailure { onError("无法打开身份验证，请重试") }
}

private fun Context.hasBiometricHardware(): Boolean {
    val pm = packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
        pm.hasSystemFeature(PackageManager.FEATURE_FACE) ||
        pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
}

private fun Context.isDeviceSecure(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/** 从 Compose 的 Context 里找出宿主 Activity。 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
