package com.guolongli.dinner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘根目录的规范化规则。
 *
 * 这是红线相关的逻辑：任何越出 /apps/ 的路径都必须被拒绝，
 * 否则用户手填一个路径就能让应用往网盘任意位置写文件。
 */
class NetdiskPathsTest {

    @Test
    fun `默认根目录是 apps 容器下的 dinner`() {
        assertEquals("/apps/dinner", NetdiskPaths.DEFAULT_ROOT)
        assertEquals("/apps/dinner", NetdiskPaths.Default.root)
    }

    @Test
    fun `默认根目录落在应用数据容器之下`() {
        // 百度网盘只允许第三方应用在「我的应用数据」(/apps) 下读写。
        assertTrue(NetdiskPaths.DEFAULT_ROOT.startsWith(NetdiskPaths.APPS_CONTAINER))
        assertEquals("/apps", NetdiskPaths.APPS_CONTAINER)
    }

    @Test
    fun `子路径都由群组目录派生`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")
        assertEquals("/apps/dinner/group_17380203573/config", paths.configDir)
        assertEquals(
            "/apps/dinner/group_17380203573/config/records.json",
            paths.recordsSnapshot,
        )
        assertEquals(
            "/apps/dinner/group_17380203573/config/members.json",
            paths.membersSnapshot,
        )
        assertEquals("/apps/dinner/group_17380203573/avatars", paths.avatarsRoot)
        assertEquals(
            "/apps/dinner/group_17380203573/avatars/abc.jpg",
            paths.avatarOf("abc"),
        )
    }

    @Test
    fun `月份补零`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")
        assertEquals(
            "/apps/dinner/group_17380203573/members/13800138000/meals/2026/03",
            paths.mediaDirOf("13800138000", 2026, 3),
        )
    }

    @Test
    fun `规范化去掉结尾斜杠`() {
        assertEquals(
            "/apps/dinner",
            NetdiskPaths.normalize("/apps/dinner/").getOrThrow(),
        )
    }

    @Test
    fun `规范化补上前导斜杠`() {
        assertEquals(
            "/apps/dinner",
            NetdiskPaths.normalize("apps/dinner").getOrThrow(),
        )
    }

    @Test
    fun `只填一个名字时补成 apps 下的目录`() {
        assertEquals("/apps/dinner", NetdiskPaths.normalize("dinner").getOrThrow())
        assertEquals("/apps/dinner", NetdiskPaths.normalize("  dinner  ").getOrThrow())
    }

    @Test
    fun `规范化去掉首尾空白`() {
        assertEquals(
            "/apps/dinner",
            NetdiskPaths.normalize("  /apps/dinner  ").getOrThrow(),
        )
    }

    @Test
    fun `拒绝 apps 之外的路径`() {
        assertTrue(NetdiskPaths.normalize("/sdcard/dinner").isFailure)
        assertTrue(NetdiskPaths.normalize("/apps").isFailure)
        assertTrue(NetdiskPaths.normalize("/apps/").isFailure)
        // 大小写不同就是另一个目录，网盘上不存在它，必须拒绝而不是悄悄改写。
        assertTrue(NetdiskPaths.normalize("/APPS/dinner").isFailure)
    }

    @Test
    fun `拒绝空路径`() {
        assertTrue(NetdiskPaths.normalize("").isFailure)
        assertTrue(NetdiskPaths.normalize("   ").isFailure)
    }

    @Test
    fun `拒绝包含点点或空格的路径`() {
        assertTrue(NetdiskPaths.normalize("/apps/../etc").isFailure)
        assertTrue(NetdiskPaths.normalize("/apps/APP dinner").isFailure)
    }

    @Test
    fun `没有群组时没有任何可写路径`() {
        // 群组目录是唯一形态：未入组时访问群组级路径必须立刻失败，
        // 而不是静默退回 /apps/dinner 把文件散落在 group_* 外面。
        val paths = NetdiskPaths("/apps/dinner", groupId = null)
        assertTrue(!paths.hasGroup)
        val thrown = runCatching { paths.groupRoot }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(runCatching { paths.recordsSnapshot }.isFailure)
        assertTrue(runCatching { paths.passwdFile }.isFailure)
    }

    @Test
    fun `有群组时所有路径都落在群组目录之下`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_13800138000")
        assertTrue(paths.hasGroup)
        assertEquals("/apps/dinner/group_13800138000", paths.groupRoot)
        assertEquals("/apps/dinner/group_13800138000/config/passwd.json", paths.passwdFile)
        assertEquals(
            "/apps/dinner/group_13800138000/config/records.json",
            paths.recordsSnapshot,
        )
        assertEquals(
            "/apps/dinner/group_13800138000/config/members.json",
            paths.membersSnapshot,
        )
        assertEquals(
            "/apps/dinner/group_13800138000/avatars/13800138000.jpg",
            paths.avatarOf("13800138000"),
        )
    }

    @Test
    fun `媒体目录按人分开`() {
        val grouped = NetdiskPaths("/apps/dinner", groupId = "group_13800138000")
        assertEquals(
            "/apps/dinner/group_13800138000/members/13900139000/meals/2026/10",
            grouped.mediaDirOf("13900139000", 2026, 10),
        )
        // 同一个群组里两个人各自有目录，不会互相覆盖。
        assertEquals(
            "/apps/dinner/group_13800138000/members/13800138000/meals/2026/10",
            grouped.mediaDirOf("13800138000", 2026, 10),
        )
        // 没有群组时同样没有媒体目录可用。
        val legacy = NetdiskPaths("/apps/dinner", groupId = null)
        assertTrue(runCatching { legacy.mediaDirOf("13900139000", 2026, 10) }.isFailure)
    }

    @Test
    fun `所有群组路径都在 group 目录之内`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")
        val groupRoot = paths.groupRoot
        listOf(
            paths.recordsSnapshot,
            paths.membersSnapshot,
            paths.passwdFile,
            paths.avatarsRoot,
            paths.mediaDirOf("17400000000", 2026, 10),
            paths.avatarOf("17400000000"),
        ).forEach { path ->
            assertTrue("$path 必须落在 $groupRoot 之内", path.startsWith("$groupRoot/"))
        }
    }

    @Test
    fun `配置与快照统一放在 config 目录里`() {
        // passwd.json 曾放在群组根下、快照放在 data/ 里，两处分散导致「云端能看见文件但登不上」。
        // 现在三者同处一个目录，改名后的目录就是唯一的配置来源。
        val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")
        val configDir = paths.configDir
        assertEquals("/apps/dinner/group_17380203573/config", configDir)
        assertEquals("$configDir/passwd.json", paths.passwdFile)
        assertEquals("$configDir/records.json", paths.recordsSnapshot)
        assertEquals("$configDir/members.json", paths.membersSnapshot)
        // 群组根下不再有裸放的 passwd.json。
        assertTrue(!paths.passwdFile.startsWith("${paths.groupRoot}/passwd"))
    }

    @Test
    fun `空白群组 id 视同没有群组`() {
        val paths = NetdiskPaths("/apps/dinner", groupId = "  ")
        assertTrue(!paths.hasGroup)
        assertTrue(runCatching { paths.groupRoot }.isFailure)
    }
}
