package com.guolongli.dinner.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 口令的比对与生成。
 *
 * 两条不能破的规则：
 * 1. **空口令永不匹配**——云端 passwd.json 缺字段时读出来是空串，
 *    如果空串能匹配，等于「没设密码就谁都能进」；
 * 2. 生成的口令必须落在用的字符表里，否则管理员念出来的码成员打不进去。
 */
class PasswordsTest {

    @Test
    fun `相同口令匹配`() {
        assertTrue(Passwords.equals("ABC123", "ABC123"))
    }

    @Test
    fun `不同口令不匹配`() {
        assertFalse(Passwords.equals("ABC123", "ABC124"))
        // 只差长度也要判否，不能因为前缀相同就算过
        assertFalse(Passwords.equals("ABC123", "ABC1234"))
        assertFalse(Passwords.equals("ABC123", "abc123"))
    }

    @Test
    fun `空口令永不匹配`() {
        assertFalse(Passwords.equals("", ""))
        assertFalse(Passwords.equals("ABC123", ""))
        assertFalse(Passwords.equals("", "ABC123"))
    }

    @Test
    fun `生成的口令只有安全字符`() {
        val code = Passwords.randomJoinCode()
        assertEquals(7, code.length) // 6 位 + 中间那个连字符
        assertEquals('-', code[3])
        code.filter { it != '-' }.forEach { ch ->
            assertTrue("出现了不该出现的字符：$ch", ch in Passwords.ALPHABET)
        }
    }

    @Test
    fun `两次生成不会总是一样`() {
        val samples = (0 until 20).map { Passwords.randomJoinCode() }.toSet()
        assertTrue("随机口令重复太多，像是没取到熵", samples.size > 1)
    }

    @Test
    fun `管理员口令长度下限是六位`() {
        assertFalse(Passwords.isValidAdmin(""))
        assertFalse(Passwords.isValidAdmin("12345"))
        assertTrue(Passwords.isValidAdmin("123456"))
        // 前后空白不算长度
        assertFalse(Passwords.isValidAdmin("   123   "))
        assertTrue(Passwords.isValidAdmin("  123456  "))
    }

    @Test
    fun `入群口令可以是四位以上`() {
        assertFalse(Passwords.isValidJoin("123"))
        assertTrue(Passwords.isValidJoin("1234"))
        assertTrue(Passwords.isValidJoin("F4K9-2Q"))
    }

    @Test
    fun `超长口令不接受`() {
        // 明文会整段上云，粘贴一个超长串没有意义
        assertFalse(Passwords.isValidAdmin("1".repeat(Passwords.MAX_LENGTH + 1)))
        assertFalse(Passwords.isValidJoin("1".repeat(Passwords.MAX_LENGTH + 1)))
    }
}
