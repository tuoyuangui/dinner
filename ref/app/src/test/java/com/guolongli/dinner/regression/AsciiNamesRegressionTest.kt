package com.guolongli.dinner.regression

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.core.identity.PhoneIdentity
import com.guolongli.dinner.ui.util.DateText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 回归：云端路径 / 文件名 / 日期键 / 入群密码一律是 ASCII，不随设备语言变形。
 *
 * 背景（实测确认的坑）：`String.format` 默认按设备 Locale 选数字字形。
 * 把设备语言切成阿拉伯语（`ar-EG-u-nu-arab`）后，`String.format("%04d", 2026)`
 * 直接给出 `٢٠٢٦`（阿拉伯-印度数字）。于是：
 *
 * - 云端目录变成 `members/<号码>/meals/٢٠٢٦/١٠`；
 * - 媒体文件名变成 `٢٠٢٦-١٠-٢٩_١١_٢٢_٣٥.jpg`；
 * - 而入群密码会变成 `٠١٢٣٤٥`，对方在自己设备上根本敲不出来。
 *
 * 这些字符串都要跨设备、跨语言保持一致，变形就是数据错位与门禁死锁。
 *
 * 守这条靠切换默认 Locale 后重新跑一遍纯函数：
 * 如果谁把 `Ascii` 换回 `String.format(...)`，这里立刻红。
 */
class AsciiNamesRegressionTest {

    private lateinit var original: Locale

    @Before
    fun setUp() {
        original = Locale.getDefault()
        // 阿拉伯语 + 阿拉伯-印度数字：String.format 会给出非 ASCII 数字字形。
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    private val ascii = Regex("[\\x20-\\x7E]+")

    @Test
    fun `上传文件名在非拉丁语言下仍是 ASCII`() {
        // 2026-10-29 11:22:35 北京时间
        val millis = 1_793_148_155_000L
        val name = FileNames.timestampedMedia(millis, "jpg")
        assertTrue("文件名出现非 ASCII 字符：$name", ascii.matches(name))
        assertTrue("扩展名丢失：$name", name.endsWith(".jpg"))
        // 名字必须能被自己的解析器认回来，否则兜底扫描与按名找 fs_id 全瞎。
        assertTrue("自己的文件名自己解析不了：$name", FileNames.dayMillisOf(name) != null)
    }

    @Test
    fun `同秒去重后缀也是 ASCII`() {
        val millis = 1_793_148_155_000L
        (1..9).forEach { seq ->
            val name = FileNames.withDedupSuffix(millis, seq, "mp4")
            assertTrue("去重后缀文件名出现非 ASCII：$name", ascii.matches(name))
            assertTrue("自己的文件名自己解析不了：$name", FileNames.dayMillisOf(name) != null)
        }
    }

    @Test
    fun `云端媒体目录只有 ASCII 数字`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")
        val dir = paths.mediaDirOf("17380203573", 2026, 10)
        assertTrue("云端目录出现非 ASCII：$dir", ascii.matches(dir))
        assertEquals(
            "/apps/dinner/group_17380203573/members/17380203573/meals/2026/10",
            dir,
        )
        // 单数字月份也必须补零：03 与 3 是两个不同目录。
        assertEquals(
            "/apps/dinner/group_17380203573/members/17380203573/meals/2026/03",
            paths.mediaDirOf("17380203573", 2026, 3),
        )
    }

    @Test
    fun `手机号归一化结果只由 ASCII 数字组成`() {
        // 密码体系已移除，跨设备传播的机器可读数字只剩手机号（成员 id 就是它）。
        // 归一化必须产出 ASCII：非 ASCII 的 id 会变成「另一个人」。
        val raw = listOf("13800138000", "+86 13800138000", "138-0013-8000")
        raw.forEach { input ->
            val normalized = PhoneIdentity.normalize(input)
            assertTrue("手机号归一化失败：$input", normalized != null)
            assertTrue(
                "归一化结果含非 ASCII：$normalized",
                normalized!!.all { it in '0'..'9' },
            )
        }
    }

    @Test
    fun `日期键是 ASCII 且能原样解析`() {
        val key = DateText.dayKeyOf(2026, 10, 28)
        assertTrue("日期键出现非 ASCII：$key", ascii.matches(key))
        assertEquals("2026-10-28", key)
        val millis = DateText.parseDayKey(key)
        assertEquals(key, DateText.dayKey(millis!!))
        // 月历用字符串比较判断「还没到」，非 ASCII 会让比较跨数字系统错乱。
        assertTrue("2026-11-01 应排在 2026-10-28 之后", "2026-11-01" > DateText.dayKeyOf(2026, 10, 28))
    }

    @Test
    fun `坐标文案是 ASCII`() {
        val location = com.guolongli.dinner.domain.model.GeoLocation(
            latitude = 35.7,
            longitude = 116.4,
            address = "",
            placeName = "",
        )
        val text = location.displayText
        assertTrue("坐标出现非 ASCII：$text", ascii.matches(text))
        assertEquals("35.70000, 116.40000", text)
        assertEquals(text, location.coordinateText)
    }
}
