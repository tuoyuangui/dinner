package com.guolongli.dinner.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guolongli.dinner.data.SettingsStore
import com.guolongli.dinner.ui.component.BottomPillNav
import com.guolongli.dinner.ui.component.DinnerSwitch
import com.guolongli.dinner.ui.component.DialogActions
import com.guolongli.dinner.ui.component.DialogCard
import com.guolongli.dinner.ui.component.DialogOption
import com.guolongli.dinner.ui.component.InputField
import com.guolongli.dinner.ui.component.MainTab
import com.guolongli.dinner.ui.component.MemberAvatar
import com.guolongli.dinner.ui.component.OptionsDialog
import com.guolongli.dinner.ui.component.RoleTag
import com.guolongli.dinner.ui.component.SectionCard
import com.guolongli.dinner.ui.component.SectionLabel
import com.guolongli.dinner.ui.component.SettingsGroup
import com.guolongli.dinner.ui.component.SettingsRow
import com.guolongli.dinner.ui.component.TextPromptDialog
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.StatScope
import com.guolongli.dinner.ui.model.StatUiModel
import com.guolongli.dinner.ui.theme.AvatarBlue
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagBlueFg
import com.guolongli.dinner.ui.theme.TagGreenBg
import com.guolongli.dinner.ui.theme.TagGreenFg
import com.guolongli.dinner.ui.theme.TagOrangeFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/**
 * 05 · 我的（设计稿第 5 页）
 *
 * 资料卡 → 三个可点统计 → 数据分组（群组 / 本地数据 / 压缩 / 网盘 / 取回）
 * → 通用分组（应用锁 / 外观 / 关于）。
 *
 * 这一页是「这台设备上的我」：谁在记、数据怎么管、出了问题去哪看。
 * 所有设置都即时生效，不设「保存」按钮——设置项少到一个程度时，保存反而是负担。
 */
@Composable
fun MineScreen(
    name: String,
    avatarPath: String?,
    isAdmin: Boolean,
    versionLabel: String,
    stats: List<StatUiModel>,
    groupName: String,
    memberCount: Int,
    retentionDays: Int,
    compressEnabled: Boolean,
    netdiskStatus: String,
    /** 网盘是否已授权：新手引导第二步「绑定百度网盘」的完成判据 */
    authorized: Boolean,
    /** 是否已入群：新手引导第三步「选择身份」的完成判据 */
    paired: Boolean,
    /** 昵称是否已改过：新手引导第一步的判据（默认昵称不算） */
    nameSet: Boolean,
    /** 新手引导第一遍是否已走完：走完不再自动显示引导卡 */
    onboardingDone: Boolean,
    /** 三步齐了调一次，记住「带路已完成」 */
    onFinishOnboarding: () -> Unit,
    appLockEnabled: Boolean,
    busy: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onOpenGroup: () -> Unit,
    onOpenNetdisk: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStat: (StatScope) -> Unit,
    onPickAvatar: () -> Unit,
    onRename: (String) -> Unit,
    onSetRetention: (Int) -> Unit,
    onSetCompress: (Boolean) -> Unit,
    /** 主题模式（浅色/深色/跟随系统）与切换回调 */
    themeMode: String,
    onSetTheme: (String) -> Unit,
    onRestoreMedia: () -> Unit,
    onSetAppLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameOpen by remember { mutableStateOf(false) }
    var retentionOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(PageBackground)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = Dimens.PagePadding),
        ) {
            Text(
                text = "我的",
                style = DinnerText.screenTitle,
                color = TextPrimary,
                modifier = Modifier.padding(top = Dimens.GapL, bottom = Dimens.GapXl),
            )

            // 新手引导：只在「第一遍还没走完」时自动出现，按顺序一步步走；
            // 三步齐了记一笔 onboardingDone，从此不再自动判断、不再显示——
            // 想再看去「通用 → 新手引导」，那是常驻入口
            val avatarSet = avatarPath != null
            val allDone = nameSet && avatarSet && authorized && paired
            if (allDone && !onboardingDone) {
                LaunchedEffect(Unit) { onFinishOnboarding() }
            }
            if (!onboardingDone && !allDone) {
                OnboardingCard(
                    nameSet = nameSet,
                    avatarSet = avatarSet,
                    authorized = authorized,
                    paired = paired,
                    onEditName = { renameOpen = true },
                    onPickAvatar = onPickAvatar,
                    onOpenNetdisk = onOpenNetdisk,
                    onOpenGroup = onOpenGroup,
                )
                Spacer(Modifier.height(Dimens.GapXl))
            }

            ProfileCard(
                name = name,
                avatarPath = avatarPath,
                isAdmin = isAdmin,
                versionLabel = versionLabel,
                onPickAvatar = onPickAvatar,
                onRename = { renameOpen = true },
            )

            Spacer(Modifier.height(Dimens.GapXl))

            StatsCard(stats = stats, onOpenStat = onOpenStat)

            SectionLabel("数据")

            SettingsGroup {
                SettingsRow(
                    icon = LineIconSpec.GROUP,
                    title = "我的群组",
                    value = "$groupName · $memberCount 人",
                    onClick = onOpenGroup,
                )
                SettingsRow(
                    icon = LineIconSpec.FOLDER,
                    title = "本地数据",
                    value = SettingsStore.retentionLabel(retentionDays),
                    onClick = { retentionOpen = true },
                )
                SettingsRow(
                    icon = LineIconSpec.COMPRESS,
                    title = "压缩存储",
                    trailing = {
                        DinnerSwitch(checked = compressEnabled, onCheckedChange = onSetCompress)
                    },
                    onClick = { onSetCompress(!compressEnabled) },
                )
                SettingsRow(
                    icon = LineIconSpec.CLOUD,
                    title = "网盘连接",
                    value = netdiskStatus,
                    onClick = onOpenNetdisk,
                )
                SettingsRow(
                    icon = LineIconSpec.DOWNLOAD_TRAY,
                    title = "区间取回",
                    value = if (busy) "正在从云端补回…" else "从云端补回",
                    showDivider = false,
                    onClick = { if (!busy) onRestoreMedia() },
                )
            }

            SectionLabel("通用")

            SettingsGroup {
                SettingsRow(
                    icon = LineIconSpec.SHIELD,
                    title = "应用锁",
                    value = if (appLockEnabled) "指纹 / 人脸 / 锁屏密码" else "未开启",
                    // 直接切开关：验证交给系统的指纹 / 锁屏密码，应用不保存口令，
                    // 所以没有「先设一个密码」这一步
                    onClick = { onSetAppLock(!appLockEnabled) },
                )
                SettingsRow(
                    icon = LineIconSpec.SUN,
                    title = "外观",
                    value = SettingsStore.themeLabel(themeMode),
                    onClick = { themeOpen = true },
                )
                SettingsRow(
                    icon = LineIconSpec.INFO,
                    title = "新手引导",
                    value = if (onboardingDone) "已完成 · 可重看" else "进行中",
                    onClick = { guideOpen = true },
                )
                SettingsRow(
                    icon = LineIconSpec.INFO,
                    title = "关于食伴",
                    value = versionLabel.substringBefore("·").trim(),
                    showDivider = false,
                    onClick = onOpenAbout,
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        BottomPillNav(
            selected = MainTab.MINE,
            onSelect = onSelectTab,
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    if (guideOpen) {
        DialogCard(onDismiss = { guideOpen = false }) {
            Text(text = "新手引导", style = DinnerText.navTitle, color = TextPrimary)
            Text(
                text = "第一次使用按下面三步走一遍；已完成的部分会显示对勾。",
                style = DinnerText.caption,
                color = TextSecondary,
            )
            OnboardingCard(
                nameSet = nameSet,
                avatarSet = avatarPath != null,
                authorized = authorized,
                paired = paired,
                onEditName = { guideOpen = false; renameOpen = true },
                onPickAvatar = { guideOpen = false; onPickAvatar() },
                onOpenNetdisk = { guideOpen = false; onOpenNetdisk() },
                onOpenGroup = { guideOpen = false; onOpenGroup() },
            )
            Text(
                text = "知道了",
                style = DinnerText.bodySmallMedium,
                color = BrandBlue,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { guideOpen = false }
                    .padding(Dimens.GapS),
            )
        }
    }

    if (renameOpen) {
        TextPromptDialog(
            title = "改个名字",
            initial = name,
            hint = "家人会在记录上看到这个名字",
            maxLength = 12,
            onDismiss = { renameOpen = false },
            onConfirm = { value ->
                onRename(value)
                renameOpen = false
            },
        )
    }

    if (retentionOpen) {
        val options = SettingsStore.RETENTION_OPTIONS
        OptionsDialog(
            title = "本地数据保留",
            message = "超过保留期的照片与视频会从本机删掉；记录本身和网盘上的副本都保留，" +
                "翻到旧记录时会自动取回。清理前会逐个确认云端有副本。",
            options = options.map { DialogOption(SettingsStore.retentionLabel(it)) },
            current = options.indexOf(retentionDays),
            onDismiss = { retentionOpen = false },
            onPick = { index ->
                onSetRetention(options[index])
                retentionOpen = false
            },
        )
    }

    if (themeOpen) {
        OptionsDialog(
            title = "外观",
            message = "选「跟随系统」时，深浅色跟着手机的深色模式走。",
            options = SettingsStore.THEME_OPTIONS.map { DialogOption(SettingsStore.themeLabel(it)) },
            current = SettingsStore.THEME_OPTIONS.indexOf(themeMode),
            onDismiss = { themeOpen = false },
            onPick = { index ->
                onSetTheme(SettingsStore.THEME_OPTIONS[index])
                themeOpen = false
            },
        )
    }
}

@Composable
private fun ProfileCard(
    name: String,
    avatarPath: String?,
    isAdmin: Boolean,
    versionLabel: String,
    onPickAvatar: () -> Unit,
    onRename: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapXl),
        ) {
            // 头像本身就是「上传头像」的入口：点一下开相册，选完自动裁方
            Box(contentAlignment = Alignment.BottomEnd) {
                MemberAvatar(
                    initial = name.trim().take(1).ifBlank { "我" },
                    color = AvatarBlue,
                    size = Dimens.AvatarLarge,
                    fontSize = 20.sp,
                    avatarPath = avatarPath,
                    modifier = Modifier.clickable { onPickAvatar() },
                )
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(BrandBlue)
                        .border(1.dp, SurfaceWhite, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "+", style = DinnerText.micro, color = OnBrand)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.GapXs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
                ) {
                    Text(text = name, style = DinnerText.navTitle, color = TextPrimary)
                    if (isAdmin) {
                        RoleTag(text = "管理员", bg = TagBlueBg, fg = TagBlueFg)
                    }
                }
                Text(text = versionLabel, style = DinnerText.caption, color = TextTertiary)
                Text(
                    text = "改名字",
                    style = DinnerText.caption,
                    color = BrandBlue,
                    modifier = Modifier.clickable { onRename() },
                )
            }
        }
    }
}

@Composable
private fun StatsCard(stats: List<StatUiModel>, onOpenStat: (StatScope) -> Unit) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            stats.forEach { stat ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Dimens.RadiusSmall))
                        .clickable { onOpenStat(stat.scope) }
                        .padding(vertical = Dimens.GapXs),
                    verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stat.value.toString(),
                        style = DinnerText.screenTitle,
                        // 现取色而不是用 stat.accent：accent 是数据层构造时定格的色值，
                        // 切主题后会残留旧主题的颜色
                        color = when (stat.scope) {
                            StatScope.SYNCED -> TagGreenFg
                            else -> TextPrimary
                        },
                    )
                    Text(text = stat.label, style = DinnerText.footnote, color = TextSecondary)
                }
            }
        }
    }
}

/**
 * 新手引导。新用户进来的三件事，按产品定的顺序走完这张卡就消失：
 * ① 更新昵称和头像（别人认得出你）→ ② 绑定百度网盘（数据有去处、能同步）
 * → ③ 选择身份：管理员（建群）/ 群员（入群）。
 *
 * 后一步在前一步完成前锁着——不是不给跳，而是「引导」的意义就是按顺序走；
 * 页面下方的常规入口（群组 / 网盘连接）不受影响，熟手想提前弄照样能弄。
 */
@Composable
private fun OnboardingCard(
    nameSet: Boolean,
    avatarSet: Boolean,
    authorized: Boolean,
    paired: Boolean,
    onEditName: () -> Unit,
    onPickAvatar: () -> Unit,
    onOpenNetdisk: () -> Unit,
    onOpenGroup: () -> Unit,
) {
    val step1Done = nameSet && avatarSet
    val step2Done = authorized
    val step3Done = paired
    val current = when {
        !step1Done -> 1
        !step2Done -> 2
        !step3Done -> 3
        else -> 3
    }

    SectionCard {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapL),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "新手引导", style = DinnerText.bodyMedium, color = TextPrimary)
                Text(
                    text = "第 $current 步 / 共 3 步",
                    style = DinnerText.caption,
                    color = TextSecondary,
                )
            }

            OnboardStepRow(
                number = 1,
                title = "更新昵称和头像",
                subtitle = when {
                    step1Done -> "昵称和头像都设好了"
                    nameSet -> "昵称好了，还差一个头像"
                    avatarSet -> "头像好了，还差一个昵称"
                    else -> "家人会在记录上看到它们"
                },
                done = step1Done,
            ) {
                if (!step1Done) {
                    if (!nameSet) StepButton("改名字", primary = true, onClick = onEditName)
                    if (!avatarSet) StepButton("换头像", primary = !nameSet, onClick = onPickAvatar)
                }
            }

            OnboardStepRow(
                number = 2,
                title = "绑定百度网盘",
                subtitle = when {
                    step2Done -> "已授权，数据会自动同步"
                    !step1Done -> "先完成上一步"
                    else -> "记录只存在你自己的网盘里"
                },
                done = step2Done,
                locked = !step1Done,
            ) {
                if (!step2Done && step1Done) {
                    StepButton("去绑定", primary = true, onClick = onOpenNetdisk)
                }
            }

            OnboardStepRow(
                number = 3,
                title = "选择身份：管理员 / 群员",
                subtitle = when {
                    step3Done -> "身份已选定"
                    !step2Done -> "先完成上一步"
                    else -> "管理员建群，群员凭入群密码进群"
                },
                done = step3Done,
                locked = !step2Done,
            ) {
                if (!step3Done && step2Done) {
                    StepButton("去选择", primary = true, onClick = onOpenGroup)
                }
            }
        }
    }
}

/** 引导里的一步：序号圈 + 标题副题 + 右侧动作 */
@Composable
private fun OnboardStepRow(
    number: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    locked: Boolean = false,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> TagGreenBg
                        locked -> SurfaceMuted
                        else -> TagBlueBg
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else "$number",
                style = DinnerText.micro,
                color = when {
                    done -> TagGreenFg
                    locked -> TextTertiary
                    else -> BrandBlue
                },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = DinnerText.bodySmallMedium,
                color = if (locked) TextTertiary else TextPrimary,
            )
            Text(text = subtitle, style = DinnerText.footnote, color = TextTertiary)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
private fun StepButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(if (primary) BrandBlue else TagBlueBg)
            .clickable { onClick() }
            .padding(horizontal = Dimens.GapL),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DinnerText.footnote,
            color = if (primary) OnBrand else BrandBlue,
        )
    }
}
