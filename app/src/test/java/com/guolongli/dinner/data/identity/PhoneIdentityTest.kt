package com.guolongli.dinner.data.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机号归一化。
 *
 * 这些用例守的是同一件事：**同一个人只能有一个 id**。
 * 只要归一化漏掉一种写法，成员列表里就会出现第二个「我」，
 * 作者归属也会裂成两半，而且从界面上看不出原因。
 */
class PhoneIdentityTest {

    @Test
    fun `普通号码原样通过`() {
        assertEquals("13800138000", PhoneIdentity.normalize("13800138000"))
    }

    @Test
    fun `去掉加八六前缀`() {
        assertEquals("13800138000", PhoneIdentity.normalize("+8613800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("8613800138000"))
        assertEquals("13800138000", PhoneIdentity.normalize("008613800138000"))
    }

    @Test
    fun `容忍空格与横线`() {
        assertEquals("13800138000", PhoneIdentity.normalize("138 0013 8000"))
        assertEquals("13800138000", PhoneIdentity.normalize("138-0013-8000"))
    }

    @Test
    fun `全角数字也认`() {
        assertEquals("13800138000", PhoneIdentity.normalize("１３８００１３８０００"))
    }

    @Test
    fun `格式不对一律返回 null`() {
        assertNull(PhoneIdentity.normalize(""))
        assertNull(PhoneIdentity.normalize("12800138000")) // 第二位不能是 2
        assertNull(PhoneIdentity.normalize("1380013800")) // 只有 10 位
        assertNull(PhoneIdentity.normalize("138001380000")) // 12 位
        assertNull(PhoneIdentity.normalize("abcdefghijk"))
    }

    @Test
    fun `群组 id 可往返`() {
        val groupId = PhoneIdentity.groupIdOf("13800138000")
        assertEquals("group_13800138000", groupId)
        assertEquals("13800138000", PhoneIdentity.adminPhoneOf(groupId))
    }

    @Test
    fun `非法群组 id 反解为 null`() {
        assertNull(PhoneIdentity.adminPhoneOf("group_123"))
        assertNull(PhoneIdentity.adminPhoneOf("dev-abc"))
        assertNull(PhoneIdentity.adminPhoneOf("13800138000"))
    }

    @Test
    fun `掩码只露头尾`() {
        assertEquals("138****8000", PhoneIdentity.mask("13800138000"))
        // 长度不对就原样返回，绝不截断成更奇怪的东西
        assertEquals("138", PhoneIdentity.mask("138"))
        assertEquals("138****8000", PhoneIdentity.maskRaw("+86 138 0013 8000"))
    }

    @Test
    fun `旧设备 id 不算手机号`() {
        assertTrue(PhoneIdentity.isPhoneId("13800138000"))
        assertFalse(PhoneIdentity.isPhoneId("dev-8f2c1a"))
    }
}
