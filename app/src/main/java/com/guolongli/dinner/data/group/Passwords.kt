package com.guolongli.dinner.data.group

/**
 * 口令的生成与比对。
 *
 * 两份口令都以**明文**存在云端 `config/passwd.json`（产品要求，不改哈希）：
 * 文件就躺在共享网盘目录里，任何成员都能读，哈希不会让口令更难拿到，
 * 只会让「管理员在应用里输入口令」变成一次看起来更安全的比对。
 * 产品的选择是：诚实存明文，并在界面上说明「这是一道门闩，不是加密」。
 *
 * 这里只做两件正事：**空口令永不匹配**、**常量时间比较**。
 */
object Passwords {

    /**
     * 生成用的字符表。去掉容易看错的 0/O、1/l/I——
     * 入群密码本来就是管理员私下念给成员听的东西。
     */
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** 入群密码长度：短到能口头转告，长到不会被随手猜中 */
    const val JOIN_LENGTH = 6

    /** 管理员口令最小长度：它管的是「改别人的东西」 */
    const val ADMIN_MIN_LENGTH = 6

    /** 口令长度上限，只用来挡住粘贴进来的超长串（明文会整段上云） */
    const val MAX_LENGTH = 64

    /** 生成一个可口头转告的随机口令，形如 `F4K9-2Q` */
    fun randomJoinCode(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val raw = CharArray(JOIN_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
        return raw.take(3) + "-" + raw.drop(3)
    }

    /**
     * 常量时间比较。
     *
     * **任一侧为空一律判否**：云端 `passwd.json` 缺字段时读出来是空串，
     * 如果 `equals("", "") == true`，那就等于「没设密码谁都能进」——
     * 而这张文件就躺在共享网盘里，谁都能把它清空。
     * 把这条规则放在比较函数本身，而不是指望每个调用点都记得先判空。
     *
     * 时序方面：没有服务端、没有网络往返，侧信道在本地几乎不可利用；
     * 但常量时间成本近乎为零，还能避免「按字符提前返回」被复制到别处。
     */
    fun equals(expected: String, actual: String): Boolean {
        if (expected.isEmpty() || actual.isEmpty()) return false
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

    /** 口令是否可用：非空、去空白后长度够、不超上限 */
    fun isValidAdmin(raw: String): Boolean {
        val value = raw.trim()
        return value.length in ADMIN_MIN_LENGTH..MAX_LENGTH
    }

    fun isValidJoin(raw: String): Boolean {
        val value = raw.trim()
        return value.length in 4..MAX_LENGTH
    }
}
