package com.guolongli.dinner.feature.mine

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guolongli.dinner.ui.components.AppTopBar
import com.guolongli.dinner.feature.browse.RecordFilter
import com.guolongli.dinner.ui.components.AvatarCircle
import com.guolongli.dinner.ui.components.ConfirmDialog
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import com.guolongli.dinner.ui.components.SectionCard
import com.guolongli.dinner.ui.components.StatTile
import com.guolongli.dinner.ui.components.StatTiles
import com.guolongli.dinner.ui.components.ThinDivider
import com.guolongli.dinner.ui.theme.DinnerDimens
import com.guolongli.dinner.ui.util.DateText

/**
 * 「我的」页面：身份 / 统计 / 网盘 / 成员 / 存储位置 / 关于。
 *
 * 结构上分成「我」和「设置」两段：上半部分用头像与三宫格回答"我是谁、记了多少"，
 * 下半部分用分组卡片承载所有可操作项，避免图标与文字排成一条长清单。
 */
@Composable
fun MineScreen(
    onOpenAuth: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenRecords: (RecordFilter) -> Unit,
    viewModel: MineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var nameInput by remember { mutableStateOf("") }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.setMyAvatar(uri)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "我的", subtitle = "两张嘴，一本账") },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(DinnerDimens.spinnerMd),
                    strokeWidth = DinnerDimens.progressStroke,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DinnerDimens.screenPadding,
                    end = DinnerDimens.screenPadding,
                    top = DinnerDimens.spaceSm,
                    bottom = DinnerDimens.spaceLg,
                ),
        ) {
            ProfileHeader(
                name = state.myName,
                totalCount = state.totalCount,
                avatarImage = state.myAvatar,
                onAvatarClick = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRename = viewModel::openNameDialog,
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceMd))

            // 三个数字都能点进去看明细：这比只给一个数字有用得多。
            StatTiles(
                items = listOf(
                    StatTile(
                        value = state.totalCount.toString(),
                        label = "总记录",
                        onClick = { onOpenRecords(RecordFilter.ALL) },
                    ),
                    StatTile(
                        value = state.mineCount.toString(),
                        label = "我记的",
                        onClick = { onOpenRecords(RecordFilter.MINE) },
                    ),
                    StatTile(
                        value = state.syncedCount.toString(),
                        label = "已同步",
                        onClick = { onOpenRecords(RecordFilter.SYNCED) },
                    ),
                ),
            )

            Spacer(modifier = Modifier.height(DinnerDimens.spaceMd))

            // 六个卡片平铺时标题层级都一样，扫一眼分不清哪几块是一类事。
            // 用三个分区标题把它们收成「身份与同步 / 群组与存储 / 数据与安全」。
            GroupLabel("身份与同步")

            SectionCard(title = "身份") {
                SettingRow(
                    icon = Icons.Outlined.Badge,
                    title = state.phoneMask ?: "绑定手机号",
                    subtitle = if (state.phoneMask != null) {
                        "重装或换手机后，填入同一号码即可找回记录"
                    } else {
                        "绑定后身份不再依赖这台设备，卸载重装也能找回历史"
                    },
                    value = if (state.phoneMask == null) "去绑定" else "更换",
                    onClick = viewModel::openPhoneDialog,
                )
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            SectionCard(title = "百度网盘") {
                SettingRow(
                    icon = Icons.Outlined.CloudSync,
                    title = if (state.isAuthorized) "已授权" else "未授权",
                    subtitle = if (state.isAuthorized) {
                        "记录会自动上传到 ${state.storageRoot}"
                    } else {
                        "授权后才能同步照片与视频"
                    },
                    value = if (state.pendingCount > 0) "待上传 ${state.pendingCount}" else null,
                    onClick = onOpenAuth,
                )

                if (state.isAuthorized) {
                    ThinDivider()
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !state.isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DinnerDimens.spaceSm)
                            .height(DinnerDimens.touchTargetMin),
                    ) {
                        Text(if (state.isSyncing) "同步中…" else "立即同步")
                    }
                    val lastSyncMessage = state.lastSyncMessage
                    if (lastSyncMessage != null) {
                        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                        Text(
                            text = lastSyncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinDivider()
                    TextButton(
                        onClick = viewModel::openRevokeDialog,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("解除网盘授权", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceMd))

            GroupLabel("群组与存储")

            SectionCard(title = "成员") {
                SettingRow(
                    icon = Icons.Outlined.People,
                    title = "群组与入群密码",
                    subtitle = when {
                        state.groupId != null -> "已入群 ${state.groupId} · 管理入群密码、建群与加入"
                        else -> "还没建群也没入群 · 建群或用动态入群密码加入"
                    },
                    value = "进入",
                    onClick = onOpenGroup,
                )
                ThinDivider()
                SettingRow(
                    icon = Icons.Outlined.People,
                    title = if (state.members.size > 1) {
                        "${state.members.size} 位家人与饭搭子"
                    } else {
                        "只有我自己"
                    },
                    subtitle = "上限 ${state.memberLimit} 人。加人由管理员在「群组」页换入群密码后私下转告",
                    value = "管理",
                    onClick = onOpenPairing,
                )
                if (state.groupId != null) {
                    ThinDivider()
                    TextButton(
                        onClick = viewModel::openDeleteGroupDialog,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("删除群组", color = MaterialTheme.colorScheme.error)
                    }
                }
                ThinDivider()
                SettingRow(
                    icon = Icons.Outlined.AdminPanelSettings,
                    title = "管理员：${state.adminName}",
                    subtitle = if (state.amIAdmin) {
                        "可以修改所有人的记录、评论与云端文件"
                    } else {
                        "只有本人和管理员能改别人的内容"
                    },
                    value = if (state.amIAdmin) "转让" else null,
                    onClick = viewModel::openAdminDialog,
                )
                if (state.members.size > 1) {
                    ThinDivider()
                    MemberStrip(members = state.members)
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            SectionCard(title = "存储位置") {
                SettingRow(
                    icon = Icons.Outlined.Folder,
                    title = state.storageRoot,
                    subtitle = "记录、照片、头像都写在这个目录下",
                    value = "修改",
                    onClick = viewModel::openStorageDialog,
                )
                if (state.isBound) {
                    ThinDivider()
                    TextButton(
                        onClick = viewModel::openUnbindDialog,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("解除成员关系", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceMd))

            GroupLabel("数据与安全")

            SectionCard(title = "本地数据") {
                SettingRow(
                    icon = Icons.Outlined.Storage,
                    title = "本机保留 ${retentionLabel(state.retentionDays)}",
                    subtitle = "超期的记录会先确认云端有副本，再从本机清掉",
                    value = "调整",
                    onClick = viewModel::openRetentionDialog,
                )
                ThinDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setCompressMedia(!state.compressMedia) }
                        .padding(vertical = DinnerDimens.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(DinnerDimens.avatarSm)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Compress,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(DinnerDimens.iconSm),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "压缩后存储",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (state.compressMedia) {
                                "照片长边压到 1920、视频降到 1280"
                            } else {
                                "默认不压缩：原图原视频，不降分辨率"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.compressMedia,
                        onCheckedChange = viewModel::setCompressMedia,
                    )
                }
                ThinDivider()
                SettingRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = "从云端取回一段时间",
                    subtitle = "按区间补回记录与评论，照片视频点开时再下载",
                    value = "选择",
                    onClick = viewModel::openRangeDialog,
                )
                if (state.retentionDays != PERSIST_FOREVER) {
                    ThinDivider()
                    TextButton(
                        onClick = viewModel::openCleanConfirm,
                        enabled = !state.isCleaning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isCleaning) "清理中…" else "立即清理本地")
                    }
                }
                val message = state.maintenanceMessage
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            SectionCard(title = "网盘连接") {
                SettingRow(
                    icon = Icons.Outlined.CloudSync,
                    title = "连接失败时重试 ${networkRetriesLabel(state.networkRetries)}",
                    subtitle = if (state.networkRetries <= 1) {
                        "请求失败就直接报错，最省接口配额"
                    } else {
                        "最多再试 ${state.networkRetries - 1} 次，都失败才报错"
                    },
                    value = "调整",
                    onClick = viewModel::openNetworkRetriesDialog,
                )
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            SectionCard(title = "安全") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setAppLock(!state.appLockEnabled) }
                        .padding(vertical = DinnerDimens.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
                ) {
                    Box(
                        modifier = Modifier
                            .size(DinnerDimens.avatarSm)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(DinnerDimens.iconSm),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "打开应用时验证身份",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "用指纹、人脸或锁屏密码解锁；应用不保存任何口令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.appLockEnabled,
                        onCheckedChange = viewModel::setAppLock,
                    )
                }
            }

            Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))

            SectionCard {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = "关于食伴",
                    subtitle = "版本、开源协议与开发者",
                    onClick = onOpenAbout,
                )
            }
        }
    }

    // 改名对话框
    if (state.showNameDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeNameDialog,
            title = { Text("改昵称") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("新昵称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setName(nameInput.ifBlank { state.myName }) }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeNameDialog) { Text("取消") }
            },
        )
    }

    // 绑定手机号：把身份固定到号码上，卸载重装也能找回。
    if (state.showPhoneDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closePhoneDialog,
            title = { Text(if (state.phoneMask == null) "绑定手机号" else "更换手机号") },
            text = {
                Column {
                    Text(
                        text = "绑定后你的记录归到这个号码名下。重装或换手机时，" +
                            "填入同一个号码就能找回身份、头像与历史记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    OutlinedTextField(
                        value = state.phoneInput,
                        onValueChange = viewModel::setPhoneInput,
                        label = { Text("手机号") },
                        singleLine = true,
                        enabled = !state.isBindingPhone,
                        isError = state.phoneError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            val error = state.phoneError
                            Text(
                                text = error ?: "只在填写时用一次，不会被上传",
                                color = if (error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "号码会先做摘要再作为身份使用，网盘里存的是派生标识，不是号码本身。" +
                            "但请知悉：没有短信验证，谁填了你的号码都能接管这个身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmBindPhone,
                    enabled = !state.isBindingPhone,
                ) {
                    Text(if (state.isBindingPhone) "绑定中…" else "确认绑定")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closePhoneDialog) { Text("取消") }
            },
        )
    }

    // 解除授权确认：先取消同步。
    if (state.showRevokeDialog) {        ConfirmDialog(
            title = "解除网盘授权？",
            message = "取消同步并清除本机的网盘访问，已保存的本地记录不会删除。",
            confirmText = "解除授权",
            destructive = true,
            onConfirm = viewModel::revokeAuth,
            onDismiss = viewModel::closeRevokeDialog,
        )
    }

    if (state.showDeleteGroupDialog) {
        ConfirmDialog(
            title = "删除群组？",
            message = "只会清掉这台设备上的群组状态（成员名单与群组绑定），" +
                "回到「还没建群」的初始界面。\n\n" +
                "云端目录（${state.groupId ?: "group_<号码>"}）里的记录、照片、头像" +
                "一律不删除，其他成员也不受影响。想回到群里，重新用入群密码加入即可。",
            confirmText = "删除群组",
            destructive = true,
            onConfirm = viewModel::deleteGroup,
            onDismiss = viewModel::closeDeleteGroupDialog,
        )
    }

    if (state.showUnbindDialog) {
        ConfirmDialog(
            title = "解除成员关系？",
            message = "解除后这份名单会被清空，网盘上的记录不会删除，重新加入即可看到。",
            confirmText = "解除",
            destructive = true,
            onConfirm = viewModel::unbind,
            onDismiss = viewModel::closeUnbindDialog,
        )
    }

    // 存储位置：只改「以后写哪里」，不搬动网盘上已有文件。
    if (state.showStorageDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeStorageDialog,
            title = { Text("存储位置") },
            text = {
                Column {
                    Text(
                        text = "记录、照片、头像都会写在这个目录下，不存在会自动创建。必须以 /apps/ 开头。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    OutlinedTextField(
                        value = state.storageInput,
                        onValueChange = viewModel::setStorageInput,
                        label = { Text("网盘根目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.storageError?.let { error ->
                        Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                    Text(
                        text = "改完不会搬动已有文件，旧目录的数据需要重新同步才会出现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::resetStorageRoot) {
                        Text("恢复默认", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveStorageRoot) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeStorageDialog) { Text("取消") }
            },
        )
    }

    // 转让管理员：只能一人，名单里点选。
    if (state.showAdminDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeAdminDialog,
            title = { Text("转让管理员") },
            text = {
                Column {
                    Text(
                        text = "管理员可以修改所有人的记录与评论，删除别人的云端文件。转让后你自己恢复成普通成员。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    state.members.forEach { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !member.isMe) {
                                    viewModel.transferAdmin(member.id)
                                }
                                .padding(vertical = DinnerDimens.spaceXxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = member.name == state.adminName,
                                onClick = { if (!member.isMe) viewModel.transferAdmin(member.id) },
                                enabled = !member.isMe,
                            )
                            Text(
                                text = member.name + if (member.isMe) "（我）" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeAdminDialog) { Text("关闭") }
            },
        )
    }

    // 保留天数：档位有限，不做自由输入（这个值决定「会不会删本地数据」）。
    if (state.showCleanConfirm) {
        ConfirmDialog(
            title = "清理本机旧数据？",
            message = "会删掉超过 ${state.retentionDays} 天的本机照片与视频副本，" +
                "云端不受影响。已被云端确认过的才删，确认不了的一律保留。",
            confirmText = "开始清理",
            onConfirm = viewModel::confirmClean,
            onDismiss = viewModel::closeCleanConfirm,
        )
    }

    if (state.showRetentionDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeRetentionDialog,
            title = { Text("本机保留多久") },
            text = {
                Column {
                    Text(
                        text = "超过保留期的记录，会先逐个确认云端确实有副本，再从本机删除；确认不了的一律保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    RETENTION_OPTIONS.forEach { days ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setRetentionDays(days) }
                                .padding(vertical = DinnerDimens.spaceXxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.retentionDays == days,
                                onClick = { viewModel.setRetentionDays(days) },
                            )
                            Text(
                                text = retentionLabel(days),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeRetentionDialog) { Text("关闭") }
            },
        )
    }

    if (state.showNetworkRetriesDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeNetworkRetriesDialog,
            title = { Text("网盘连接重试") },
            text = {
                Column {
                    Text(
                        text = "只对频控、断网这类瞬时故障重试；鉴权失败、文件已存在等错误不会重试——"
                            + "那些重试多少次结果都一样，只会白烧接口配额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    StorageConfigRepository.NETWORK_RETRY_OPTIONS.forEach { times ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setNetworkRetries(times) }
                                .padding(vertical = DinnerDimens.spaceXxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.networkRetries == times,
                                onClick = { viewModel.setNetworkRetries(times) },
                            )
                            Text(
                                text = networkRetriesLabel(times),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeNetworkRetriesDialog) { Text("关闭") }
            },
        )
    }

    // 从云端补一段：只补记录与评论，媒体按需下载。
    if (state.showRangeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::closeRangeDialog,
            title = { Text("从云端取回") },
            text = {
                Column {
                    Text(
                        text = "只补记录与评论；照片和视频在时间线里点开时再下载，省接口配额。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceSm))
                    RangeRow(
                        label = "开始",
                        millis = state.rangeStart,
                        onPicked = viewModel::setRangeStart,
                    )
                    RangeRow(
                        label = "结束",
                        millis = state.rangeEnd,
                        onPicked = viewModel::setRangeEnd,
                    )
                    Spacer(modifier = Modifier.height(DinnerDimens.spaceXs))
                    Text(
                        text = "换设备或本机被清理过，用「取回全部」读整份云端快照，" +
                            "一次把时间线补全；之后照片仍按需下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::restoreRange, enabled = !state.isRestoring) {
                    Text(if (state.isRestoring) "取回中…" else "开始取回")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::restoreAll, enabled = !state.isRestoring) {
                        Text("取回全部")
                    }
                    TextButton(onClick = viewModel::closeRangeDialog) { Text("取消") }
                }
            },
        )
    }
}

/** 一行「标签 + 日期 + 更改」，点日期开系统日期选择器。 */
@Composable
private fun RangeRow(label: String, millis: Long, onPicked: (Long) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DinnerDimens.touchTargetMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DinnerDimens.spaceXl),
        )
        Text(
            text = DateText.dateLabel(millis),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onPicked(DateText.fromDateParts(year, month, day)) },
                    DateText.yearOf(millis),
                    DateText.monthOf(millis),
                    DateText.dayOfMonthOf(millis),
                ).show()
            },
        ) {
            Text("更改")
        }
    }
}

private fun retentionLabel(days: Int): String = StorageConfigRepository.retentionLabel(days)

private fun networkRetriesLabel(times: Int): String =
    StorageConfigRepository.networkRetriesLabel(times)

private val RETENTION_OPTIONS: List<Int> = StorageConfigRepository.RETENTION_OPTIONS

private const val PERSIST_FOREVER = StorageConfigRepository.PERSIST_FOREVER

/** 成员头像横排，一眼看到这个组里有谁。 */
@Composable
private fun MemberStrip(members: List<com.guolongli.dinner.feature.pairing.MemberUi>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DinnerDimens.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceSm),
    ) {
        members.forEach { member ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(DinnerDimens.spaceXxl),
            ) {
                AvatarCircle(
                    name = member.name,
                    size = DinnerDimens.avatarSm,
                    isMine = member.isMe,
                    image = member.avatar,
                )
                Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 头像 + 昵称 + 记录数的身份区。 */
@Composable
private fun ProfileHeader(
    name: String,
    totalCount: Long,
    avatarImage: Any?,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(DinnerDimens.avatarXl)
                .clickable(onClick = onAvatarClick),
        ) {
            AvatarCircle(
                name = name,
                isMine = true,
                size = DinnerDimens.avatarXl,
                image = avatarImage,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(DinnerDimens.iconLg)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "更换头像",
                    modifier = Modifier.size(DinnerDimens.iconSm),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(DinnerDimens.spaceSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(DinnerDimens.spaceXxs))
            Text(
                text = if (totalCount > 0) "已经记了 $totalCount 条" else "还没有记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRename) {
            Text("改昵称")
        }
    }
}

/**
 * 设置行：图标 + 标题/说明 + 右侧状态与箭头。
 *
 * 点击区域覆盖整行（>= 48dp），图标底色用 surfaceVariant，避免与背景糊在一起。
 */
/**
 * 分区标题：比卡片标题大一档、颜色更淡，只负责把「哪几块是一类事」讲清楚。
 *
 * 它不参与点击，也不抢卡片的视觉重量，所以用 onSurfaceVariant 而不是 onSurface。
 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = DinnerDimens.spaceXxs,
            bottom = DinnerDimens.spaceXs,
        ),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    leadingAvatar: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = DinnerDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DinnerDimens.spaceXs),
    ) {
        if (leadingAvatar != null) {
            leadingAvatar()
        } else {
            Box(
                modifier = Modifier
                    .size(DinnerDimens.avatarSm)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(DinnerDimens.iconSm),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
