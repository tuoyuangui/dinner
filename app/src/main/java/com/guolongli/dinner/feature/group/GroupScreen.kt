package com.guolongli.dinner.feature.group

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.ui.component.ConfirmDialog
import com.guolongli.dinner.ui.component.DialogActions
import com.guolongli.dinner.ui.component.DialogCard
import com.guolongli.dinner.ui.component.InputField
import com.guolongli.dinner.ui.component.MemberAvatar
import com.guolongli.dinner.ui.component.RoleTag
import com.guolongli.dinner.ui.component.SectionCard
import com.guolongli.dinner.ui.component.SectionLabel
import com.guolongli.dinner.ui.component.TextPromptDialog
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.MemberUiModel
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DividerColor
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagBlueFg
import com.guolongli.dinner.ui.theme.TagGreenBg
import com.guolongli.dinner.ui.theme.TagGreenFg
import com.guolongli.dinner.ui.theme.TagNeutralBg
import com.guolongli.dinner.ui.theme.TagOrangeFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/**
 * 04 · 群组（设计稿第 4 页）
 *
 * 管理员口令卡 → 成员列表 → 群组设置 → 动态入群密码。
 *
 * 三条产品约束落在这一页上：
 * 1. 口令以前是明文存的（产品要求），解锁只在本机内存里有效；
 * 2. 只有管理员能改群名 / 换入群密码 / 转让管理权；
 * 3. 成员不是账号，而是「同步下来出现过的作者」+ 本机这个人——
 *    网盘上没有账号体系，这是最诚实的一份名单。
 */
@Composable
fun GroupScreen(
    groupName: String,
    members: List<MemberUiModel>,
    memberLimit: Int,
    joinCode: String,
    adminCode: String,
    adminUnlocked: Boolean,
    iAmAdmin: Boolean,
    /** 本机这个人的号码（已掩码）；空串表示还没绑定 */
    phoneMask: String,
    /** 是否已经入群 */
    paired: Boolean,
    /** 建组者号码（已掩码） */
    adminPhoneMask: String,
    onBack: () -> Unit,
    onUnlock: (String) -> Unit,
    onLock: () -> Unit,
    onUpdateGroupName: (String) -> Unit,
    onUpdateJoinCode: (String) -> Unit,
    onUpdateAdminCode: (String) -> Unit,
    onTransferAdmin: (MemberUiModel) -> Unit,
    onBindPhone: (String) -> Unit,
    onJoinGroup: (phone: String, adminPhone: String, joinPassword: String) -> Unit,
    onCreateGroup: (phone: String, adminPassword: String) -> Unit,
    onRotateJoinCode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var codeInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf(Editor.NONE) }
    var transferTarget by remember { mutableStateOf<MemberUiModel?>(null) }
    var joinOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var bindOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(PageBackground)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = Dimens.PagePadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapM, bottom = Dimens.GapL),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    LineIcon(spec = LineIconSpec.ARROW_LEFT, tint = TextPrimary, size = 20.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "群组", style = DinnerText.screenTitle, color = TextPrimary)
                    Text(
                        text = "$groupName · ${members.size} / $memberLimit 位成员",
                        style = DinnerText.caption,
                        color = TextSecondary,
                    )
                }
            }

            AdminCard(
                unlocked = adminUnlocked,
                iAmAdmin = iAmAdmin,
                error = error,
                input = codeInput,
                onInput = { codeInput = it; error = null },
                onSubmit = {
                    // 校验要读云端的 passwd.json（别人换过口令就得立刻失效），
                    // 所以是异步的：这里先收起输入，结果由一次性提示语告诉用户。
                    onUnlock(codeInput)
                    codeInput = ""
                    error = null
                },
                onLock = { onLock(); codeInput = ""; error = null },
            )

            Spacer(Modifier.height(Dimens.CardPadding))

            IdentityCard(
                phoneMask = phoneMask,
                paired = paired,
                adminPhoneMask = adminPhoneMask,
                onCreateGroup = { createOpen = true },
                onJoinGroup = { joinOpen = true },
                onBindPhone = { bindOpen = true },
            )

            SectionLabel("成员")

            SectionCard {
                Column(Modifier.padding(horizontal = Dimens.CardPadding)) {
                    members.forEachIndexed { index, member ->
                        MemberRow(
                            member = member,
                            // 只有管理员本人、且已解锁，才能把管理权交出去
                            canTransfer = adminUnlocked && iAmAdmin && !member.isMe && !member.isAdmin,
                            onTransfer = { transferTarget = member },
                        )
                        if (index != members.lastIndex) {
                            HorizontalDivider(thickness = 1.dp, color = DividerColor)
                        }
                    }
                }
            }

            Spacer(Modifier.height(Dimens.CardPadding))

            GroupSettingsCard(
                groupName = groupName,
                unlocked = adminUnlocked,
                onEditName = { editor = Editor.NAME },
            )

            Spacer(Modifier.height(Dimens.CardPadding))

            JoinCodeCard(
                joinCode = joinCode,
                unlocked = adminUnlocked,
                onEdit = { editor = Editor.JOIN_CODE },
                onRotate = onRotateJoinCode,
            )

            Spacer(Modifier.height(Dimens.CardPadding))

            AdminCodeCard(
                adminCode = adminCode,
                unlocked = adminUnlocked,
                onEdit = { editor = Editor.ADMIN_CODE },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    when (editor) {
        Editor.NONE -> Unit

        Editor.JOIN_CODE -> TextPromptDialog(
            title = "新的入群密码",
            initial = joinCode,
            hint = "4~16 位，好念好抄；成员凭它入群，换一次旧密码立刻失效",
            minLength = 4,
            maxLength = 16,
            onDismiss = { editor = Editor.NONE },
            onConfirm = { value ->
                onUpdateJoinCode(value)
                editor = Editor.NONE
            },
        )

        Editor.ADMIN_CODE -> TextPromptDialog(
            title = "新的管理员口令",
            initial = adminCode,
            hint = "6~12 位；改删他人内容、转让管理权前要输入它",
            minLength = 6,
            maxLength = 12,
            onDismiss = { editor = Editor.NONE },
            onConfirm = { value ->
                onUpdateAdminCode(value)
                editor = Editor.NONE
            },
        )

        Editor.NAME -> TextPromptDialog(
            title = "群组名称",
            initial = groupName,
            hint = "家人打开应用时看到的名字",
            maxLength = 12,
            onDismiss = { editor = Editor.NONE },
            onConfirm = { value ->
                onUpdateGroupName(value)
                editor = Editor.NONE
            },
        )
    }

    if (bindOpen) {
        TextPromptDialog(
            title = "绑定手机号",
            initial = "",
            hint = "11 位手机号。绑定后换手机、重装都能靠它找回同一个身份",
            minLength = 11,
            maxLength = 11,
            onDismiss = { bindOpen = false },
            onConfirm = { value ->
                onBindPhone(value)
                bindOpen = false
            },
        )
    }

    if (createOpen) {
        CreateGroupDialog(
            onDismiss = { createOpen = false },
            onConfirm = { phone, password ->
                onCreateGroup(phone, password)
                createOpen = false
            },
        )
    }

    if (joinOpen) {
        JoinGroupDialog(
            onDismiss = { joinOpen = false },
            onConfirm = { phone, adminPhone, password ->
                onJoinGroup(phone, adminPhone, password)
                joinOpen = false
            },
        )
    }

    transferTarget?.let { target ->
        ConfirmDialog(
            title = "把管理权交给 ${target.name}",
            message = "转让后你将失去改删他人内容的权限，对方需要设置新的管理员口令。" +
                "这一步会同步给所有成员。",
            confirmLabel = "确定转让",
            onDismiss = { transferTarget = null },
            onConfirm = {
                onTransferAdmin(target)
                transferTarget = null
            },
        )
    }
}

private enum class Editor { NONE, NAME, JOIN_CODE, ADMIN_CODE }

/**
 * 管理员卡。解锁前后高度一致（只换按钮文案），
 * 否则一张卡忽高忽低会把下面的成员列表顶着跳。
 */
@Composable
private fun AdminCard(
    unlocked: Boolean,
    iAmAdmin: Boolean,
    error: String?,
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onLock: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                LineIcon(
                    spec = LineIconSpec.LOCK,
                    tint = if (unlocked) TagGreenFg else TagOrangeFg,
                    size = 16.dp,
                )
                Text(
                    text = if (unlocked) "管理员模式已解锁" else "管理员模式未解锁",
                    style = DinnerText.bodyMedium,
                    color = TextPrimary,
                )
            }

            Text(
                text = if (unlocked) {
                    "本次运行内有效。可以改删所有人的记录、换入群密码、转让管理权。"
                } else {
                    "修改 / 删除他人内容、换入群密码前，需先验证管理员口令。"
                },
                style = DinnerText.caption,
                color = TextSecondary,
            )

            if (unlocked) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(Dimens.RadiusSmall))
                        .background(SurfaceMuted)
                        .clickable { onLock() }
                        .padding(horizontal = Dimens.GapXl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "退出管理员模式", style = DinnerText.bodySmall, color = TextSecondary)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
                ) {
                    Box(Modifier.weight(1f)) {
                        InputField(
                            value = input,
                            hint = "输入管理员口令",
                            masked = true,
                            onChange = onInput,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(Dimens.RadiusSmall))
                            .background(if (input.isBlank()) TextTertiary else BrandBlue)
                            .clickable(enabled = input.isNotBlank()) { onSubmit() }
                            .padding(horizontal = Dimens.GapXl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "解锁", style = DinnerText.bodySmallMedium, color = OnBrand)
                    }
                }
            }

            if (error != null) {
                Text(text = error, style = DinnerText.footnote, color = TagOrangeFg)
            }
            if (!iAmAdmin) {
                Text(
                    text = "管理员不是这台设备；解锁后也只能看不能改群级设置。",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                )
            }
            Text(
                text = "解锁只在本次运行内有效，别人在别处换了口令会立刻失效",
                style = DinnerText.footnote,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun MemberRow(member: MemberUiModel, canTransfer: Boolean, onTransfer: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (canTransfer) Modifier.clickable { onTransfer() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapL),
    ) {
        MemberAvatar(
            initial = member.initial,
            color = member.color,
            size = Dimens.AvatarMedium,
            fontSize = 14.sp,
            avatarPath = member.avatarPath,
        )
        Text(
            text = if (member.isMe) "${member.name}（我）" else member.name,
            style = DinnerText.body,
            color = TextPrimary,
        )
        RoleTag(
            text = if (member.isAdmin) "管理员" else "成员",
            bg = if (member.isAdmin) TagBlueBg else TagNeutralBg,
            fg = if (member.isAdmin) TagBlueFg else TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (canTransfer) "转让管理权" else member.joinedAt,
            style = DinnerText.footnote,
            color = if (canTransfer) BrandBlue else TextTertiary,
        )
    }
}

@Composable
private fun GroupSettingsCard(groupName: String, unlocked: Boolean, onEditName: () -> Unit) {
    SectionCard {
        Column(Modifier.padding(horizontal = Dimens.CardPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable(enabled = unlocked) { onEditName() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "群组名称", style = DinnerText.body, color = TextPrimary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
                ) {
                    Text(text = groupName, style = DinnerText.caption, color = TextTertiary)
                    if (unlocked) {
                        LineIcon(spec = LineIconSpec.CHEVRON_RIGHT, tint = TextTertiary, size = 16.dp)
                    }
                }
            }
            if (!unlocked) {
                HorizontalDivider(thickness = 1.dp, color = DividerColor)
                Text(
                    text = "解锁管理员后可改",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                    modifier = Modifier.padding(vertical = Dimens.GapM),
                )
            }
        }
    }
}

/**
 * 动态入群密码。
 *
 * 「动态」的完整语义：随手可复制发给家人；管理员加完人可以「换一个」
 * （随机重生成，旧密码立刻失效）；也可以手改一串好念的。
 * 换 / 改都是管理员动作，要先解锁。
 */
@Composable
private fun JoinCodeCard(
    joinCode: String,
    unlocked: Boolean,
    onEdit: () -> Unit,
    onRotate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SectionCard {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapS),
        ) {
            Text(text = "动态入群密码", style = DinnerText.bodyMedium, color = TextPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                Text(
                    text = joinCode,
                    style = DinnerText.screenTitle,
                    color = BrandBlue,
                    modifier = Modifier.weight(1f),
                )
                CodeActionButton(text = "复制") {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(joinCode))
                }
                if (unlocked) {
                    // 动态的核心动作：加完人就换一串，旧的立刻失效
                    CodeActionButton(text = "换一个", onClick = onRotate)
                    CodeActionButton(text = "修改", primary = true, onClick = onEdit)
                } else {
                    CodeActionButton(text = "需解锁", enabled = false) {}
                }
            }
            Text(
                text = "新成员填对管理员手机号与这串密码即可进群；每换一次旧的立刻失效。" +
                    "加完人记得「换一个」，密码才够“动态”。复制发给家人即可。",
                style = DinnerText.caption,
                color = TextSecondary,
            )
        }
    }
}

/**
 * 管理员口令。
 *
 * 与入群密码分开两张卡，是因为它们的「谁需要知道」不一样：入群密码要发给家人，
 * 管理员口令只该管理员自己留着。混在一张卡里容易顺手一起截图发出去。
 *
 * **默认不展示**：明文口令不常驻屏幕——锁着时只给一串圆点，解锁后也要
 * 主动点「显示」才看得到。
 */
@Composable
private fun AdminCodeCard(adminCode: String, unlocked: Boolean, onEdit: () -> Unit) {
    // 默认藏起来；锁着时连「显示」按钮都不给，避免旁人顺手点开
    var revealed by remember { mutableStateOf(false) }
    val showPlain = unlocked && revealed

    SectionCard {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapS),
        ) {
            Text(text = "管理员口令", style = DinnerText.bodyMedium, color = TextPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                Text(
                    text = if (showPlain) adminCode else "••••••••",
                    style = DinnerText.screenTitle,
                    color = if (unlocked) BrandBlue else TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (unlocked) {
                    CodeActionButton(text = if (revealed) "隐藏" else "显示") { revealed = !revealed }
                    CodeActionButton(text = "修改", primary = true, onClick = onEdit)
                } else {
                    CodeActionButton(text = "需解锁", enabled = false) {}
                }
            }
            Text(
                text = if (unlocked) {
                    "默认不展示，点「显示」才看得到。改 / 删他人内容、换入群密码、转让管理权时要输入它；" +
                        "只在本次运行内有效"
                } else {
                    "解锁管理员后可查看与修改；改 / 删他人内容、换入群密码、转让管理权时要输入它"
                },
                style = DinnerText.caption,
                color = TextSecondary,
            )
        }
    }
}

/** 口令卡上的小按钮：复制 / 换一个 / 显示 / 修改 / 需解锁 */
@Composable
private fun CodeActionButton(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(
                when {
                    !enabled -> SurfaceMuted
                    primary -> TagBlueBg
                    else -> SurfaceMuted
                },
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Dimens.GapL),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DinnerText.bodySmallMedium,
            color = when {
                !enabled -> TextTertiary
                primary -> BrandBlue
                else -> TextSecondary
            },
        )
    }
}

/**
 * 「我是谁 / 我在哪个群」。
 *
 * 这一张卡把原来散落的三件事说清楚：手机号是身份、群组由管理员号码定位、
 * 入群只验入群密码。不做成三个入口，是因为它们本来就是同一件事的三个阶段。
 */
@Composable
private fun IdentityCard(
    phoneMask: String,
    paired: Boolean,
    adminPhoneMask: String,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onBindPhone: () -> Unit,
) {
    SectionCard {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Text(text = "我的身份", style = DinnerText.bodyMedium, color = TextPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (phoneMask.isBlank()) "还没绑定手机号" else phoneMask,
                        style = DinnerText.bodyLarge,
                        color = if (phoneMask.isBlank()) TextTertiary else TextPrimary,
                    )
                    Text(
                        text = when {
                            paired -> "已入群" + if (adminPhoneMask.isNotBlank()) " · 管理员 $adminPhoneMask" else ""
                            phoneMask.isBlank() -> "绑定后换手机、重装都能找回同一个身份"
                            else -> "还没入群：可以建一个，或加入家人的群"
                        },
                        style = DinnerText.caption,
                        color = TextSecondary,
                    )
                }
                if (phoneMask.isBlank()) {
                    PillButton(text = "绑定", onClick = onBindPhone)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                PillButton(text = "创建群组", primary = true, onClick = onCreateGroup)
                PillButton(text = "加入群组", onClick = onJoinGroup)
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(if (primary) BrandBlue else TagBlueBg)
            .clickable { onClick() }
            .padding(horizontal = Dimens.GapXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DinnerText.bodySmallMedium,
            color = if (primary) OnBrand else BrandBlue,
        )
    }
}

/** 建组：手机号 + 管理员口令；动态入群密码由应用随机生成并立刻显示 */
@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (phone: String, adminPassword: String) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = phone.trim().length == 11 && password.trim().length >= 6

    DialogCard(onDismiss = onDismiss) {
        Text(text = "创建群组", style = DinnerText.navTitle, color = TextPrimary)
        Text(
            text = "你的手机号会成为群组的定位（管理员号码），管理员口令用于改删别人内容。" +
                "建好后应用会生成一个入群密码，发给家人即可。",
            style = DinnerText.caption,
            color = TextSecondary,
        )
        InputField(
            value = phone,
            hint = "你的手机号（11 位）",
            numericOnly = true,
            onChange = { phone = it.take(11) },
        )
        InputField(
            value = password,
            hint = "管理员口令（至少 6 位）",
            masked = true,
            onChange = { password = it.take(32) },
        )
        DialogActions(
            confirmLabel = "创建",
            confirmEnabled = valid,
            onDismiss = onDismiss,
            onConfirm = { onConfirm(phone, password) },
        )
    }
}

/** 入群：我的号码 + 管理员号码 + 入群密码。不验管理员口令 */
@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (phone: String, adminPhone: String, password: String) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var adminPhone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = phone.trim().length == 11 &&
        adminPhone.trim().length == 11 &&
        password.trim().isNotEmpty()

    DialogCard(onDismiss = onDismiss) {
        Text(text = "加入群组", style = DinnerText.navTitle, color = TextPrimary)
        Text(
            text = "向管理员要两个东西：他的手机号、当前入群密码。" +
                "入群只验这个密码，不会向你索要管理员口令。",
            style = DinnerText.caption,
            color = TextSecondary,
        )
        InputField(
            value = phone,
            hint = "你的手机号（11 位）",
            numericOnly = true,
            onChange = { phone = it.take(11) },
        )
        InputField(
            value = adminPhone,
            hint = "管理员手机号（11 位）",
            numericOnly = true,
            onChange = { adminPhone = it.take(11) },
        )
        InputField(
            value = password,
            hint = "入群密码",
            onChange = { password = it.take(32) },
        )
        DialogActions(
            confirmLabel = "加入",
            confirmEnabled = valid,
            onDismiss = onDismiss,
            onConfirm = { onConfirm(phone, adminPhone, password) },
        )
    }
}
