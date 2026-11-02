package com.guolongli.dinner.data.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理员解锁态，**只在内存里**（AGENTS.md 红线：禁持久化）。
 *
 * 为什么只放内存：
 * - 这是一道「这次运行里已经对上过管理员口令」的门闩，不是凭证。
 *   落盘之后，下一次冷启动应用会直接认为管理员已登录，口令就白设了；
 * - 进程被杀、用户手动退出、换群组，都必须立刻回到未解锁状态。
 * - 它不持有口令本身，只记「哪个群组的哪个成员已经验过」，
 *   所以即使 dump 内存也拿不到口令。
 *
 * 后端（仓储层）仍必须每次调用 [requireAdminUnlocked]，不能只靠界面藏按钮：
 * 界面只是不展示入口，绕过界面的调用路径（分享、深链、后台任务）一样要拦住。
 */
@Singleton
class AdminSession @Inject constructor() {

    /** 已解锁的群组 id 与管理员成员 id。两者成对存在，缺一即视为未解锁。 */
    private var unlockedGroupId: String? = null
    private var unlockedAdminId: String? = null

    /**
     * 当时验通过的那份管理员口令。
     *
     * 只用于「与云端再比一次」判断口令是否已被更换，**不落盘、不进日志**。
     * 它不是凭证缓存：每次受保护操作都会拿它和云端最新值比对，
     * 云端一换，本进程的解锁态立刻作废（见 AdminGate）。
     */
    private var unlockedPassword: String? = null

    /** 解锁发生的时间。仅用于界面展示「本次运行内有效」，不参与鉴权判断。 */
    private var unlockedAt: Long = 0L

    /** 某个群组是否已经在本进程内验过管理员口令。 */
    @Synchronized
    fun isUnlocked(groupId: String): Boolean {
        val g = groupId.trim()
        return g.isNotEmpty() && unlockedGroupId == g && !unlockedAdminId.isNullOrBlank()
    }

    /** 本进程内已解锁的管理员成员 id；未解锁时为 null。 */
    @Synchronized
    fun unlockedAdminId(groupId: String): String? =
        if (isUnlocked(groupId)) unlockedAdminId else null

    @Synchronized
    fun unlockedAt(groupId: String): Long? =
        if (isUnlocked(groupId)) unlockedAt else null

    /**
     * 校验通过后记下解锁态。
     *
     * 同一个群组重复解锁会覆盖旧的成员 id（比如管理员把身份转让给了别人，
     * 又在同一台设备上验了新的口令）。
     */
    @Synchronized
    fun unlock(groupId: String, adminId: String, password: String) {
        val g = groupId.trim()
        val a = adminId.trim()
        if (g.isEmpty() || a.isEmpty() || password.isBlank()) return
        unlockedGroupId = g
        unlockedAdminId = a
        unlockedPassword = password
        unlockedAt = System.currentTimeMillis()
    }

    /** 当时验通过的口令，供与云端最新值比对；未解锁时为 null。 */
    @Synchronized
    fun rememberedAdminPassword(groupId: String): String? =
        if (isUnlocked(groupId)) unlockedPassword else null

    /** 锁定（退出管理员 / 换群组 / 解除授权）。 */
    @Synchronized
    fun lock() {
        unlockedGroupId = null
        unlockedAdminId = null
        unlockedPassword = null
        unlockedAt = 0L
    }

    /**
     * 后端强制校验：没解锁就抛错，不得静默放行。
     *
     * 抛 [com.guolongli.dinner.core.error.DinnerException.Validation] 而不是布尔返回值：
     * 返回 false 的写法很容易被调用方忽略（`val ok = require...` 然后忘了判），
     * 抛错则一定会浮到界面，失败不会被当成成功。
     */
    @Synchronized
    fun requireAdminUnlocked(groupId: String) {
        if (!isUnlocked(groupId)) {
            throw com.guolongli.dinner.core.error.DinnerException.Validation(
                "这次运行还没有验证管理员口令，请先在群组页输入口令"
            )
        }
    }

    /**
     * 转让管理权后把解锁身份同步到新管理员。
     *
     * 只改「解锁的是谁」，不改「验的是哪份口令」：转让不等于拿到口令
     * （AGENTS 里记着这个张力），所以新管理员在别的设备上仍然要自己验一次。
     * 本机因为刚刚亲自验过、也亲自转让过，保持解锁是合理的。
     */
    @Synchronized
    fun adoptAdmin(groupId: String, adminId: String) {
        if (!isUnlocked(groupId)) return
        val a = adminId.trim()
        if (a.isEmpty()) return
        unlockedAdminId = a
    }
}
