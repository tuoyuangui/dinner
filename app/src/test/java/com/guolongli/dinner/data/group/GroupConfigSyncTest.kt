package com.guolongli.dinner.data.group

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.guolongli.dinner.data.ProfileStore
import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import com.guolongli.dinner.testing.FakeTokenSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 群组配置的云端同步。
 *
 * 这里用真的 [ProfileStore]（Robolectric 给的文件目录与 SharedPreferences）加一个
 * 假网盘，验证的是三条会真的出事的规则：
 *
 * 1. 本机没改过时（时间戳 0）绝不能写云端——否则第二台设备一装就把管理员的密码顶掉；
 * 2. 云端更新时本机要跟着变，且时间戳一起落盘（不然下一轮又会反向覆盖）；
 * 3. push 必须回读确认：上传「看起来成功、云端还是旧值」时不能当成写成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GroupConfigSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var profile: ProfileStore

    /** 假网盘上的 passwd.json 内容；null 表示云端还没有这个文件 */
    @Volatile
    private var cloudFile: String? = null

    /**
     * 上传成功后是否真的把内容写进「云端」。
     * 置 false 用来模拟「传上去了但云端还是旧值」——回读校验必须发现它。
     */
    @Volatile
    private var acceptUploads = true

    /** 真正落到网盘上的上传次数，用来断言「不该写的时候一次都没写」 */
    @Volatile
    private var uploadCount = 0

    @Before
    fun setUp() {
        profile = ProfileStore(ApplicationProvider.getApplicationContext())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                return when {
                    path.contains("method=list") -> {
                        val file = cloudFile
                        if (file == null) {
                            MockResponse().setBody("""{"errno":-9,"errmsg":"file does not exist"}""")
                        } else {
                            MockResponse().setBody(
                                """{"errno":0,"list":[{"fs_id":42,"path":"/apps/dinner/config/passwd.json",
                                   "server_filename":"passwd.json","size":${file.length},"isdir":0,
                                   "server_mtime":100}]}""",
                            )
                        }
                    }

                    path.contains("method=filemetas") ->
                        MockResponse().setBody("""{"errno":0,"info":[{"fs_id":42,"dlink":"${host()}/dfile?x=1"}]}""")

                    path.contains("/dfile") -> MockResponse().setBody(cloudFile.orEmpty())

                    // 建目录：list 已经回过 -9 了，这里只回成功
                    path.contains("method=precreate") ->
                        MockResponse().setBody("""{"errno":0,"uploadid":"up-dir"}""")

                    path.contains("method=create") -> MockResponse().setBody("""{"errno":0,"fs_id":9}""")

                    path.contains("method=locateupload") ->
                        MockResponse().setBody("""{"error_code":0,"host":"${host()}"}""")

                    path.contains("/rest/2.0/pcs/file?method=upload") -> {
                        uploadCount += 1
                        if (acceptUploads) cloudFile = extractJson(body)
                        MockResponse().setBody("""{"errno":0,"path":"/apps/dinner/config/passwd.json"}""")
                    }

                    else -> MockResponse().setResponseCode(404).setBody("""{"errno":-1,"errmsg":"unexpected"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun host() = server.url("/").toString().trimEnd('/')

    /** multipart 体里那段 JSON 才是要写入的内容 */
    private fun extractJson(body: String): String? {
        val start = body.indexOf('{', body.indexOf("name=\"file\""))
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else null
    }

    private fun sync(): GroupConfigSync = GroupConfigSync(
        profile = profile,
        netdisk = {
            NetdiskRepository(
                auth = FakeTokenSource("tk"),
                api = NetdiskApi(
                    client = OkHttpClient.Builder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .build(),
                    panHost = host(),
                    pcsHost = host(),
                ),
                singleStepMaxSize = 4L * 1024 * 1024,
                retryDelayMs = { 0L },
            )
        },
    )

    /** 旧版本写的是 camelCase 键名，改名之后必须还读得出来，否则老设备会被关在门外 */
    @Test
    fun `本机没改过时不会用默认值覆盖云端，且旧键名照样认得`() = runBlocking {
        cloudFile = """{"version":1,"groupName":"我们的家","joinCode":"AAA-BBB",
                        "adminCode":"secret","updatedAt":1000,"updatedBy":"dad"}"""

        val result = sync().sync()

        assertEquals("AAA-BBB", result?.joinCode)
        assertEquals("secret", profile.adminCode)
        assertEquals(1000L, profile.groupUpdatedAt)
        assertEquals("云端更新时只该读，不该写回去", 0, uploadCount)
    }

    @Test
    fun `本机改过时推到云端，并回读确认`() = runBlocking {
        profile.joinCode = "NEW-CODE"
        profile.groupUpdatedAt = System.currentTimeMillis()

        val ok = sync().push()

        assertTrue("写后回读一致才算成功", ok)
        // 键名是 README 的约定：admin_password / join_password，改名字等于换一套门禁
        val written = JSONObject(cloudFile!!)
        assertEquals("NEW-CODE", written.getString("join_password"))
        assertEquals(profile.adminCode, written.getString("admin_password"))
        assertEquals(profile.groupName, written.getString("group_name"))
    }

    @Test
    fun `云端没变而本机没有改动时什么都不做`() = runBlocking {
        cloudFile = null
        assertNull("云端没有文件、本机也没改过，不该凭空造一份", sync().sync())
        assertNull(cloudFile)
    }

    @Test
    fun `上传没有真正生效时 push 返回失败`() = runBlocking {
        cloudFile = """{"version":1,"groupName":"我们的家","joinCode":"OLD-CODE",
                        "adminCode":"secret","updatedAt":10,"updatedBy":"dad"}"""
        profile.joinCode = "NEW-CODE"
        profile.groupUpdatedAt = System.currentTimeMillis()
        acceptUploads = false // 云端拒不更新：模拟「传了但没写进去」

        assertEquals("回读发现不一致，必须报失败", false, sync().push())
    }

    @Test
    fun `云端的入群密码更新后本机跟着变，且下一轮不再反向覆盖`() = runBlocking {
        cloudFile = """{"schema":1,"group_name":"我们的家","join_password":"MOM-2026",
                        "admin_password":"dinner","updated_at":50,"updated_by":"mom"}"""

        sync().sync()
        assertEquals("MOM-2026", profile.joinCode)

        // 再跑一轮：时间戳已经对齐，不该再往云端写
        val writesBefore = uploadCount
        sync().sync()
        assertEquals("对齐后再同步不该再上传", writesBefore, uploadCount)
    }
}
