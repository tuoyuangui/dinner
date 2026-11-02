package com.guolongli.dinner.regression

import com.guolongli.dinner.core.identity.PhoneIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：各种数字系统（阿拉伯-印度 `/` 波斯 / 全角）的输入必须能变成 ASCII。
 *
 * 背景（实测确认的坑）：`Char.isDigit()` 与 `String.filter { it.isDigit() }`
 * 都是按 **Unicode** 判定的。阿拉伯语输入法给出的是 `٠١٢٣٤٥`（U+0660..U+0665），
 * 波斯语给出 `۰۱۲۳۴۵`（U+06F0..U+06F5），这些字符 `isDigit()` 全都算「数字」，
 * 于是被原样收进输入框；而 `PhoneIdentity.PHONE_REGEX` 里的 `\d` 在 Java / Kotlin
 * 默认是 ASCII-only，一个都匹配不上。
 *
 * 两条真实后果：
 * 1. 入群密码：够 6 位就能让按钮变亮，但云端存的是 ASCII，比对永远不相等——
 *    用户填的是对的人给的密码，却一直被告知「入群密码不对」。
 * 2. 手机号：输入框留下了阿拉伯数字，`normalize` 返回 null，界面只说
 *    「请填写 11 位手机号」，用户看不出哪里错——他填的明明就是自己的号码。
 *
 * 现在的口径是**先转 ASCII 再判**，而不是把非 ASCII 数字直接滤掉：
 * 滤掉会让阿拉伯语用户「打字没反应」，一个字都进不去，更糟。
 */
class NumericInputRegressionTest {

    @Test
    fun `阿拉伯-印度数字能转成 ASCII`() {
        // 13800138000 的阿拉伯-印度写法
        assertEquals(
            "13800138000",
            PhoneIdentity.toAsciiDigits("١٣٨٠٠١٣٨٠٠٠"),
        )
        assertEquals(
            "012345",
            PhoneIdentity.toAsciiDigits("٠١٢٣٤٥"),
        )
    }

    @Test
    fun `波斯数字能转成 ASCII`() {
        assertEquals(
            "13800138000",
            PhoneIdentity.toAsciiDigits("۱۳۸۰۰۱۳۸۰۰۰"),
        )
        assertEquals(
            "012345",
            PhoneIdentity.toAsciiDigits("۰۱۲۳۴۵"),
        )
    }

    @Test
    fun `全角数字能转成 ASCII`() {
        // 全角 ０-９（U+FF10..U+FF19）
        assertEquals("012345", PhoneIdentity.toAsciiDigits("０１２３４５"))
    }

    @Test
    fun `非数字字符原样保留，交给调用方取舍`() {
        // 手机号允许 +、-、空格、括号，转换阶段不该擅自删掉。
        assertEquals(
            "+86 138 0013 8000",
            PhoneIdentity.toAsciiDigits("+86 138 0013 8000"),
        )
    }

    @Test
    fun `阿拉伯语输入法的手机号能归一化成同一个 id`() {
        // 关键：同一个号码无论用什么数字系统输入，都必须得到同一个成员 id，
        // 否则「这台设备记的」和「那台设备认的」会变成两个人。
        assertEquals("13800138000", PhoneIdentity.normalize("١٣٨٠٠١٣٨٠٠٠"))
        assertEquals("13800138000", PhoneIdentity.normalize("۱۳۸۰۰۱۳۸۰۰۰"))
        assertEquals("13800138000", PhoneIdentity.normalize("13800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("+86 ١٣٨٠٠١٣٨٠٠٠"))
    }

    @Test
    fun `阿拉伯语数字的非法号码仍然被拒`() {
        // 转换不等于放宽校验：位数不对照样 null。
        assertNull(PhoneIdentity.normalize("١٣٨٠٠١٣٨٠٠"))
        assertNull(PhoneIdentity.normalize("١٢٨٠٠١٣٨٠٠٠"))
        assertFalse(PhoneIdentity.isValid("١٣٨٠٠١٣٨٠٠"))
    }

    @Test
    fun `阿拉伯语输入法的手机号转换后与 ASCII 号码完全相等`() {
        // 模拟「对方报的号码是 ASCII，我用阿拉伯语输入法敲了一遍」。
        val typed = "13800138000".map { asciiDigitToArabic(it) }.joinToString("")
        // 输入侧转换后必须与 ASCII 原串完全相等，否则会变成另一个成员 id。
        assertEquals("13800138000", PhoneIdentity.toAsciiDigits(typed))
        assertEquals("13800138000", PhoneIdentity.normalize(typed))
    }

    /** 把 ASCII 数字换成阿拉伯-印度数字，模拟阿拉伯语输入法的产出。 */
    private fun asciiDigitToArabic(ch: Char): Char =
        ('\u0660'.code + (ch - '0')).toChar()
}
