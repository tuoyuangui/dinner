package com.guolongli.dinner.core.identity

/**
 * 手机号就是身份。
 *
 * 规则只有一条：**成员 id = 归一化后的手机号本身**，群组 id = `group_<管理员手机号>`。
 * 不做任何哈希派生——重装、换机、清数据之后，用户只要重新填入自己的号码，
 * createdBy、成员条目与头像就全部对得上，历史记录跟着回来。
 *
 * 为什么可以明文当 id：
 * - 同一个号码在任何设备上都得到同一个 id，可复现、可核对；
 * - 群组目录名（`group_<号码>`）本来就带号码，id 明文不再额外泄露什么；
 * - 真正的安全边界是「能不能授权同一个网盘账号」，不是 id 的写法。
 *
 * 仍然要 [normalize]：容忍 `+86`、空格、横线这些输入写法，统一成 11 位数字，
 * 避免「同一个人两个 id」。
 */
object PhoneIdentity {

    /** 群组 id 前缀：群组目录名形如 `group_13800138000`。 */
    const val GROUP_PREFIX = "group_"

    /** 中国大陆手机号：1 开头、第二位 3-9、共 11 位。 */
    private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")

    /**
     * 归一化用户输入。
     *
     * 容忍 `+86 138 0013 8000`、`138-0013-8000`、`(138)00138000` 这些写法，
     * 统一成 11 位数字；不合法返回 null。
     *
     * **只认 ASCII 数字 0-9**，不用 `Char.isDigit()`：
     * 后者按 Unicode 判定，`١٣٨٠٠١٣٨٠٠٠`（阿拉伯-印度数字）也会被当成数字保留下来，
     * 但 `PHONE_REGEX` 里的 `\d` 在 Java/Kotlin 默认是 ASCII-only，一个都不匹配，
     * 于是整串被判非法。用户填的是自己真实的号码，界面却只说「请填写 11 位手机号」——
     * 而且阿拉伯语 / 波斯语输入法下这几乎是必然发生。
     *
     * 这类输入的正确处理是**先转成 ASCII 再判**：屏幕上显示的就是「13800138000」，
     * 用户能一眼看出对不对，落库的 id 也与其他设备一致（成员 id 就是手机号本身，
     * 非 ASCII 版本会变成另一个人）。
     */
    fun normalize(raw: String): String? {
        val digits = toAsciiDigits(raw).filter { it in '0'..'9' }
        if (digits.isEmpty()) return null

        val withoutCountryCode = when {
            // +86 / 0086 / 86 前缀：只在长度超过 11 位时才剥离，避免误伤 1 开头的号码。
            digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
            digits.length == 15 && digits.startsWith("0086") -> digits.drop(4)
            else -> digits
        }
        return withoutCountryCode.takeIf { PHONE_REGEX.matches(it) }
    }

    /**
     * 把各种数字系统（阿拉伯-印度、波斯、全角等）的十进制数字统一成 ASCII。
     *
     * `Character.digit(c, 10)` 认得这些手写数字，返回 0..9；
     * 非数字字符原样保留，交给调用方决定丢不丢（手机号允许 +、-、空格、括号）。
     *
     * 手机号输入框用它，而不是自己写 `filter { it in '0'..'9' }`：
     * 直接滤掉非 ASCII 会让阿拉伯语用户「打字没反应」，一个字都进不去，
     * 分不清是输入法坏了还是应用坏了。转成 ASCII 才是对的——屏幕上立刻能看到数字。
     */
    fun toAsciiDigits(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            val value = Character.digit(ch, 10)
            if (value in 0..9) append(('0'.code + value).toChar()) else append(ch)
        }
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /**
     * 成员 id 就是手机号本身。
     *
     * 入参必须是已归一化的号码；传原始输入会得到不同的 id，
     * 所以对外一律先 [normalize] 再 [idOf]。
     */
    fun idOf(normalizedPhone: String): String = normalizedPhone

    /** 群组 id：`group_<管理员手机号>`。 */
    fun groupIdOf(normalizedAdminPhone: String): String = GROUP_PREFIX + normalizedAdminPhone

    /** 从群组 id 反解管理员手机号；不是合法群组 id 时返回 null。 */
    fun adminPhoneOf(groupId: String): String? {
        if (!groupId.startsWith(GROUP_PREFIX)) return null
        return groupId.removePrefix(GROUP_PREFIX).takeIf { PHONE_REGEX.matches(it) }
    }

    /** 是否是手机号形态的成员 id（用于判断「已绑定身份」，旧的 `dev-*` 不算）。 */
    fun isPhoneId(id: String): Boolean = PHONE_REGEX.matches(id)

    /** 展示用掩码：138****8000。绝不整段显示，截图分享时也不会泄露完整号码。 */
    fun mask(normalizedPhone: String): String {
        if (normalizedPhone.length != 11) return normalizedPhone
        val head = normalizedPhone.take(3)
        val tail = normalizedPhone.takeLast(4)
        return "$head****$tail"
    }

    /** 掩码展示：入参可以是未归一化的输入。 */
    fun maskRaw(raw: String): String = normalize(raw)?.let { mask(it) } ?: raw
}
