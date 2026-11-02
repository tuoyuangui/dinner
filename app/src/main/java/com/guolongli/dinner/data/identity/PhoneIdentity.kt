package com.guolongli.dinner.data.identity

/**
 * 手机号就是身份。
 *
 * 规则只有一条：**成员 id = 归一化后的手机号本身**，群组 id = `group_<管理员手机号>`。
 * 不做哈希派生——重装、换机、清数据之后，只要重新填同一个号码，
 * 作者归属、成员条目、头像就全部对得上，历史记录跟着回来。
 *
 * 为什么可以明文当 id：
 * - 同一个号码在任何设备上都得到同一个 id，可复现、可核对；
 * - 群组本来就要靠「管理员手机号」定位，id 明文不再额外泄露什么；
 * - 真正的安全边界是「能不能授权同一个网盘账号」，不是 id 的写法。
 *
 * 仍然必须 [normalize]：容忍 `+86`、空格、横线，统一成 11 位数字，
 * 否则「同一个人两个 id」，成员列表里会凭空多出一个人。
 */
object PhoneIdentity {

    /** 群组 id 前缀：`group_13800138000` */
    const val GROUP_PREFIX = "group_"

    /** 中国大陆手机号：1 开头、第二位 3-9、共 11 位 */
    private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")

    /**
     * 归一化用户输入，不合法返回 null。
     *
     * 只认 ASCII 数字 0-9：`\d` 在 JVM 上默认 ASCII-only，而 `Char.isDigit()`
     * 连阿拉伯-印度数字也认，两者混用会出现「输入框显示了一串数字、却提示格式不对」。
     * 所以先把各种数字系统转成 ASCII（见 [toAsciiDigits]），再判格式。
     */
    fun normalize(raw: String): String? {
        val digits = toAsciiDigits(raw).filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        val stripped = when {
            digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
            digits.length == 15 && digits.startsWith("0086") -> digits.drop(4)
            else -> digits
        }
        return stripped.takeIf { PHONE_REGEX.matches(it) }
    }

    /** 把全角 / 阿拉伯-印度等十进制数字统一成 ASCII，其余字符原样保留 */
    fun toAsciiDigits(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            val value = Character.digit(ch, 10)
            if (value in 0..9) append(('0'.code + value).toChar()) else append(ch)
        }
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /** 成员 id：就是归一化后的号码本身 */
    fun idOf(normalizedPhone: String): String = normalizedPhone

    /** 群组 id */
    fun groupIdOf(normalizedAdminPhone: String): String = GROUP_PREFIX + normalizedAdminPhone

    /** 从群组 id 反解管理员号码；不是合法群组 id 时返回 null */
    fun adminPhoneOf(groupId: String): String? {
        if (!groupId.startsWith(GROUP_PREFIX)) return null
        return groupId.removePrefix(GROUP_PREFIX).takeIf { PHONE_REGEX.matches(it) }
    }

    /** 是否是手机号形态的 id（旧的 `dev-*` / UUID 不算） */
    fun isPhoneId(id: String): Boolean = PHONE_REGEX.matches(id)

    /** 展示用掩码：138****8000。绝不整段显示，截图分享时也不泄露完整号码 */
    fun mask(normalizedPhone: String): String {
        if (normalizedPhone.length != 11) return normalizedPhone
        return normalizedPhone.take(3) + "****" + normalizedPhone.takeLast(4)
    }

    /** 掩码展示：入参可以是未归一化的输入 */
    fun maskRaw(raw: String): String = normalize(raw)?.let { mask(it) } ?: raw
}
