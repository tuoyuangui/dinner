package com.guolongli.dinner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群组门禁的口径（AGENTS.md「群组门禁」与「数据」）。
 *
 * 这里守的是几件很容易被「优化」掉的事：
 * - 口令必须**明文**保存在模型里（产品要求，禁改哈希）；
 * - 入群只比 join_password，管理员口令不参与入群校验；
 * - 空口令永不匹配（passwd.json 读坏了不能变成谁都能进）；
 * - 比较是常量时间的，且长度不同也必须走完整轮。
 */
class GroupConfigTest {

    private fun config(
        adminPassword: String = "admin-口令",
        joinPassword: String = "J8k2Qm",
    ) = GroupConfig(
        groupId = "group_13800138000",
        adminPhone = "13800138000",
        adminPassword = adminPassword,
        joinPassword = joinPassword,
        adminId = "13800138000",
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    @Test
    fun `群组信息带两份明文口令`() {
        val c = config()
        assertEquals("group_13800138000", c.groupId)
        assertEquals("13800138000", c.adminPhone)
        assertEquals("admin-口令", c.adminPassword)
        assertEquals("J8k2Qm", c.joinPassword)
        assertEquals("13800138000", c.adminId)
    }

    @Test
    fun `入群只比 join_password，管理员口令对也不放行`() {
        val c = config(adminPassword = "管理员专属", joinPassword = "J8k2Qm")
        assertTrue(c.matchesJoinPassword("J8k2Qm"))
        // 拿管理员口令来当入群密码必须被拒绝：两道门各管一件事。
        assertFalse(c.matchesJoinPassword("管理员专属"))
    }

    @Test
    fun `管理员口令只认它自己`() {
        val c = config(adminPassword = "管理员专属", joinPassword = "J8k2Qm")
        assertTrue(c.matchesAdminPassword("管理员专属"))
        assertFalse(c.matchesAdminPassword("J8k2Qm"))
    }

    @Test
    fun `空口令永不匹配`() {
        // passwd.json 读坏 / 老数据缺字段时，不能变成「谁都能进」。
        val broken = config(adminPassword = "", joinPassword = "")
        assertFalse(broken.hasAdminPassword)
        assertFalse(broken.hasJoinPassword)
        assertFalse(broken.matchesJoinPassword(""))
        assertFalse(broken.matchesAdminPassword(""))
        assertFalse(broken.matchesJoinPassword("任意"))
        assertFalse(broken.matchesAdminPassword("任意"))
    }

    @Test
    fun `口令比较区分大小写与前缀`() {
        val c = config()
        assertFalse(c.matchesJoinPassword("j8k2qm"))
        assertFalse(c.matchesJoinPassword("J8k2Q"))
        assertFalse(c.matchesJoinPassword("J8k2Qm "))
    }

    @Test
    fun `常量时间比较对长度不同的串也返回 false`() {
        assertTrue(GroupConfig.constantTimeEquals("abc", "abc"))
        assertFalse(GroupConfig.constantTimeEquals("abc", "abcd"))
        assertFalse(GroupConfig.constantTimeEquals("abc", "abd"))
        assertFalse(GroupConfig.constantTimeEquals("", "x"))
        assertTrue(GroupConfig.constantTimeEquals("", ""))
    }

    @Test
    fun `生成的入群密码长度固定且不含易混字符`() {
        val password = GroupConfig.randomPassword(GroupConfig.JOIN_PASSWORD_LENGTH)
        assertEquals(GroupConfig.JOIN_PASSWORD_LENGTH, password.length)
        // 0/O、1/l/I 在口头转告时最常出错，字符表里刻意没有它们。
        assertFalse(password.any { it in "01OIl" })
        assertTrue(password.all { it in GroupConfig.PASSWORD_ALPHABET })
    }

    @Test
    fun `新建群组的时间戳默认是零，由调用方填`() {
        // 默认值必须是 0 而不是 System.currentTimeMillis()：
        // 模型保持纯数据，时间在建组那一刻注入，测试与反序列化才不会随时间漂移。
        val c = GroupConfig(groupId = "group_13800138000")
        assertEquals(0L, c.createdAt)
        assertEquals(0L, c.updatedAt)
        assertNull(c.adminId)
    }
}
