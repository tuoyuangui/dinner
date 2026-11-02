package com.guolongli.dinner.data.media

import com.guolongli.dinner.core.NetdiskPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地媒体缓存的目录结构。
 *
 * 要求：**本地文件组织与云盘保持一致**。这样「哪些已经在本地」是一次文件
 * 存在性检查，而不是逐个算哈希；用户也能对着网盘目录逐个核对。
 *
 * 这里不实例化 Android 依赖，只验证「远端路径 -> 本地相对路径」这段纯映射规则，
 * 用的是与 MediaCacheRepository 相同的字符串推导。
 */
class MediaCacheLayoutTest {

    private val root = NetdiskPaths.DEFAULT_ROOT

    /** 与 MediaCacheRepository.localFile 同一套规则：去根前缀、按段建目录。 */
    private fun relativePathOf(remotePath: String, rootPrefix: String = root): String =
        remotePath.removePrefix(rootPrefix.trimEnd('/') + "/").trimStart('/')

    @Test
    fun `本地相对路径镜像云盘群组结构`() {
        val remote = "$root/group_17380203573/members/13800138000/meals/2026/10/a.jpg"
        assertEquals(
            "group_17380203573/members/13800138000/meals/2026/10/a.jpg",
            relativePathOf(remote),
        )
    }

    @Test
    fun `快照与头像同样镜像`() {
        assertEquals(
            "group_17380203573/config/records.json",
            relativePathOf("$root/group_17380203573/config/records.json"),
        )
        assertEquals(
            "group_17380203573/avatars/13800138000.jpg",
            relativePathOf("$root/group_17380203573/avatars/13800138000.jpg"),
        )
    }

    @Test
    fun `改了根目录之后仍能还原成群组相对路径`() {
        // 自定义根目录下也必须只保留群组那一段，否则本地会多套一层。
        val customRoot = "/apps/other"
        assertEquals(
            "group_17380203573/members/13800138000/meals/2026/10/a.jpg",
            relativePathOf(
                "$customRoot/group_17380203573/members/13800138000/meals/2026/10/a.jpg",
                customRoot,
            ),
        )
    }

    @Test
    fun `同一个群不同月份的路径彼此不同`() {
        // 旧实现用「路径哈希 + 文件名」，这里守的是新结构下也不会互相覆盖。
        val october = relativePathOf("$root/group_1/members/138/meals/2026/10/a.jpg")
        val november = relativePathOf("$root/group_1/members/138/meals/2026/11/a.jpg")
        assertTrue(october != november)
    }

    @Test
    fun `不同成员的同期同名文件不会互相覆盖`() {
        val first = relativePathOf("$root/group_1/members/138/meals/2026/10/a.jpg")
        val second = relativePathOf("$root/group_1/members/139/meals/2026/10/a.jpg")
        assertTrue(first != second)
    }
}
