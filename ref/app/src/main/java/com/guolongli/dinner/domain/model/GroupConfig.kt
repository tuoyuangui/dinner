package com.guolongli.dinner.domain.model

/**
 * 群组的基础信息，落在 `group_<管理员手机号>/config/passwd.json`。
 *
 * 这个文件是群组唯一的一道门，里面存两份口令（明文，产品要求，禁改哈希）：
 * - [adminPassword]：管理员口令。管理员在应用里显式输入它，才能改 / 删别人的记录、
 *   改入群密码、转让管理权。`AdminSession` 只在内存里记「这次运行已经对上过」。
 * - [joinPassword]：动态入群密码。成员入群只需要它，不绑电话、不验管理员口令。
 *
 * 为什么明文：这是一份放在自己网盘里的小册子，没有服务端，也就没有真正的鉴权。
 * 哈希不会让共享网盘目录里的口令更难拿（成员本来就能读这个文件），
 * 只会让「管理员在应用里输入口令」变成一次哈希比对，多一层看起来像安全的东西。
 * 产品的选择是：诚实存明文，并在界面上如实说明「这是一道门闩，不是加密」。
 *
 * [adminPhone] 是建组者的手机号，只用来推导目录名 `group_<号码>/`；
 * 群组身份一律以目录名为准，文件里的 `group_id` / `admin_phone` 只做参考。
 *
 * [adminId] 是可转让的当前管理员成员 id（成员 id = 归一化手机号）。
 * 它与 [adminPhone] 是两套口径：转让管理权改的是 [adminId]，目录名不会跟着改；
 * 拿到管理员身份也不等于拿到 [adminPassword]。
 */
data class GroupConfig(
    val groupId: String,
    val adminPhone: String = "",
    /** 管理员口令（明文）。管理员改别人内容前必须显式输入并被校验通过。 */
    val adminPassword: String = "",
    /** 动态入群密码（明文）。加人的唯一入口：管理员换码后转告。 */
    val joinPassword: String = "",
    /** 当前管理员成员 id（可转让）；为空时回落到建组者，再回落到最早加入者。 */
    val adminId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 口令为空一律视为「不可用」，调用方必须拒绝放行，不得拿空串当通配。 */
    val hasAdminPassword: Boolean get() = adminPassword.isNotBlank()

    val hasJoinPassword: Boolean get() = joinPassword.isNotBlank()

    /**
     * 校验入群密码。
     *
     * 只比 [joinPassword]，不比对管理员口令、不绑定电话。
     * 空口令永不匹配：`passwd.json` 读坏了不能变成「谁都能进」。
     */
    fun matchesJoinPassword(input: String): Boolean {
        val expected = joinPassword
        return expected.isNotBlank() && constantTimeEquals(expected, input)
    }

    /** 校验管理员口令。空口令永不匹配。 */
    fun matchesAdminPassword(input: String): Boolean {
        val expected = adminPassword
        return expected.isNotBlank() && constantTimeEquals(expected, input)
    }

    companion object {
        /**
         * 口令生成的字符表。
         *
         * 去掉了容易看错的 0/O、1/l/I：这两组在口头转告和手抄时最常出错，
         * 而入群密码本来就是「管理员私下念给成员」的东西。
         */
        const val PASSWORD_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** 入群密码长度。6 位足够短到能口头转告，又足够长到不会被随手猜中。 */
        const val JOIN_PASSWORD_LENGTH = 6

        /** 管理员口令长度。比入群密码长，因为它管的是「改别人的东西」。 */
        const val ADMIN_PASSWORD_LENGTH = 8

        /**
         * 口令长度上限。
         *
         * 只用来挡住粘贴进来的超长串（明文会整段写进 passwd.json 上云），
         * 不参与正确性判断。
         */
        const val MAX_PASSWORD_LENGTH = 64

        /**
         * 常量时间比较。
         *
         * 没有服务端、没有网络往返，时序侧信道在本地几乎不可利用；
         * 但成本近乎为零，且能避免「按字符提前返回」这类写法被复制到别处。
         */
        fun constantTimeEquals(expected: String, actual: String): Boolean {
            val a = expected.toByteArray(Charsets.UTF_8)
            val b = actual.toByteArray(Charsets.UTF_8)
            var diff = a.size xor b.size
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val x = if (i < a.size) a[i].toInt() else 0
                val y = if (i < b.size) b[i].toInt() else 0
                diff = diff or (x xor y)
            }
            return diff == 0
        }

        /** 生成一个可口头转告的随机口令。 */
        fun randomPassword(
            length: Int,
            random: java.security.SecureRandom = java.security.SecureRandom(),
        ): String {
            require(length in 1..MAX_PASSWORD_LENGTH) { "口令长度必须在 1..$MAX_PASSWORD_LENGTH" }
            val alphabet = PASSWORD_ALPHABET
            val chars = CharArray(length) { alphabet[random.nextInt(alphabet.length)] }
            return String(chars)
        }
    }
}
