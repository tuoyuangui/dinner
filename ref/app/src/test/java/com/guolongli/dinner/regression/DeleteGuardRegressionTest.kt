package com.guolongli.dinner.regression

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.domain.model.GroupState
import com.guolongli.dinner.domain.model.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0.0 修复项回归：云端删除的路径越界防护。
 *
 * 背景：删云端之前只按记录里存的 remotePath 直接发删除请求，既没校验它是否落在
 * 当前数据根目录之下，也没判断删除是否真的成功——路径被脏数据改写时会去删
 * 根目录之外的东西，删失败却把本地标成已删，云端留下孤儿文件。
 *
 * 现在删之前先过 [FileNames.requireInsideRoot]，不在根目录之下直接拒绝。
 */
class DeleteGuardRegressionTest {

    private val root = "/apps/dinner/group_17380203573"

    @Test
    fun `根目录之下的路径放行`() {
        FileNames.requireInsideRoot("$root/members/138/meals/2026/10/a.jpg", root)
        // 根目录本身也算「在之内」，列目录与建目录都会用到
        FileNames.requireInsideRoot(root, root)
    }

    @Test
    fun `同级但不同前缀的目录不算在根目录之内`() {
        // 经典陷阱：group_173802035731 以 root 开头，但它是另一个群组目录
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("${root}1/config/records.json", root)
        }
    }

    @Test
    fun `根目录之外的路径一律拒绝`() {
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("/apps/other/records.json", root)
        }
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("/apps/dinner-2/records.json", root)
        }
    }

    @Test
    fun `相对路径与路径穿越被拒绝`() {
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("apps/dinner/records.json", root)
        }
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("$root/../other/records.json", root)
        }
    }

    @Test
    fun `空白路径被拒绝`() {
        assertThrows(DinnerException.Validation::class.java) {
            FileNames.requireInsideRoot("   ", root)
        }
    }
}

/**
 * 3.0.0 修复项回归：删除 / 编辑权限的判定口径。
 *
 * 背景：权限曾经写成 `createdBy == myId || amIAdmin`。成员流首帧到达之前
 * myId 与 effectiveAdminId 都是空串，`"" == ""` 让 amIAdmin 恒为真，
 * 于是在身份就绪前点删除，非本人的云端记录会被真删。
 *
 * 修复口径：权限一律走 [GroupState.canModify]；调用方在 myId 或作者 id
 * 为空白时必须按「无权」处理。这里把「空 id 不得放行」钉死。
 */
class PermissionGuardRegressionTest {

    private fun group(
        myId: String,
        ids: List<String> = listOf("u1", "u2"),
        adminId: String? = null,
    ): GroupState = GroupState(
        members = ids.mapIndexed { index, id -> Member(id = id, name = "成员$index", joinedAt = index + 1L) },
        myId = myId,
        adminId = adminId,
    )

    @Test
    fun `身份未就绪时空 id 是危险的默认值`() {
        // 复现原缺陷的成因：成员名单还没到，myId 与 adminId 都是空，
        // effectiveAdminId 回落到 myId（空串），amIAdmin 于是为真。
        val pending = GroupState(members = emptyList(), myId = "")
        assertTrue(pending.amIAdmin)

        // 因此调用方必须用「myId 是否为空白」先拦一道，
        // 不能只靠 GroupState.amIAdmin。这里固定该判据的语义。
        assertFalse(pending.myId.isNotBlank())
    }

    @Test
    fun `非管理员改不了别人与无主内容`() {
        val me = group(myId = "u2", adminId = "u1")
        assertTrue(me.canModify("u2")) // 自己的
        assertFalse(me.canModify("u1")) // 别人的：不行
        assertFalse(me.canModify("已退群的人")) // 无主内容：也不行
    }

    @Test
    fun `管理员可以改所有人的内容`() {
        val admin = group(myId = "u1", adminId = "u1")
        assertTrue(admin.canModify("u2"))
        assertTrue(admin.canModify("已退群的人"))
    }

    @Test
    fun `空作者 id 不等于自己`() {
        val me = group(myId = "u2", adminId = "u1")
        // 脏数据里 createdBy 可能是空串；它不该被当成「我的」而放行
        assertFalse(me.canModify(""))
    }

    @Test
    fun `显式管理员不在名单里时回落到最早加入的人`() {
        val stale = group(myId = "u2", adminId = "已退群的人")
        assertEquals("u1", stale.effectiveAdminId)
        assertFalse(stale.amIAdmin)
    }
}
