package com.guolongli.dinner.feature.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.ui.components.LoadingState
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.theme.DinnerDimens

/**
 * 群组页：建群、入群、管理员验口令、以及管理员维护动态入群密码。
 *
 * 两道门各管一件事：
 * - 入群：填对管理员手机号（定位目录）+ 云端 `join_password`（验证身份），
 *   不绑电话、不验管理员口令；
 * - 管理员：显式输入管理员口令，通过后才能在本次运行里改别人的内容、换入群密码、转让管理权。
 *
 * 口令卡片默认遮明文；验证通过后才出现换密码 / 退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    onBack: () -> Unit,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // key 用消息的自增序号而不是消息内容：连点两次「换一个入群密码」，
    // 两次文案都是「已换新入群密码…」，以内容为 key 时第二次不重启 effect，
    // 用户会以为没生效。序号由 ViewModel 每次发消息时自增，同一句文案也能各弹一次。
    LaunchedEffect(state.messageSeq) {
        val message = state.errorMessage ?: state.successMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "群组",
                subtitle = "建一个群，或者加入别人的群",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    start = DinnerDimens.screenPadding,
                    end = DinnerDimens.screenPadding,
                    top = DinnerDimens.spaceXs,
                    bottom = DinnerDimens.spaceLg,
                ),
            verticalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
        ) {
            when (state.phase) {
                GroupUiState.Phase.SETUP -> SetupPhase(state = state, viewModel = viewModel)
                GroupUiState.Phase.JOINED -> JoinedPhase(state = state, viewModel = viewModel)
            }
        }
    }
}

// ---------- 未建组 / 未加入 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupPhase(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    SectionCard {
        Text(
            text = "先选你的身份",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        Text(
            text = "管理员建群时设两个口令：管理员口令自己留着，入群密码转告要加的人。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.role == GroupRole.ADMIN,
                onClick = { viewModel.setRole(GroupRole.ADMIN) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    SegmentedButtonDefaults.Icon(active = state.role == GroupRole.ADMIN)
                },
            ) {
                Text("我是管理员")
            }
            SegmentedButton(
                selected = state.role == GroupRole.MEMBER,
                onClick = { viewModel.setRole(GroupRole.MEMBER) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    SegmentedButtonDefaults.Icon(active = state.role == GroupRole.MEMBER)
                },
            ) {
                Text("我是群员")
            }
        }
    }

    when (state.role) {
        GroupRole.ADMIN -> AdminCreateForm(state = state, viewModel = viewModel)
        GroupRole.MEMBER -> MemberJoinForm(state = state, viewModel = viewModel)
    }
}

@Composable
private fun AdminCreateForm(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    SectionCard(title = "创建群组") {
        OutlinedTextField(
            value = state.adminPhone,
            onValueChange = viewModel::setAdminPhone,
            label = { Text("你的手机号") },
            singleLine = true,
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text("群组目录就叫 group_你的号码，换手机填同一个号码就能找回")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        OutlinedTextField(
            value = state.adminPasswordInput,
            onValueChange = viewModel::setAdminPassword,
            label = { Text("管理员口令") },
            singleLine = true,
            enabled = !state.isBusy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            // 口令明文存进网盘 passwd.json（产品要求），界面如实说明它是一道门闩、不是加密。
            supportingText = { Text("明文存进云端 passwd.json，用于改 / 删别人的内容；它不是加密") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        Button(
            onClick = viewModel::createGroup,
            enabled = state.canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(DinnerDimens.spinnerSm),
                    strokeWidth = DinnerDimens.progressStroke,
                )
            } else {
                Text("创建群组")
            }
        }
    }

    SectionCard(title = "创建之后") {
        Text(
            text = "建完会拿到一个动态入群密码。把它和管理员手机号一起告诉要一起记的人，" +
                "对方就能进群；你随时可以换码，旧码立刻作废。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemberJoinForm(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    SectionCard(title = "加入群组") {
        OutlinedTextField(
            value = state.joinAdminPhone,
            onValueChange = viewModel::setJoinAdminPhone,
            label = { Text("管理员手机号") },
            singleLine = true,
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("管理员建群时用的那个号码，只用来找到群组目录") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        OutlinedTextField(
            value = state.memberPhone,
            onValueChange = viewModel::setMemberPhone,
            label = { Text("你的手机号") },
            singleLine = true,
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("只作为你在群里的身份") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        OutlinedTextField(
            value = state.joinPasswordInput,
            onValueChange = viewModel::setJoinPassword,
            label = { Text("入群密码") },
            singleLine = true,
            enabled = !state.isBusy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("管理员私下转告的那个；不需要管理员口令") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        Button(
            onClick = viewModel::joinGroup,
            enabled = state.canJoin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(DinnerDimens.spinnerSm),
                    strokeWidth = DinnerDimens.progressStroke,
                )
            } else {
                Text("加入群组")
            }
        }
    }

    SectionCard(title = "加入流程") {
        Text(
            text = "1. 向管理员要入群密码（私下给，别在群里要）。\n" +
                "2. 填管理员手机号、你的手机号、入群密码，密码对上就直接进群。\n" +
                "3. 进群后就能看到群里所有的记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 已入组 ----------

@Composable
private fun JoinedPhase(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
            Column {
                Text(
                    text = if (state.amIAdmin) "你在管理这个群组" else "你已在这个群组",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "群组 ${state.groupId.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    PasswordCard(state = state, viewModel = viewModel)

    SectionCard(title = "同步") {
        Text(
            text = "改昵称、换头像、发新记录、写评论都会自动同步给其他人，不需要管理员代为操作。这里也可以手动补一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
        FilledTonalButton(
            onClick = viewModel::syncNow,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("立即同步")
        }
    }
}

/**
 * 动态入群密码卡片。
 *
 * 默认遮明文：没验管理员口令之前只显示「已设置 / 未读到」，
 * 不把口令直接摊在屏幕上（借给别人看照片时最容易漏）。
 * 验证通过后才出现「换一个」「显示 / 隐藏」「退出管理员」。
 *
 * 后端并不依赖这块界面：`rotateJoinPassword` 一定先 `requireAdminUnlocked`。
 */
@Composable
private fun PasswordCard(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    val joinPassword = state.joinPassword
    SectionCard(title = "动态入群密码") {
        Text(
            text = "加人的唯一入口。成员填对管理员手机号 + 这个密码就能进群，" +
                "不需要管理员口令，也不绑电话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))

        // 口令本体：未解锁时一律遮住（连明文都不渲染），解锁后按开关显示。
        val masked = state.adminUnlocked && state.joinPasswordVisible
        val display = when {
            joinPassword.isNullOrBlank() -> "还没有读到口令"
            !state.adminUnlocked -> "需要验证管理员口令后才显示"
            masked -> joinPassword
            else -> "••••••"
        }
        Text(
            text = display,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.configError != null) {
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            Text(
                text = state.configError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

        if (!state.adminUnlocked) {
            OutlinedTextField(
                value = state.unlockPasswordInput,
                onValueChange = viewModel::setUnlockPassword,
                label = { Text("管理员口令") },
                singleLine = true,
                enabled = !state.isBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            Button(
                onClick = viewModel::unlockAdmin,
                enabled = !state.isBusy && state.unlockPasswordInput.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.height(DinnerDimens.iconMd),
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text("验证管理员口令")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = viewModel::rotateJoinPassword,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.height(DinnerDimens.iconMd),
                    )
                    Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                    Text("换一个")
                }
                IconButton(onClick = viewModel::toggleJoinPasswordVisible) {
                    Icon(
                        imageVector = if (state.joinPasswordVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (state.joinPasswordVisible) "隐藏入群密码" else "显示入群密码",
                    )
                }
            }
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
            OutlinedButton(
                onClick = viewModel::lockAdmin,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.height(DinnerDimens.iconMd),
                )
                Spacer(modifier = Modifier.width(DinnerDimens.spaceXs))
                Text("退出管理员")
            }
            Text(
                text = "本次运行内有效；关掉应用再打开需要重新验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
        TextButton(
            onClick = viewModel::reloadConfig,
            enabled = !state.isBusy,
        ) {
            Text("从云端重新读一次")
        }
    }
}
