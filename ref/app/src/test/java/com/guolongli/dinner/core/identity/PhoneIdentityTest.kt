package com.guolongli.dinner.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机号身份规则。
 *
 * 这里守的是「同一个号码在任何设备上都是同一个 id」这条约定：
 * 成员 id 就是手机号本身，群组 id 就是 `group_<管理员手机号>`，
 * 一旦不稳定，重装后填回号码也找不回记录，整个功能就没有意义。
 */
class PhoneIdentityTest {

    @Test
    fun `归一化容忍空格横线与国家码`() {
        assertEquals("13800138000", PhoneIdentity.normalize("13800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("138 0013 8000"))
        assertEquals("13800138000", PhoneIdentity.normalize("138-0013-8000"))
        assertEquals("13800138000", PhoneIdentity.normalize("+86 13800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("008613800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("(138)00138000"))
    }

    @Test
    fun `非法号码一律拒绝`() {
        assertNull(PhoneIdentity.normalize(""))
        assertNull(PhoneIdentity.normalize("abcdefghijk"))
        assertNull(PhoneIdentity.normalize("1380013800"))
        assertNull(PhoneIdentity.normalize("138001380001"))
        // 第二位不能是 0 / 1 / 2
        assertNull(PhoneIdentity.normalize("12800138000"))
        assertNull(PhoneIdentity.normalize("10000000000"))
        assertFalse(PhoneIdentity.isValid("23800138000"))
    }

    @Test
    fun `成员 id 就是手机号本身`() {
        assertEquals("13800138000", PhoneIdentity.idOf("13800138000"))
        // 换设备、重装都不影响：纯函数，无随机数。
        assertEquals(
            "13800138000",
            PhoneIdentity.idOf(PhoneIdentity.normalize("+86 138 0013 8000")!!),
        )
    }

    @Test
    fun `不同号码是不同 id`() {
        assertNotEquals(
            PhoneIdentity.idOf("13800138000"),
            PhoneIdentity.idOf("13800138001"),
        )
    }

    @Test
    fun `群组 id 是 group_ 前缀加管理员号码`() {
        assertEquals("group_13800138000", PhoneIdentity.groupIdOf("13800138000"))
        assertEquals("13800138000", PhoneIdentity.adminPhoneOf("group_13800138000"))
        // 不是合法群组 id 时反解不出来
        assertNull(PhoneIdentity.adminPhoneOf("group_abc"))
        assertNull(PhoneIdentity.adminPhoneOf("13800138000"))
    }

    @Test
    fun `手机号形态才算已绑定身份`() {
        assertTrue(PhoneIdentity.isPhoneId("13800138000"))
        assertFalse(PhoneIdentity.isPhoneId("dev-abc-123"))
        assertFalse(PhoneIdentity.isPhoneId("group_13800138000"))
    }

    @Test
    fun `掩码只露前三后四`() {
        assertEquals("138****8000", PhoneIdentity.mask("13800138000"))
        // 未归一化的输入也能安全展示
        assertEquals("138****8000", PhoneIdentity.maskRaw("+86 138 0013 8000"))
    }
}
