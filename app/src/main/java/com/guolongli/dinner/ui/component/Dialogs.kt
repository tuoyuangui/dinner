package com.guolongli.dinner.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import com.guolongli.dinner.ui.theme.BorderColor
import com.guolongli.dinner.ui.theme.BrandBlue
import com.guolongli.dinner.ui.theme.Dimens
import com.guolongli.dinner.ui.theme.DinnerText
import com.guolongli.dinner.ui.theme.SurfaceMuted
import com.guolongli.dinner.ui.theme.SurfaceWhite
import com.guolongli.dinner.ui.theme.TagBlueBg
import com.guolongli.dinner.ui.theme.TagRedFg
import com.guolongli.dinner.ui.theme.TextPrimary
import com.guolongli.dinner.ui.theme.TextSecondary
import com.guolongli.dinner.ui.theme.TextTertiary

/* ----------------------------------------------------------------------------
 * 弹窗。三个页面原本各自写了一套「输入 / 选择 / 确认」，样式与交互都不一致；
 * 统一收到这里，页面里只描述「要问什么」。
 * -------------------------------------------------------------------------- */

/** 弹窗外壳：390 宽的白卡、9dp 圆角、16dp 内边距，所有对话共用 */
@Composable
internal fun DialogCard(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.RadiusCard))
                .background(SurfaceWhite)
                .padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.GapL),
            content = content,
        )
    }
}

/** 取消 / 确认两枚文字按钮，确认项在右、可按状态置灰 */
@Composable
internal fun DialogActions(
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    confirmColor: Color = BrandBlue,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GapM, Alignment.End),
    ) {
        Text(
            text = "取消",
            style = DinnerText.bodySmall,
            color = TextSecondary,
            modifier = Modifier
                .clickable { onDismiss() }
                .padding(Dimens.GapS),
        )
        Text(
            text = confirmLabel,
            style = DinnerText.bodySmallMedium,
            color = if (confirmEnabled) confirmColor else TextTertiary,
            modifier = Modifier
                .clickable(enabled = confirmEnabled) { onConfirm() }
                .padding(Dimens.GapS),
        )
    }
}

/** 单行输入弹窗：改名、改群名、改口令都用它 */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    minLength: Int = 1,
    maxLength: Int = 24,
    masked: Boolean = false,
    confirmLabel: String = "保存",
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.trim().length >= minLength

    DialogCard(onDismiss = onDismiss) {
        Text(text = title, style = DinnerText.navTitle, color = TextPrimary)
        InputField(
            value = value,
            hint = hint,
            masked = masked,
            onChange = { value = it.take(maxLength) },
        )
        Text(text = hint, style = DinnerText.footnote, color = TextTertiary)
        DialogActions(
            confirmLabel = confirmLabel,
            confirmEnabled = valid,
            onDismiss = onDismiss,
            onConfirm = { onConfirm(value.trim()) },
        )
    }
}

/** 纯确认弹窗：二次确认删除这类不可撤销的动作 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    DialogCard(onDismiss = onDismiss) {
        Text(text = title, style = DinnerText.navTitle, color = TextPrimary)
        Text(text = message, style = DinnerText.caption, color = TextSecondary)
        DialogActions(
            confirmLabel = confirmLabel,
            confirmColor = if (destructive) TagRedFg else BrandBlue,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

/** 单选项：本地数据保留期、删除档位都用它 */
data class DialogOption(
    val title: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
)

/**
 * 单列选项弹窗。
 * [onPick] 收到的是选项下标——调用方拿它去映射自己的枚举，避免把业务类型塞进通用组件。
 */
@Composable
fun OptionsDialog(
    title: String,
    message: String? = null,
    options: List<DialogOption>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    current: Int = -1,
) {
    DialogCard(onDismiss = onDismiss) {
        Text(text = title, style = DinnerText.navTitle, color = TextPrimary)
        if (message != null) {
            Text(text = message, style = DinnerText.caption, color = TextSecondary)
        }
        options.forEachIndexed { index, option ->
            val selected = index == current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.RadiusSmall))
                    .background(if (selected) TagBlueBg else SurfaceWhite)
                    .clickable { onPick(index) }
                    .padding(vertical = Dimens.GapM, horizontal = Dimens.GapXl),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.title,
                    style = DinnerText.bodySmallMedium,
                    color = when {
                        option.destructive -> TagRedFg
                        selected -> BrandBlue
                        else -> TextPrimary
                    },
                )
                option.subtitle?.let {
                    Text(text = it, style = DinnerText.footnote, color = TextSecondary)
                }
            }
        }
    }
}

/** 统一输入框：40dp 高、6dp 圆角、灰底 */
@Composable
fun InputField(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    numericOnly: Boolean = false,
    singleLine: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(Dimens.RadiusSmall))
            .background(SurfaceMuted)
            .border(Dimens.CardBorderWidth, BorderColor, RoundedCornerShape(Dimens.RadiusSmall))
            .padding(horizontal = Dimens.GapXl),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = hint, style = DinnerText.bodySmall, color = TextTertiary)
        }
        BasicTextField(
            value = value,
            onValueChange = { input -> onChange(if (numericOnly) input.filter { it.isDigit() } else input) },
            singleLine = singleLine,
            textStyle = DinnerText.bodySmall.copy(color = TextPrimary),
            cursorBrush = SolidColor(BrandBlue),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numericOnly) KeyboardType.NumberPassword else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
