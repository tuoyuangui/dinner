package com.guolongli.dinner.feature.netdisk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.core.Graph
import com.guolongli.dinner.data.auth.BaiduAuthManager
import com.guolongli.dinner.data.auth.BaiduAuthStore
import com.guolongli.dinner.data.auth.DeviceCode
import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.data.netdisk.RemoteFile
import com.guolongli.dinner.data.SettingsStore
import com.guolongli.dinner.data.sync.AppSyncPrefs
import com.guolongli.dinner.data.sync.SyncReason
import com.guolongli.dinner.ui.component.SectionCard
import com.guolongli.dinner.ui.component.SectionLabel
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BorderStrongColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagGreenFg
import com.guolongli.dinner.ui.theme.TagOrangeFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 06 · 网盘（百度网盘接入控制台）
 *
 * 授权链路：填 AppKey / SecretKey → 打开百度授权页 → 拿 code 换 token → 落到 SharedPreferences。
 * 之后的每一步（建目录 / 上传 / 列表 / 下载）都用同一个 token，过期自动续。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetdiskScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 用 Graph 里的那个授权管理器，不要自己 new 一个：
    // 自己 new 出来的实例和全局那套是两套状态机，退出授权 / 重置会话时
    // 很容易出现「界面说已退出、全局还认得授权」的错位。
    val auth = remember { Graph.authManager }
    // 重试次数走设置：和 Graph.netdisk 用的是同一份 SettingsStore，改完下一次请求就生效
    val settings = remember { Graph.settings }
    val repository = remember(auth) {
        NetdiskRepository(auth, retryTimes = { settings.retryTimes })
    }

    var authorized by remember { mutableStateOf(auth.isAuthorized()) }
    var appKey by remember { mutableStateOf(auth.appKey) }
    var appSecret by remember { mutableStateOf(auth.appSecret) }
    var busy by remember { mutableStateOf<String?>(null) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var files by remember { mutableStateOf(listOf<RemoteFile>()) }
    var quota by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }

    /**
     * 凭据读回次数。
     *
     * [BaiduAuthManager.hasKeys] 读的是 SharedPreferences，不是 Compose 状态：
     * 存进去之后如果没有别的状态变化，界面不会重算，那行「缺少 AppKey / SecretKey」
     * 就会一直挂着，看着像没保存成功。所以每次写完凭据手动 +1，逼着界面重算一次。
     */
    var credRevision by remember { mutableStateOf(0) }
    val keysReady = remember(credRevision) { auth.hasKeys() }

    // 自动同步设置：读一次，改了就写回并重排后台任务
    val syncPrefs = remember { Graph.syncPrefs }
    val autoSync = remember { Graph.autoSync }
    val syncState by autoSync.state.collectAsState()
    var autoEnabled by remember { mutableStateOf(syncPrefs.autoEnabled) }
    var interval by remember { mutableStateOf(syncPrefs.intervalMinutes) }
    var wifiOnly by remember { mutableStateOf(syncPrefs.wifiOnly) }

    // 网盘重试档位：直接读写 SettingsStore，NetdiskRepository 每次请求都重读
    var retryTimes by remember { mutableStateOf(settings.retryTimes) }

    // 设备码授权
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var deviceStatus by remember { mutableStateOf("") }

    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        logs = (logs + "$stamp  $line").takeLast(60)
    }

    fun run(task: String, block: suspend () -> String) {
        if (busy != null) return
        busy = task
        scope.launch {
            try {
                log(block())
            } catch (e: NetdiskException) {
                log("【$task】失败 errno=${e.errno} ${e.message}")
            } catch (e: Exception) {
                log("【$task】失败 ${e.message ?: e::class.java.simpleName}")
            } finally {
                busy = null
                authorized = auth.isAuthorized()
                // 授权状态变化立即反映到全局同步状态：
                // 从网盘页返回「我的」时不用等下一次前台事件才发现已授权
                autoSync.refreshAuthorized()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.PagePadding),
    ) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.GapS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LineIcon(
                spec = LineIconSpec.ARROW_LEFT,
                tint = TextPrimary,
                size = 24.dp,
                modifier = Modifier.clickable { onBack() },
            )
            Text(text = "网盘", style = DinnerText.screenTitle, color = TextPrimary)
            Text(
                text = "3.1.0",
                style = DinnerText.caption,
                color = TextTertiary,
            )
        }

        // —— 授权状态 ——
        SectionCard {
            Column(
                modifier = Modifier.padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GapS),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (authorized) TagGreenFg else TagOrangeFg),
                        )
                        Text(
                            text = when {
                                !keysReady -> "缺少 AppKey / SecretKey"
                                authorized -> "已授权百度网盘"
                                else -> "未授权"
                            },
                            style = DinnerText.bodyMedium,
                            color = TextPrimary,
                        )
                    }
                    if (authorized) {
                        Text(
                            text = "退出授权",
                            style = DinnerText.captionMedium,
                            color = TextSecondary,
                            modifier = Modifier.clickable {
                                auth.logout()
                                authorized = false
                                files = emptyList()
                                quota = null
                                log("已清除本地 token")
                            },
                        )
                    }
                }

                InfoLine("应用名", BaiduConfig.APP_NAME)
                InfoLine("目录", BaiduConfig.APP_DIR)
                InfoLine("scope", auth.scope().ifBlank { BaiduConfig.SCOPE })
                if (authorized) {
                    InfoLine("有效期至", formatTime(auth.expiresAt()))
                }
            }
        }

        // —— 自动同步 ——
        SectionLabel("自动同步")
        SectionCard {
            Column(
                modifier = Modifier.padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
                    ) {
                        Text(text = "后台自动同步", style = DinnerText.bodyMedium, color = TextPrimary)
                        Text(
                            text = if (autoEnabled) {
                                "每 $interval 分钟检查一次 · ${if (wifiOnly) "仅 Wi-Fi" else "有网就传"}"
                            } else {
                                "已关闭，只在点「立即同步」时上传"
                            },
                            style = DinnerText.footnote,
                            color = TextTertiary,
                        )
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = { on ->
                            autoEnabled = on
                            syncPrefs.autoEnabled = on
                            Graph.scheduler.reschedule()
                            log(if (on) "自动同步已开启，后台每 $interval 分钟一次" else "自动同步已关闭")
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SurfaceWhite,
                            checkedTrackColor = BrandBlue,
                            uncheckedThumbColor = SurfaceWhite,
                            uncheckedTrackColor = BorderStrongColor,
                            uncheckedBorderColor = BorderStrongColor,
                        ),
                        modifier = Modifier.scale(0.85f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                    AppSyncPrefs.INTERVALS.forEach { minutes ->
                        ChoiceChip(
                            text = "$minutes 分钟",
                            selected = interval == minutes,
                            enabled = autoEnabled,
                        ) {
                            interval = minutes
                            syncPrefs.intervalMinutes = minutes
                            Graph.scheduler.reschedule()
                            log("同步间隔改为 $minutes 分钟")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.GapXs),
                    ) {
                        Text(text = "仅 Wi-Fi 上传", style = DinnerText.bodySmall, color = TextPrimary)
                        Text(
                            text = "开着更省流量；照片视频多的时候建议保持打开",
                            style = DinnerText.footnote,
                            color = TextTertiary,
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { on ->
                            wifiOnly = on
                            syncPrefs.wifiOnly = on
                            Graph.scheduler.reschedule()
                            log(if (on) "以后只在 Wi-Fi 下自动上传" else "允许用流量自动上传")
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SurfaceWhite,
                            checkedTrackColor = BrandBlue,
                            uncheckedThumbColor = SurfaceWhite,
                            uncheckedTrackColor = BorderStrongColor,
                            uncheckedBorderColor = BorderStrongColor,
                        ),
                        modifier = Modifier.scale(0.85f),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(Dimens.GapS)) {
                    Text(text = "网盘重试", style = DinnerText.bodySmall, color = TextPrimary)
                    Text(
                        text = "遇到频控 / 网络抖动时换个时间再试几次；" +
                            "鉴权类和业务错误不会重试，省配额",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                        SettingsStore.RETRY_OPTIONS.forEach { times ->
                            ChoiceChip(
                                text = "$times 次",
                                selected = retryTimes == times,
                                enabled = true,
                            ) {
                                retryTimes = times
                                settings.retryTimes = times
                                log("网盘重试改为 $times 次")
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))

                InfoLine("上次同步", formatTime(syncState.lastAt))
                InfoLine("待传", "${syncState.pending} 条")
                InfoLine("状态", syncState.statusText)

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                    ActionChip("立即同步", busy == null) {
                        busy = "同步"
                        scope.launch {
                            try {
                                val report = autoSync.run(SyncReason.MANUAL, force = true)
                                log(report?.summary() ?: "已是最新")
                                authorized = auth.isAuthorized()
                            } catch (e: Exception) {
                                log("同步失败：${e.message ?: e::class.java.simpleName}")
                            } finally {
                                busy = null
                            }
                        }
                    }
                    ActionChip("全部重试", busy == null) {
                        busy = "重试"
                        scope.launch {
                            try {
                                val report = Graph.repository.retryFailed()
                                log("重试完成：${report.summary()}")
                            } catch (e: Exception) {
                                log("重试失败：${e.message ?: e::class.java.simpleName}")
                            } finally {
                                busy = null
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("应用凭据")
        SectionCard {
            Column(
                modifier = Modifier.padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                KeyField(
                    label = "AppKey",
                    value = appKey,
                    placeholder = "百度网盘开放平台的 API Key",
                    onValueChange = { appKey = it },
                )
                KeyField(
                    label = "SecretKey",
                    value = appSecret,
                    placeholder = "对应的 Secret Key",
                    masked = true,
                    onValueChange = { appSecret = it },
                )
                Text(
                    text = "凭据需要在百度网盘开放平台创建应用后手动填写，不会随安装包内置；填写后保存在本机（SecretKey 加密存储）。应用名必须是 dinner，接口才允许访问 /apps/dinner/。",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryAction(
                    text = if (authorized) "重新授权" else "保存并授权",
                    enabled = busy == null,
                ) {
                    // 先落盘再判断：只有写进去并且读得回来，才算「填好了」。
                    // 读不回来的典型原因是加密密钥被误删——那种情况在这里就会暴露出来，
                    // 而不是等到后面打开授权页才报一个看不懂的错。
                    auth.updateKeys(appKey, appSecret)
                    authorized = false
                    credRevision++
                    if (!auth.hasKeys()) {
                        log("凭据没能保存成功：SecretKey 读取失败，请重新填写后再试")
                        return@PrimaryAction
                    }
                    // 读回来核对一遍：写进去的和读出来的不一样，说明落盘有问题
                    if (auth.appSecret != appSecret.trim()) {
                        log("SecretKey 保存后读不一致，请重新填写")
                        return@PrimaryAction
                    }
                    log("凭据已保存")
                    run("打开授权页") {
                        authUrl = auth.authorizeUrl()
                        "正在打开百度授权页…"
                    }
                }

                // 设备码：手机上不用在两个页面之间复制那串 32 位的授权码
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
                ) {
                    ActionChip("设备码授权", busy == null && deviceCode == null) {
                        auth.updateKeys(appKey, appSecret)
                        credRevision++
                        if (!auth.hasKeys()) {
                            log("凭据没能保存成功，请重新填写 AppKey 与 SecretKey")
                            return@ActionChip
                        }
                        busy = "申请设备码"
                        scope.launch {
                            try {
                                val code = auth.requestDeviceCode()
                                deviceCode = code
                                deviceStatus = "等待确认"
                                log("设备码 ${code.userCode}，在 ${code.verificationUrl} 输入后点同意")
                                try {
                                    val info = auth.waitForDeviceToken(code) { elapsed ->
                                        deviceStatus = "等待确认（${elapsed / 1000}s）"
                                    }
                                    authorized = true
                                    deviceCode = null
                                    deviceStatus = ""
                                    log("授权成功，scope=${info.scope.ifBlank { BaiduConfig.SCOPE }}")
                                    autoSync.refreshAuthorized()
                                    // 刚拿到 token：立刻拉一遍云端已有的记录
                                    val report = Graph.autoSync.run(SyncReason.AUTH)
                                    log("首次同步：${report?.summary() ?: "已跳过"}")
                                } catch (e: Exception) {
                                    deviceStatus = "已取消或超时"
                                    log("设备码授权失败：${e.message ?: e::class.java.simpleName}")
                                    deviceCode = null
                                }
                            } catch (e: Exception) {
                                log("申请设备码失败：${e.message ?: e::class.java.simpleName}")
                            } finally {
                                busy = null
                            }
                        }
                    }
                    Text(
                        text = "手机推荐：显示短码 → 浏览器确认 → 自动完成",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                    )
                }

                deviceCode?.let { code ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimens.RadiusSmall))
                            .background(TagBlueBg)
                            .padding(Dimens.GapXl),
                        verticalArrangement = Arrangement.spacedBy(Dimens.GapS),
                    ) {
                        Text(
                            text = code.userCode.uppercase().chunked(4).joinToString(" "),
                            style = DinnerText.screenTitle,
                            color = BrandBlue,
                        )
                        Text(
                            text = "在浏览器打开 ${code.verificationUrl}，输入上面的短码并确认（${code.expiresInSeconds / 60} 分钟内有效）",
                            style = DinnerText.footnote,
                            color = TextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapM)) {
                            ActionChip("打开确认页", true) {
                                runCatching {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(code.verificationUrl),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }.onFailure { log("打开浏览器失败：${it.message}") }
                            }
                            Text(
                                text = deviceStatus.ifBlank { "等待确认" },
                                style = DinnerText.footnote,
                                color = TextTertiary,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                }
            }
        }

        SectionLabel("接口自检")
        SectionCard {
            Column(
                modifier = Modifier.padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
                    verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
                ) {
                    ActionChip("检查目录", busy == null) {
                        run("检查目录") {
                            try {
                                val created = repository.ensureAppDir()
                                repository.ensureDir(BaiduConfig.RECORD_DIR)
                                if (created) "已创建 ${BaiduConfig.APP_DIR}" else "${BaiduConfig.APP_DIR} 已存在"
                            } catch (e: NetdiskException) {
                                // 建不出目录时，最需要知道的是「云端到底有哪些应用目录」：
                                // 应用名和开放平台登记的不一致时，/apps/dinner 永远不存在，
                                // 光看 errno=2 根本猜不到是这个原因。
                                val visible = repository.listAppContainer()
                                val hint = if (visible.isEmpty()) {
                                    "读不到 /apps 目录列表"
                                } else {
                                    "/apps 下可见：${visible.joinToString("、")}"
                                }
                                throw IllegalStateException("${e.message}（errno=${e.errno}；$hint）", e)
                            }
                        }
                    }
                    ActionChip("上传测试记录", busy == null) {
                        run("上传记录") {
                            val name = "record-${System.currentTimeMillis()}.json"
                            val body = JSONObject()
                                .put("id", System.currentTimeMillis())
                                .put("type", "晚餐")
                                .put("note", "网盘联通性测试")
                                .put("rating", 5)
                                .put("createdAt", System.currentTimeMillis())
                                .toString(2)
                            val path = repository.uploadRecord(body, name)
                            "已上传 $path"
                        }
                    }
                    ActionChip("刷新列表", busy == null) {
                        run("拉列表") {
                            // 先确保目录存在再列：云端还没有 /apps/dinner 时，
                            // listall 只会回 31066，看上去像「接口坏了」，其实是「还没建」。
                            repository.ensureDirectories(BaiduConfig.APP_DIR)
                            val remote = repository.listAll()
                            files = remote
                            "共 ${remote.size} 个文件"
                        }
                    }
                    ActionChip("下载最新", busy == null) {
                        run("下载") {
                            val newest = files.firstOrNull { !it.isDir }
                                ?: throw NetdiskException(31066, "列表为空，先刷新列表")
                            val target = File(context.cacheDir, "netdisk/${newest.name}")
                            repository.download(newest.path, target)
                            "已下载到 ${target.absolutePath}（${newest.size / 1024} KB）"
                        }
                    }
                    ActionChip("查容量", busy == null) {
                        run("容量") {
                            val q = repository.quota()
                            quota = q
                            val total = q.first
                            val used = q.second
                            val percent = if (total > 0) used * 100.0 / total else 0.0
                            "已用 ${gb(used)} / ${gb(total)}（${"%.1f".format(percent)}%）"
                        }
                    }
                }

                quota?.let { q ->
                    Text(
                        text = "剩余 ${gb(q.third)}",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                    )
                }
            }
        }

        if (files.isNotEmpty()) {
            SectionLabel("云端文件（${files.size}）")
            SectionCard {
                Column(Modifier.padding(horizontal = Dimens.CardPadding)) {
                    files.take(12).forEachIndexed { index, file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = file.path.removePrefix(BaiduConfig.APP_DIR + "/"),
                                style = DinnerText.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (file.isDir) "目录" else "${file.size / 1024} KB",
                                style = DinnerText.footnote,
                                color = TextTertiary,
                            )
                        }
                        if (index != files.take(12).lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderColor))
                        }
                    }
                }
            }
        }

        SectionLabel("日志")
        SectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.GapS),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = if (busy == null) "点上面的按钮开始自检" else "$busy …",
                        style = DinnerText.footnote,
                        color = TextTertiary,
                    )
                } else {
                    logs.forEach { line ->
                        Text(text = line, style = DinnerText.footnote, color = TextSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    authUrl?.let { url ->
        BaiduAuthWebView(
            url = url,
            redirectUri = auth.redirectUri,
            onCode = { code ->
                authUrl = null
                run("换 token") {
                    val info = auth.exchange(code)
                    "授权成功，scope=${info.scope.ifBlank { BaiduConfig.SCOPE }}"
                }
            },
            onClose = { authUrl = null },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = DinnerText.footnote, color = TextTertiary)
        Text(text = value, style = DinnerText.footnote, color = TextSecondary)
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    placeholder: String,
    masked: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.GapXs)) {
        Text(text = label, style = DinnerText.footnote, color = TextTertiary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(Dimens.RadiusSmall))
                .background(SurfaceMuted)
                .padding(horizontal = Dimens.GapXl),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = DinnerText.bodySmall.copy(color = TextPrimary),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = DinnerText.bodySmall, color = TextTertiary)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun ActionChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(if (enabled) TagBlueBg else SurfaceMuted)
            .border(Dimens.CardBorderWidth, if (enabled) BrandBlue.copy(alpha = 0.2f) else BorderStrongColor, RoundedCornerShape(Dimens.RadiusSmall))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Dimens.GapXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DinnerText.captionMedium,
            color = if (enabled) BrandBlue else TextTertiary,
        )
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(
                when {
                    selected -> BrandBlue
                    enabled -> SurfaceMuted
                    else -> SurfaceMuted.copy(alpha = 0.5f)
                },
            )
            .border(
                Dimens.CardBorderWidth,
                if (selected) BrandBlue else BorderStrongColor,
                RoundedCornerShape(Dimens.RadiusSmall),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Dimens.GapXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DinnerText.captionMedium,
            color = when {
                selected -> OnBrand
                enabled -> TextSecondary
                else -> TextTertiary
            },
        )
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(if (enabled) BrandBlue else BrandBlue.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = DinnerText.bodySmallMedium, color = OnBrand)
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}

private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
