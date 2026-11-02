package com.guolongli.dinner.regression

import com.guolongli.dinner.data.mapper.normalizeTags
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.domain.model.MealRecord
import com.guolongli.dinner.domain.model.MediaItem
import com.guolongli.dinner.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 3.0.0 修复项回归：本地媒体文件的可用性判据。
 *
 * 背景：多处直接用 `localPath` 就当「本机有图」用。本地保留策略清掉文件之后，
 * `localPath` 仍有值，界面于是加载一个不存在的路径——显示成坏图，
 * 而不是去补下载。现在统一走 [MealRecord.existingFile]。
 */
class LocalMediaUsableRegressionTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `空路径与空白路径都不可用`() {
        assertNull(MealRecord.existingFile(null))
        assertNull(MealRecord.existingFile(""))
        assertNull(MealRecord.existingFile("   "))
    }

    @Test
    fun `文件不存在时不可用`() {
        assertNull(MealRecord.existingFile("${folder.root.absolutePath}/nope.jpg"))
    }

    @Test
    fun `目录不是可用文件`() {
        val dir = folder.newFolder("sub")
        assertNull(MealRecord.existingFile(dir.absolutePath))
    }

    @Test
    fun `空文件按不可用处理`() {
        // 断网留下的半截文件：存在且长度为 0，不能当成有效缓存
        val empty = File(folder.root, "half.jpg")
        assertTrue(empty.createNewFile())
        assertNull(MealRecord.existingFile(empty.absolutePath))
    }

    @Test
    fun `真实非空文件可用`() {
        val file = File(folder.root, "ok.jpg")
        file.writeBytes(byteArrayOf(0x1, 0x2, 0x3))
        assertEquals(file.absolutePath, MealRecord.existingFile(file.absolutePath)?.absolutePath)
    }

    @Test
    fun `媒体段按同一判据给出本机可用性`() {
        val file = File(folder.root, "v.mp4")
        file.writeBytes(byteArrayOf(0x1))
        val media = MediaItem(
            id = "m1",
            mediaType = MediaType.VIDEO,
            localPath = file.absolutePath,
            remotePath = "/apps/dinner/group_x/members/u1/meals/2026/10/v.mp4",
        )
        assertTrue(media.hasLocalFile)
        assertTrue(media.isUploaded)

        // 清理之后：路径还在，但文件没了 —— 必须判成不可用，交给补下载
        assertTrue(file.delete())
        assertFalse(media.hasLocalFile)
        assertTrue(media.isUploaded) // 云端副本不受本地清理影响
    }
}

/**
 * 3.0.0 修复项回归：上传文件名的唯一性。
 *
 * 背景：一条记录最多 9 段媒体，而它们共用同一个记录时间，秒级精度区分不开。
 * 上传走 rtype=3 覆盖写，撞名等于无声盖掉先传上去的那段，
 * 所以同秒的第二段起要加 `_2`、`_3` 后缀。
 */
class FileNameUniquenessRegressionTest {

    @Test
    fun `同一秒的多段媒体名字互不相同`() {
        val millis = 1_763_000_000_000L
        val names = (1..9).map { seq -> FileNames.withDedupSuffix(millis, seq, "jpg") }
        assertEquals(9, names.toSet().size)
    }

    @Test
    fun `不同秒不需要后缀`() {
        // 秒不同的话基准名本身就不冲突，不需要走到去重后缀那条路
        val a = FileNames.timestampedMedia(1_763_000_000_000L, "jpg")
        val b = FileNames.timestampedMedia(1_763_000_001_000L, "jpg")
        assertTrue(a != b)
        // 两边都应当就是基准名（没加去重后缀）
        assertEquals(a, FileNames.withDedupSuffix(1_763_000_000_000L, 1, "jpg"))
        assertEquals(b, FileNames.withDedupSuffix(1_763_000_001_000L, 1, "jpg"))
    }

    @Test
    fun `生成的名字能被日期反解认回来`() {
        // 取回时靠文件名反推日期，名字改了格式而解析器没跟上会让记录永远取不回来
        repeat(9) { n ->
            val name = FileNames.withDedupSuffix(1_763_000_000_000L, n + 1, "jpg")
            assertTrue("名字 $name 应能被反解", FileNames.dayMillisOf(name) != null)
        }
    }
}

/**
 * 3.0.0 修复项回归：标签的写入侧把关。
 *
 * 背景：标签的条数与长度过去只在编码侧静默截断，写入侧不把关，
 * 于是「界面上显示的值」与「落库后回读的值」可能不一致。
 * 现在写入侧（EditViewModel）与编码侧共用 [normalizeTags] 这一套规则。
 */
class TagNormalizationRegressionTest {

    @Test
    fun `去掉首尾空白并丢弃空标签`() {
        assertEquals(
            listOf("家常", "探店"),
            normalizeTags(listOf("  家常 ", "", "   ", "探店")),
        )
    }

    @Test
    fun `单条标签最长 16 字`() {
        val long = "a".repeat(40)
        assertEquals(16, normalizeTags(listOf(long)).first().length)
    }

    @Test
    fun `最多保留 6 条`() {
        assertEquals(6, normalizeTags((1..20).map { "标签$it" }).size)
    }

    @Test
    fun `重复标签只留一条`() {
        assertEquals(listOf("家常"), normalizeTags(listOf("家常", "家常", "家常")))
        // 归一化之后再判定重复：空白差异不该产生两条
        assertEquals(listOf("家常"), normalizeTags(listOf("家常", " 家常 ")))
    }

    @Test
    fun `截断之后产生的重复同样只留一条`() {
        // 两条 20 字标签前 16 字相同，截断后应合并成一条
        val a = "a".repeat(16) + "XXXX"
        val b = "a".repeat(16) + "YYYY"
        assertEquals(1, normalizeTags(listOf(a, b)).size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(normalizeTags(emptyList()).isEmpty())
        assertTrue(normalizeTags(listOf("", "  ")).isEmpty())
    }
}
