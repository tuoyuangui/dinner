package com.guolongli.dinner.data.session

import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.group.Passwords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理员身份的唯一判定处。
 *
 * 三条规则，写在一处免得各页面自己解释：
 *
 * 1. **谁是管理员**：`profile.adminId` 指向的那位成员（默认是建组者本人）。
 *    它随云端 `config/passwd.json` 的 `admin_id` 同步，所以转让之后所有设备口径一致。
 * 2. **什么算管理员操作**：改 / 删别人的记录与评论、换入群密码、转让管理权。
 * 3. **解锁只在本次运行内有效**：口令以明文存在 profile 与云端 passwd.json
 *    （产品要求），这里只记住「这次运行已经对上过哪一份口令」。
 *    云端换过口令之后，[invalidateIfChanged] 会立刻把解锁态作废——
 *    只在内存里挂个布尔值，等于口令没有失效机制。
 *
 * 界面把按钮藏起来只是方便，真正的门槛是 [canManage] / [canTouchCloud]：
 * 仓储在写云端之前会调用它们，绕不过去。
 */
class AdminSession(private val profile: ProfileStore) {

    private val _unlocked = MutableStateFlow(false)

    /** 本次运行是否已解锁，界面用它决定按钮是否置灰 */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val isUnlocked: Boolean get() = _unlocked.value

    /** 当时验通过的那份口令，只用于「云端有没有被换掉」的比对，不落盘、不进日志 */
    private var rememberedPassword: String? = null

    /** 本机这位成员的 id：绑了手机号就是号码，否则是设备 id */
    val memberId: String get() = profile.memberId

    /** 本机是不是管理员 */
    val iAmAdmin: Boolean get() = profile.adminId == memberId

    fun isAdmin(memberId: String): Boolean = memberId.isNotBlank() && memberId == profile.adminId

    /**
     * 校验管理员口令（本机这一份）。
     *
     * 真正的入口走 [com.guolongli.dinner.data.group.GroupService.verifyAdmin]：
     * 它先读云端 passwd.json 再比对，保证「别人在别的设备改了口令，这边立刻失效」。
     * 这里保留同步版本，是为了不联网时的兜底与单元测试。
     */
    fun unlock(input: String): Boolean {
        val value = input.trim()
        if (value.isEmpty()) return false
        val expected = profile.adminCode
        if (expected.isBlank() || !Passwords.equals(expected, value)) return false
        _unlocked.value = true
        rememberedPassword = value
        return true
    }

    /** 解锁态与云端口令已经对齐（由 GroupService 在比对成功后调用） */
    fun markVerified(password: String) {
        if (password.isBlank()) return
        _unlocked.value = true
        rememberedPassword = password
    }

    /**
     * 云端口令与当初验的那份不一致 → 立刻锁定。
     *
     * @return true 表示这一下把解锁态作废了（界面可以据此提示「口令已在别处更换」）
     */
    fun invalidateIfChanged(remoteAdminPassword: String): Boolean {
        if (!isUnlocked) return false
        val remembered = rememberedPassword ?: return false
        if (Passwords.equals(remoteAdminPassword, remembered)) return false
        lock()
        return true
    }

    /**
     * 转让管理权后把解锁身份同步到新管理员。
     *
     * 只改「解锁的是谁」，不改「验的是哪份口令」：转让不等于拿到口令，
     * 新管理员在别的设备上仍然要自己验一次。本机因为刚亲自验过、也亲自转让过，
     * 保持解锁是合理的。
     */
    fun adoptAdmin(memberId: String) {
        if (memberId.isBlank()) return
        profile.adminId = memberId
    }

    fun lock() {
        _unlocked.value = false
        rememberedPassword = null
    }

    /** 改 / 删这条记录：自己的随便改，别人的要先解锁管理员 */
    fun canManage(authorId: String): Boolean = isUnlocked || authorId == memberId

    /**
     * 能不能动云端的那份文件。
     * 普通成员只能删自己上传的（README 的硬约束）；管理员解锁后可以处理所有人的。
     */
    fun canTouchCloud(authorId: String): Boolean = authorId == memberId || isUnlocked

    /** 转让管理权 / 换入群密码这类群级操作 */
    fun requireAdmin(): Boolean = isUnlocked
}
