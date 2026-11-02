package com.guolongli.dinner.core.util

import java.util.Locale

/**
 * 机器可读字符串的 ASCII 数字拼装。
 *
 * 存在的理由只有一个：**`String.format` 默认按设备 Locale 选数字字形**。
 * 设备语言是阿拉伯语（`ar-EG-u-nu-arab`）或波斯语（`fa-IR`）时，
 * `String.format("%04d", 2026)` 给出的是 `٢٠٢٦`（阿拉伯-印度数字），
 * 而这类字符串会被写进网盘路径与文件名：
 *
 * - 云端目录变成 `members/<号码>/meals/٢٠٢٦/١٠`；
 * - 媒体文件名变成 `٢٠٢٦-١٠-٢٩_١١_٢٢_٣٥.jpg`；
 * - 于是 [FileNames.MEDIA_NAME_REGEX]、[FileNames.dayMillisOf] 用 `[0-9]` 匹配
 *   一律失败——自己传上去的文件，自己认不回来，恢复与兜底扫描全瞎；
 * - 日期键 `yyyy-MM-dd` 也会变成非 ASCII，`toIntOrNull` 解析失败，
 *   `key > todayKey` 的字符串比较跨数字系统直接给错答案。
 *
 * 所以凡是要跨设备、跨平台、跨语言都保持同一形态的字符串（路径 / 文件名 / 日期键 /
 * 入群密码），一律走这里，绝不走 `String.format` 的默认 Locale。
 *
 * 展示给用户看的数字（时长、日期标题）不受此限，那些本地化是正确的。
 */
object Ascii {

    /** 定宽补零的十进制：`%0<width>d` 的 ASCII 版。 */
    fun pad(value: Int, width: Int): String = String.format(Locale.ROOT, "%0${width}d", value)

    /** 定长小数的十进制，如 [decimals] = 5 得到 `35.70000`。 */
    fun fixed(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    /**
     * 两位十六进制小写。
     *
     * 专门接 [Byte]：MD5 / SHA-256 的摘要字节是**有符号**的，`0xab` 读出来是 `-85`，
     * 直接 `"%02x".format(it)` 会得到 `ffffffab`（十四个十六进制位），
     * 拼出来的 md5 比云端那份长一倍，比对永远不相等。
     * 这里先 `and 0xff` 归到 0..255，再按两位输出。
     */
    fun hex2(value: Byte): String = String.format(Locale.ROOT, "%02x", value.toInt() and 0xff)
}
