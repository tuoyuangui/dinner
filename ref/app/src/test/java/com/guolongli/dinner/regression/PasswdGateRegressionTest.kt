package com.guolongli.dinner.regression

import com.guolongli.dinner.data.group.PasswdDto
import com.guolongli.dinner.data.group.toDomain
import com.guolongli.dinner.data.group.toDto
import com.guolongli.dinner.data.session.AdminSession
import com.guolongli.dinner.domain.model.GroupConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群组门禁回归（AGENTS「群组门禁」）。
 *
 * 这几条不是「锦上添花」的用例，而是文档里点名的红线行为：
 *
 * 1. `passwd.json` 里的口令**明文**，字段名是 `admin_password` / `join_password`；
 * 2. 群组身份以目录名为准，文件里的 `group_id` / `admin_phone` 只做参考；
 * 3. `AdminSession` 只在内存，且记着「当时验的是哪份口令」，
 *    云端一换口令该解锁态必须能失效；
 * 4. `requireAdminUnlocked` 在未解锁时抛错，而不是静默返回。
 *
 * 「写后回读不一致要抛错」「verifyJoinPassword 先读云端」两条需要网盘数据源，
 * 放在 [PasswdWriteBackRegressionTest] 里用 fake 数据源覆盖。
 */
class PasswdGateRegressionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `passwd json 用明文口令字段，不做哈希`() {
        val config = GroupConfig(
            groupId = "group_13800138000",
            adminPhone = "13800138000",
            adminPassword = "admin-口令",
            joinPassword = "J8k2Qm",
            adminId = "13800138000",
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        val payload = config.toDto()
        val encoded = json.encodeToString(PasswdDto.serializer(), payload)

        // 明文原样出现在 JSON 里：这是产品要求，禁改哈希。
        assertTrue(encoded.contains("\"admin_password\":\"admin-口令\""))
        assertTrue(encoded.contains("\"join_password\":\"J8k2Qm\""))
        // 没有任何哈希字段混进来。
        assertFalse(encoded.contains("salt"))
        assertFalse(encoded.contains("hash"))
        // 解析回来仍是同一份明文。
        val decoded = json.decodeFromString(PasswdDto.serializer(), encoded).toDomain(config.groupId)
        assertTrue(decoded.matchesAdminPassword("admin-口令"))
        assertTrue(decoded.matchesJoinPassword("J8k2Qm"))
        assertEquals("13800138000", decoded.adminId)
    }

    @Test
    fun `群组身份以目录名为准，不信文件里的 group_id 与 admin_phone`() {
        // 实测过的脏数据：目录名是 group_17380203573，文件里写着 17380203576。
        val dto = PasswdDto(
            groupId = "group_17380203576",
            adminPhone = "17380203576",
            adminPassword = "pw",
            joinPassword = "join-pw",
        )
        val domain = dto.toDomain("group_17380203573")
        assertEquals("group_17380203573", domain.groupId)
        assertEquals("17380203573", domain.adminPhone)
        // 口令没有第二份来源，只能信文件里的值。
        assertEquals("join-pw", domain.joinPassword)
    }

    @Test
    fun `AdminSession 记住当时验的口令，锁定后一并清掉`() {
        val session = AdminSession()
        session.unlock("group_13800138000", "13800138000", "admin-口令")

        assertTrue(session.isUnlocked("group_13800138000"))
        assertEquals("admin-口令", session.rememberedAdminPassword("group_13800138000"))
        assertEquals("13800138000", session.unlockedAdminId("group_13800138000"))

        // 别的群组不共享解锁态。
        assertFalse(session.isUnlocked("group_13900139000"))
        assertNull(session.rememberedAdminPassword("group_13900139000"))

        session.lock()
        assertFalse(session.isUnlocked("group_13800138000"))
        assertNull(session.rememberedAdminPassword("group_13800138000"))
        assertNull(session.unlockedAdminId("group_13800138000"))
    }

    @Test
    fun `未解锁时 requireAdminUnlocked 抛错而不是静默放行`() {
        val session = AdminSession()
        val thrown = runCatching {
            session.requireAdminUnlocked("group_13800138000")
        }.exceptionOrNull()
        assertTrue(thrown is com.guolongli.dinner.core.error.DinnerException.Validation)
    }

    @Test
    fun `空群组 id 或空白口令不能解锁`() {
        val session = AdminSession()
        session.unlock("", "13800138000", "pw")
        session.unlock("group_13800138000", "", "pw")
        session.unlock("group_13800138000", "13800138000", "   ")
        assertFalse(session.isUnlocked("group_13800138000"))
    }

    @Test
    fun `转让管理权只换身份不换口令`() {
        val session = AdminSession()
        session.unlock("group_13800138000", "13800138000", "admin-口令")
        session.adoptAdmin("group_13800138000", "13900139000")

        // 解锁的是新管理员，但验的仍是原来那份口令——
        // 转让不等于拿到口令（AGENTS 里记着这个张力）。
        assertEquals("13900139000", session.unlockedAdminId("group_13800138000"))
        assertEquals("admin-口令", session.rememberedAdminPassword("group_13800138000"))
    }
}
