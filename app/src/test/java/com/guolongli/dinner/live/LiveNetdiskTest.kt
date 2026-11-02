package com.guolongli.dinner.live

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 真网盘的接口联通性测试：容量 / 建目录 / 上传 / 列表 / 下载 / 删除，
 * 以及「多分片二进制上传」这条最容易被文档细节坑到的链路。
 *
 * 全部落在 /apps/dinner/ 下，用的都是 `-live-` 前缀的临时文件，跑完自己删干净。
 * 没授权（build/live-token.json 不存在）时整类跳过。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveNetdiskTest {

    private fun repo(chunk: Int = BaiduConfig.CHUNK_SIZE): NetdiskRepository = NetdiskRepository(
        auth = FileTokenSource(),
        api = NetdiskApi(),
        chunkSize = chunk,
        retryTimes = { 2 },
        retryDelayMs = { 800L },
    )

    private fun requireToken() {
        assumeTrue("还没有 token，先跑 DeviceCodeAuthTest 或放一份 build/live-token.json", LiveEnv.hasToken())
    }

    private fun stamp() = System.currentTimeMillis()

    @Test
    fun `01 容量与应用目录`() = runBlocking {
        requireToken()
        val repository = repo()

        val (total, used, free) = repository.quota()
        println("[live] AppID=${LiveEnv.appId} 应用名=${BaiduConfig.APP_NAME}")
        println("[live] 容量 total=${gb(total)} used=${gb(used)} free=${gb(free)}")
        assertTrue("容量信息不合理", total > 0)

        val created = repository.ensureAppDir()
        println("[live] ${BaiduConfig.APP_DIR} ${if (created) "本次新建" else "已存在"}")
        val createdRecordDir = repository.ensureDir(BaiduConfig.RECORD_DIR)
        println("[live] ${BaiduConfig.RECORD_DIR} ${if (createdRecordDir) "本次新建" else "已存在"}")
        repository.ensureDir(BaiduConfig.MEDIA_DIR)

        val again = repository.ensureAppDir()
        assertTrue("目录已存在时不该重复创建", !again)
    }

    @Test
    fun `02 记录文档上传 列表 下载 删除`() = runBlocking {
        requireToken()
        val repository = repo()
        val id = "live-${stamp()}"
        val body = """
            {
              "schema": 1,
              "id": "$id",
              "authorName": "联调",
              "note": "真网盘读写链路测试",
              "rating": 5,
              "updatedAt": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        val name = "rec-$id.json"

        val path = repository.uploadRecord(body, name)
        println("[live] 已上传 $path")
        assertEquals("${BaiduConfig.RECORD_DIR}/$name", path)

        val files = repository.listAll()
        val uploaded = files.firstOrNull { it.path == path }
        assertNotNull("列表里应该有刚上传的文件", uploaded)
        println("[live] 列表命中：${uploaded!!.name} ${uploaded.size} 字节 mtime=${uploaded.modifiedAt}")

        val target = File("build/live-download/$name").apply { parentFile?.mkdirs() }
        repository.download(path, target)
        val downloaded = target.readText()
        println("[live] 已下载 ${downloaded.length} 字节")
        assertEquals("下载内容要和上传的一模一样", body, downloaded)
        target.delete()

        repository.delete(path)
        println("[live] 已删除 $path")
        val after = repository.listAll()
        assertNull("删掉之后列表里不该再有它", after.firstOrNull { it.path == path })
    }

    @Test
    fun `03 多分片二进制上传并校验 md5`() = runBlocking {
        requireToken()
        // 分片调小到 512KB，1.2MB 的文件正好走 3 片：预上传 → 3 次 superfile2 → create
        val repository = repo(chunk = 512 * 1024)
        val id = "live-bin-${stamp()}"
        val name = "rec-$id.bin"
        val bytes = Random(System.currentTimeMillis()).nextBytes(1_200_000)
        val local = File("build/live-upload/$name").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val localMd5 = local.md5()
        println("[live] 本地上传 ${local.length()} 字节 md5=$localMd5")

        val path = repository.uploadFile(local, name, BaiduConfig.RECORD_DIR)
        println("[live] 多分片上传完成 $path")

        val target = File("build/live-download/$name").apply { parentFile?.mkdirs() }
        repository.download(path, target)
        val remoteMd5 = target.md5()
        println("[live] 下载回来 ${target.length()} 字节 md5=$remoteMd5")
        assertEquals("分片合并后的内容必须和本地一致", localMd5, remoteMd5)
        assertTrue(bytes.contentEquals(target.readBytes()))

        repository.delete(path)
        local.delete()
        target.delete()
        println("[live] 清理完成")
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gb(bytes: Long) = "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
