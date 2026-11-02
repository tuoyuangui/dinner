package com.guolongli.dinner.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成员组的人数上限与展示回退。
 *
 * 上限是硬约束：超过 10 人的名单不许再扩张，
 * 因为整份名单会随 members.json 一起同步，人越多写放大越明显。
 */
class GroupStateTest {

    private fun members(count: Int): List<Member> = (1..count).map { index ->
        Member(id = "u$index", name = "成员$index", joinedAt = index.toLong())
    }

    @Test
    fun `上限是 10 人`() {
        assertEquals(10, GroupState.MAX_MEMBERS)
    }

    @Test
    fun `满员判定`() {
        assertFalse(GroupState(members = members(9)).isFull)
        assertTrue(GroupState(members = members(10)).isFull)
        assertTrue(GroupState(members = members(11)).isFull)
    }

    @Test
    fun `只要还有别人就算已建立关系`() {
        val onlyMe = GroupState(members = members(1), myId = "u1")
        assertFalse(onlyMe.hasOthers)

        val withOther = GroupState(members = members(2), myId = "u1")
        assertTrue(withOther.hasOthers)
    }

    @Test
    fun `按 id 查成员`() {
        val group = GroupState(members = members(3), myId = "u1")
        assertEquals("成员2", group.memberOf("u2")?.name)
        assertNull(group.memberOf("不存在"))
    }

    @Test
    fun `查不到的成员回退为通用称呼而不是裸 id`() {
        val group = GroupState(members = members(1), myId = "u1")
        assertEquals("成员", group.nameOf("陌生人"))
    }

    @Test
    fun `没有显式管理员时最早加入的人当管理员`() {
        val group = GroupState(members = members(3), myId = "u1")
        // members(n) 的 joinedAt 依次为 1、2、3，u1 最早。
        assertEquals("u1", group.effectiveAdminId)
        assertTrue(group.amIAdmin)
    }

    @Test
    fun `显式管理员覆盖默认值`() {
        val group = GroupState(members = members(3), myId = "u1", adminId = "u2")
        assertEquals("u2", group.effectiveAdminId)
        assertFalse(group.amIAdmin)
        assertTrue(group.isAdmin("u2"))
    }

    @Test
    fun `管理员不在名单里时回落到最早加入的人`() {
        val group = GroupState(members = members(2), myId = "u2", adminId = "已退群的人")
        assertEquals("u1", group.effectiveAdminId)
        assertFalse(group.amIAdmin)
    }

    @Test
    fun `本人或管理员才能修改内容`() {
        val group = GroupState(members = members(3), myId = "u3", adminId = "u3")
        // 我（u3）是管理员：可以改别人的
        assertTrue(group.canModify("u2"))
        // u2 既不是管理员也不是作者本人：只能看
        val other = group.copy(myId = "u2")
        assertFalse(other.canModify("u3"))
        assertTrue(other.canModify("u2"))
    }

    @Test
    fun `一个人的时候自己就是管理员`() {
        val solo = GroupState(members = members(1), myId = "u1")
        assertTrue(solo.amIAdmin)
        assertTrue(solo.canModify("u1"))
        // 管理员也能收拾已退群成员留下的内容，作者不在名单里同样允许。
        assertTrue(solo.canModify("已退群的人"))
    }

    @Test
    fun `非管理员改不了已退群成员的内容`() {
        val group = GroupState(members = members(2), myId = "u2", adminId = "u1")
        assertFalse(group.canModify("已退群的人"))
    }

    @Test
    fun `成员在名单里但昵称为空时回退到成员`() {
        // 远端 members.json 可能是老版本写的，没有 name 字段，读出来就是空串。
        val group = GroupState(
            members = listOf(Member(id = "u1", name = "", joinedAt = 1)),
            myId = "u1",
        )
        assertEquals(GroupState.DEFAULT_MEMBER_NAME, group.nameOf("u1"))
    }

    @Test
    fun `名单里没有这个人时回退到成员`() {
        val group = GroupState(members = members(1), myId = "u1")
        assertEquals(GroupState.DEFAULT_MEMBER_NAME, group.nameOf("ghost"))
    }

    @Test
    fun `有昵称就原样返回`() {
        val group = GroupState(members = members(2), myId = "u1")
        assertEquals("成员2", group.nameOf("u2"))
    }
}
