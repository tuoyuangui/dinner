package com.guolongli.dinner.data.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 头像缓存文件命名。
 *
 * 作者标识本身带连字符（`dev-<uuid>`），分隔符选错就会把 id 截断，
 * 表现为「头像串到别人身上」或「换了头像不刷新」。
 *
 * 只认现行命名 `<id>__v<版本>.jpg`；更早的固定名不是有效缓存。
 */
class AvatarFilesTest {

    @Test
    fun `版本化文件名能被原样解析回来`() {
        val name = AvatarFiles.versionedName("dev-abc-123", 1729500000000L)
        assertEquals("dev-abc-123__v1729500000000.jpg", name)

        val entry = AvatarFiles.parse(File(name))
        assertEquals("dev-abc-123", entry?.id)
        assertEquals(1729500000000L, entry?.version)
    }

    @Test
    fun `旧版本的固定文件名不再是有效缓存`() {
        assertNull(AvatarFiles.parse(File("dev-abc-123.jpg")))
        assertNull(AvatarFiles.parse(File("13800138000.jpg")))
    }

    @Test
    fun `非现行命名一律拒绝`() {
        assertNull(AvatarFiles.parse(File("")))
        assertNull(AvatarFiles.parse(File("__v100.jpg")))
        assertNull(AvatarFiles.parse(File("u1__vabc.jpg")))
        assertNull(AvatarFiles.parse(File("u1__v100.png")))
        assertNull(AvatarFiles.parse(File("u1_v100.jpg")))
    }

    @Test
    fun `同一成员的不同版本按版本号比较`() {
        val older = File(AvatarFiles.versionedName("u1", 100L))
        val newer = File(AvatarFiles.versionedName("u1", 200L))
        assertEquals("u1", AvatarFiles.parse(older)?.id)
        assertEquals("u1", AvatarFiles.parse(newer)?.id)
        assertEquals(
            newer.name,
            listOf(older, newer).maxByOrNull { AvatarFiles.parse(it)?.version ?: 0L }?.name,
        )
    }
}
