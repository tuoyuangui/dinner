package com.guolongli.dinner.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 网盘授权页：录入开放平台 AppKey / SecretKey，再用百度 OAuth SDK 完成授权。
 *
 * 分成两步呈现：先存凭证，再发起授权。两步都做完了才有「已授权」。
 *
 * 授权页由**系统浏览器**打开百度网盘开放平台的快速授权页（union/oauth）。
 * 应用不承载任何授权 UI（AGENTS 禁止自建 WebView），
 * 回调通过 `dinner://oauth/callback` 交给 MainActivity 再接回来。
 */
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    onAuthed: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var appKeyInput by remember { mutableStateOf(state.appKey) }
    var secretInput by remember { mutableStateOf(state.secretKey) }

    // 已保存凭证异步回填时，同步到输入框，避免已存凭证不回显。
    LaunchedEffect(state.appKey) {
        appKeyInput = state.appKey
    }
    LaunchedEffect(state.secretKey) {
        secretInput = state.secretKey
    }

    // 授权成功后由导航层离开。用 isAuthorized 而不是一次性事件：
    // 这个页面在授权成功时可能刚好被系统回收重建，单靠回调会丢掉。
    LaunchedEffect(state.isAuthorized) {
        if (state.isAuthorized) onAuthed()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "网盘授权", subtitle = "数据存在你自己的网盘里", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DinnerDimens.screenPadding,
                    end = DinnerDimens.screenPadding,
                    top = DinnerDimens.spaceXs,
                    bottom = DinnerDimens.spaceLg,
                ),
            verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
        ) {
            StepIndicator(
                credentialDone = state.fullyConfigured,
                authorized = state.isAuthorized,
            )

            if (state.isAuthorized) {
                AuthorizedBanner()
            }

            SectionCard(title = "1 · 开放平台凭证") {
                Text(
                    text = "在百度开放平台创建应用，拿到 AppKey 与 SecretKey 填到这里。" +
                        "凭证用系统 Keystore 加密保存在本机，不上传任何第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                OutlinedTextField(
                    value = appKeyInput,
                    onValueChange = {
                        appKeyInput = it
                        viewModel.setAppKey(it)
                    },
                    label = { Text("AppKey") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = {
                        secretInput = it
                        viewModel.setSecretKey(it)
                    },
                    label = { Text("SecretKey") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                Button(
                    onClick = { viewModel.saveCredentials(appKeyInput, secretInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DinnerDimens.touchTargetMin),
                ) {
                    Text("保存凭证")
                }
            }

            SectionCard(title = "2 · 授权网盘") {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DinnerDimens.spaceSm),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = DinnerDimens.progressStroke)
                    }
                } else {
                    Button(
                        onClick = viewModel::startAuth,
                        enabled = state.canStartAuth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DinnerDimens.touchTargetMin),
                    ) {
                        Text(if (state.launchingAuth) "正在打开百度授权页…" else "开始授权")
                    }
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                    Text(
                 text = "授权页会用系统浏览器打开（百度网盘开放平台的快速授权）。" +
                            "在页面上登录、勾选要授权的目录并确认，完成后会自动回到本应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.fullyConfigured) {
                        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                        Text(
                            text = "请先在上方保存 AppKey 与 SecretKey",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.isAuthorized) {
                        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                        OutlinedButton(
                            onClick = viewModel::clearSdkSession,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(DinnerDimens.iconSm),
                            )
                            Spacer(modifier = Modifier.width(DinnerDimens.spaceXxs))
                            Text("重置授权会话（换账号时用）")
                        }
                    }
                }
            }

            val errorMessage = state.errorMessage
            if (errorMessage != null) {
                ErrorCallout(message = errorMessage)
            }

            val authorizeHint = state.authorizeHint
            if (authorizeHint != null) {
                Text(
                    text = authorizeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val restoreHint = state.restoreHint
            if (restoreHint != null) {
                Text(
                    text = restoreHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 两步进度条：凭证 -> 授权。 */
@Composable
private fun StepIndicator(credentialDone: Boolean, authorized: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        StepChip(index = 1, label = "凭证", done = credentialDone, modifier = Modifier.weight(1f))
        StepChip(index = 2, label = "授权", done = authorized, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StepChip(index: Int, label: String, done: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (done) scheme.primaryContainer else scheme.surfaceVariant)
            .padding(horizontal = DinnerDimens.spaceXs, vertical = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        Box(
            modifier = Modifier
                .size(DinnerDimens.iconLg)
                .clip(CircleShape)
                .background(if (done) scheme.primary else scheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (done) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthorizedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(DinnerDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = "已授权，可以正常同步了",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 错误提示块：原因如实展示，不给「看起来成功」的措辞。 */
@Composable
private fun ErrorCallout(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(DinnerDimens.spaceSm),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Start,
        )
    }
}
