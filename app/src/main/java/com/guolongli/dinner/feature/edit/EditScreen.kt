package com.guolongli.dinner.feature.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import com.guolongli.dinner.data.location.GeoPoint
import com.guolongli.dinner.data.MediaInput
import com.guolongli.dinner.data.media.ImportedMedia
import com.guolongli.dinner.ui.component.DinnerSwitch
import com.guolongli.dinner.ui.component.MediaTile
import com.guolongli.dinner.ui.component.PillChip
import com.guolongli.dinner.ui.component.PrimaryButton
import com.guolongli.dinner.ui.component.StarRating
import com.guolongli.dinner.ui.icon.LineIcon
import com.guolongli.dinner.ui.icon.LineIconSpec
import com.guolongli.dinner.ui.model.MediaUiModel
import com.guolongli.dinner.ui.model.RecordType
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BorderStrongColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.DinnerTheme
import com.guolongli.dinner.ui.theme.OnBrand
import com.guolongli.dinner.ui.theme.PageBackground
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * 02 · 新建记录
 * 九类类型胶囊 → 0..9 媒体网格（107dp 方格）→ 备注 → 打分 → 压缩开关 → 主按钮。
 *
 * 媒体是真选的：点「+」走系统照片选择器（支持照片与视频混选），
 * 选完立刻复制进应用私有目录，所以相册里的原图被删掉也不影响这条记录。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(
    modifier: Modifier = Modifier,
    compressDefault: Boolean = false,
    onCancel: () -> Unit = {},
    /** 把相册 Uri 变成应用内的一份文件；由上层用 MediaCache 实现 */
    onImportMedia: suspend (Uri, Boolean) -> ImportedMedia? = { _, _ -> null },
    /** 保存：类型 / 备注 / 星级 / 媒体 */
    onSave: (RecordType, String, Int, List<MediaInput>) -> Unit = { _, _, _, _ -> },
    /** 已经取到的位置（可能来自上一次点击，转屏后仍保留） */
    location: GeoPoint? = null,
    /** 是否有定位权限，决定按钮文案 */
    hasLocationPermission: Boolean = false,
    /** 用户点了「加上位置」：申请权限并取点 */
    onRequestLocation: () -> Unit = {},
    onClearLocation: () -> Unit = {},
) {
    var selectedType by remember { mutableStateOf(RecordType.DINNER) }
    var compressEnabled by remember { mutableStateOf(compressDefault) }
    var note by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf(4) }
    var importing by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<ImportedMedia>() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = MAX_MEDIA - picked.size
        if (room <= 0) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            uris.take(room).forEach { uri ->
                onImportMedia(uri, compressEnabled)?.let { picked.add(it) }
            }
            importing = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.PagePadding),
    ) {

        // 顶部导航：取消 / 标题 / 保存
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "取消",
                style = DinnerText.bodyMedium,
                color = BrandBlue,
                modifier = Modifier.clickable { onCancel() },
            )
            Text(text = "新建记录", style = DinnerText.navTitle, color = TextPrimary)
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(Dimens.RadiusSmall))
                    .background(if (importing) TextTertiary else BrandBlue)
                    .clickable(enabled = !importing) {
                        onSave(
                            selectedType,
                            note,
                            rating,
                            picked.map { MediaInput(it.localPath, it.kind, it.durationMs) },
                        )
                    }
                    .padding(horizontal = Dimens.ChipPaddingH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (importing) "处理中" else "保存",
                    style = DinnerText.bodySmallMedium,
                    color = OnBrand,
                )
            }
        }

        Text(text = "记录类型", style = DinnerText.bodySmallMedium, color = TextSecondary)

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapM, bottom = Dimens.GapXs),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            RecordType.entries.forEach { type ->
                PillChip(
                    label = type.label,
                    selected = type == selectedType,
                    onClick = { selectedType = type },
                )
            }
        }

        // 媒体
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.GapL, bottom = Dimens.GapM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            Text(text = "媒体", style = DinnerText.bodySmallMedium, color = TextSecondary)
            Text(
                text = if (picked.isEmpty()) {
                    "照片视频可混排 · 最多 9 段"
                } else {
                    "已选 ${picked.size} / $MAX_MEDIA"
                },
                style = DinnerText.caption,
                color = TextTertiary,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.GapXs),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            picked.forEach { item ->
                Box {
                    MediaTile(
                        media = MediaUiModel(
                            localPath = item.localPath,
                            isVideo = item.kind == com.guolongli.dinner.data.local.MediaKind.VIDEO,
                            durationLabel = item.durationMs?.let { ms ->
                                val seconds = ms / 1000
                                "%02d:%02d".format(seconds / 60, seconds % 60)
                            },
                        ),
                        modifier = Modifier.size(Dimens.MediaTileEditSize),
                        cornerRadius = Dimens.RadiusSmall,
                        glyphSize = 28.dp,
                    )
                    // 点右上角的叉移除，避免误触整块格子
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(TextTertiary.copy(alpha = 0.75f))
                            .clickable { picked.remove(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "×", style = DinnerText.caption, color = OnBrand)
                    }
                }
            }
            if (picked.size < MAX_MEDIA) {
                Box(
                    modifier = Modifier
                        .size(Dimens.MediaTileEditSize)
                        .clip(RoundedCornerShape(Dimens.RadiusSmall))
                        .background(SurfaceWhite)
                        .border(
                            Dimens.CardBorderWidth,
                            BorderStrongColor,
                            RoundedCornerShape(Dimens.RadiusSmall),
                        )
                        .clickable {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                ),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    LineIcon(spec = LineIconSpec.PLUS, tint = TextTertiary, size = 28.dp)
                }
            }
        }

        // 备注
        Text(
            text = "备注",
            style = DinnerText.bodySmallMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = Dimens.GapXl, bottom = Dimens.GapM),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(Dimens.RadiusMedium))
                .background(SurfaceWhite)
                .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusMedium))
                .padding(Dimens.GapXl),
            contentAlignment = Alignment.TopStart,
        ) {
            if (note.isEmpty()) {
                Text(
                    text = "写一句备注，比如今晚这顿怎么样",
                    style = DinnerText.body,
                    color = TextTertiary,
                )
            }
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = DinnerText.body.copy(color = TextPrimary),
                cursorBrush = SolidColor(BrandBlue),
            )
        }

        // 打分
        Text(
            text = "打个分",
            style = DinnerText.bodySmallMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = Dimens.GapXl, bottom = Dimens.GapXs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.GapXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.GapM),
        ) {
            repeat(5) { index ->
                val star = index + 1
                Text(
                    text = if (star <= rating) "★" else "☆",
                    style = DinnerText.bodyMedium.copy(fontSize = 22.sp),
                    color = if (star <= rating) BrandBlue else TextTertiary,
                    modifier = Modifier.clickable { rating = if (rating == star) 0 else star },
                )
            }
            Text(
                text = if (rating == 0) "还没打分" else "$rating 分",
                style = DinnerText.caption,
                color = TextSecondary,
            )
        }

        // 位置：一顿饭在哪儿吃，往往比几点吃更值得记
        val locationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            // 用户点了允许才去取点；拒绝就什么都不做，保存照样成功
            if (granted.values.any { it }) onRequestLocation()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.GapL)
                .clip(RoundedCornerShape(Dimens.RadiusMedium))
                .background(SurfaceWhite)
                .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusMedium))
                .clickable {
                    if (location != null) {
                        onClearLocation()
                    } else if (hasLocationPermission) {
                        onRequestLocation()
                    } else {
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                }
                .padding(Dimens.GapXl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "位置", style = DinnerText.body, color = TextPrimary)
                Text(
                    text = when {
                        location != null -> location.displayText
                        hasLocationPermission -> "点一下记下现在的位置"
                        else -> "需要定位权限；不加位置也能保存"
                    },
                    style = DinnerText.footnote,
                    color = if (location != null) BrandBlue else TextTertiary,
                )
            }
            Text(
                text = if (location != null) "移除" else "加上",
                style = DinnerText.bodySmallMedium,
                color = BrandBlue,
            )
        }

        // 压缩存储开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.GapXl)
                .clip(RoundedCornerShape(Dimens.RadiusMedium))
                .background(SurfaceWhite)
                .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusMedium))
                .padding(Dimens.GapXl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "压缩后存储", style = DinnerText.body, color = TextPrimary)
                Text(
                    text = "照片长边 1920 · 视频长边 1280",
                    style = DinnerText.footnote,
                    color = TextTertiary,
                )
            }
            DinnerSwitch(checked = compressEnabled, onCheckedChange = { compressEnabled = it })
        }

        PrimaryButton(
            text = "保存记录",
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
            height = 44.dp,
            onClick = {
                onSave(
                    selectedType,
                    note,
                    rating,
                    picked.map { MediaInput(it.localPath, it.kind, it.durationMs) },
                )
            },
        )
    }
}

private const val MAX_MEDIA = 9

@Preview(name = "02 新建记录", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun EditScreenPreview() {
    DinnerTheme {
        EditScreen()
    }
}
