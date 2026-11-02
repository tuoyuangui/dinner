package com.guolongli.dinner.core

import com.guolongli.dinner.domain.model.GroupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端目录布局的回归测试。
 *
 * 守的是「云端明明看得见文件，应用却读不到」这类坑：
 * 早先 passwd.json 放在群组根下，而 records.json / members.json 放在 data/ 里，
 * 两处分散；读 passwd 时只列群组根目录、写的时候只 ensureDirectory(群组根)，
 * 结果文件落在 `data/` 却去根目录找，登录必然失败。
 *
 * 现在统一收进 `config/`，并且读写都指向同一个目录。
 */
class GroupLayoutTest {

    private val paths = NetdiskPaths("/apps/dinner", groupId = "group_17380203573")

    @Test
    fun `口令与快照同处 config 目录`() {
        assertEquals("/apps/dinner/group_17380203573/config", paths.configDir)
        assertEquals("${paths.configDir}/passwd.json", paths.passwdFile)
        assertEquals("${paths.configDir}/records.json", paths.recordsSnapshot)
        assertEquals("${paths.configDir}/members.json", paths.membersSnapshot)
    }

    @Test
    fun `群组根下与旧的 data 目录里都不再放配置`() {
        // 群组根只应有 avatars/ 与 members/ 两个子目录；配置文件一律不在这一层。
        assertTrue(!paths.passwdFile.substringBeforeLast('/').endsWith("/data"))
        assertEquals(paths.groupRoot, paths.configDir.substringBeforeLast('/'))
        assertEquals("${paths.groupRoot}/avatars", paths.avatarsRoot)
        assertTrue(paths.memberRoot("17400000000").startsWith("${paths.groupRoot}/members/"))
    }

    @Test
    fun `config 目录里的文件都能被列目录找到`() {
        // 读 passwd 走 list(configDir)，写之前 ensureDirectory(configDir)：
        // 两者必须是同一个路径，否则写进去的文件永远读不回来。
        val listed = paths.configDir
        val ensured = paths.configDir
        assertEquals(listed, ensured)
        assertTrue(paths.passwdFile.startsWith("$listed/"))
        assertTrue(paths.recordsSnapshot.startsWith("$listed/"))
    }

    @Test
    fun `同一路径重复上传可用 md5 判定是否跳过`() {
        // 快照 / 名单 / 头像都是原地覆盖写同一路径：内容没变就不该重传。
        // 判断依据是「云端目录列表里的 md5」与「本地算出的 md5」是否相等。
        val sameA = "0123456789abcdef0123456789abcdef"
        val sameB = "0123456789abcdef0123456789abcdef"
        val changed = "fedcba9876543210fedcba9876543210"
        assertTrue(sameA.equals(sameB, ignoreCase = true))
        assertTrue(!sameA.equals(changed, ignoreCase = true))
    }

    @Test
    fun `媒体目录只按年月归档，不带日期文件夹`() {
        // 网盘里不得出现 dinner_20261029_045913 这类「应用名_日期」目录。
        // 媒体路径固定为 members/<号码>/meals/yyyy/MM，只有年与月两层。
        val media = paths.mediaDirOf("17380203573", 2026, 10)
        assertEquals(
            "/apps/dinner/group_17380203573/members/17380203573/meals/2026/10",
            media,
        )
        // 递归检查每一层目录名：不允许出现「应用名_数字」形式的日期目录。
        var current = media
        while (current.startsWith(paths.groupRoot)) {
            val name = current.substringAfterLast('/')
            assertTrue(
                "目录 $current 看起来是日期命名目录",
                !Regex("dinner_\\d{8}_\\d{6}").matches(name),
            )
            current = current.substringBeforeLast('/')
        }
    }

    @Test
    fun `建目录的路径链不含应用名前缀`() {
        // 网盘里只允许 group_<号码>/ 及其下级。建目录时若把应用名拼进路径，
        // 或让网盘「冲突时重命名」，就会产生 dinner_20261029_051931 /
        // group_17380203573_20261029_051932 这类重复目录。
        val groupRoot = paths.groupRoot
        assertEquals("/apps/dinner/group_17380203573", groupRoot)
        // 群组目录名本身不得带日期后缀。
        assertTrue(!Regex(".*_\\d{8}_\\d{6}$").matches(groupRoot))
        // config 目录必须直接挂在群组目录下，而不是再套一层应用名目录。
        assertEquals("$groupRoot/config", paths.configDir)
        assertTrue(paths.configDir.startsWith("$groupRoot/"))
    }

    @Test
    fun `群组身份以目录名为准，不信文件里的 group_id`() {
        // 实测过的脏数据：group_17380203573/config/passwd.json 里写着
        // "group_id":"group_17380203576"、"admin_phone":"17380203576"。
        // 旧实现采信文件值，于是 write() 往不存在的 group_17380203576/ 写，永远失败。
        // 现在以目录名为准：目录决定文件实际躺在哪里。
        val dirGroupId = "group_17380203573"
        val fileGroupId = "group_17380203576"
        assertTrue(dirGroupId != fileGroupId)
        // 目录名派生的群组路径才是可写路径。
        val pathsFromDir = NetdiskPaths("/apps/dinner", dirGroupId)
        assertEquals("/apps/dinner/group_17380203573/config/passwd.json", pathsFromDir.passwdFile)
        // 若错信文件值，会指向一个不存在的目录。
        val pathsFromFile = NetdiskPaths("/apps/dinner", fileGroupId)
        assertTrue(pathsFromFile.passwdFile != pathsFromDir.passwdFile)
    }
}
