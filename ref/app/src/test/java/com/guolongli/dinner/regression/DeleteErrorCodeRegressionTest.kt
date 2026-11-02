package com.guolongli.dinner.regression

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.api.BaiduMultimediaApi
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.remote.dto.BaiduCreateFileResponse
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 回归：`method=delete` 失败时返回的是 `error_code`，不是 `errno`。
 *
 * 背景（失效 token 实测确认）：
 * ```
 * POST /rest/2.0/xpan/file?method=delete
 * -> {"error_code":31024,"error_msg":"app id is empty","request_id":...}
 * ```
 * 注意这里**没有 `errno` 字段**。而 `BaiduCreateFileResponse` 的 `errno`
 * 是带默认值 0 的 Int，缺字段就保持 0——于是：
 *
 * - `BaiduFileDataSource.delete()` 判 `errno != 0` 得 false，认为删除成功；
 * - `SyncRepositoryImpl.uploadRecord` 的 `record.isDeleted` 分支删完就
 *   无条件 `updateSyncState(..., SYNCED)`；
 * - 结论：本机显示「已删除」，云端文件还在，别人打开时间线照样看得见，
 *   而且因为已经标成 SYNCED，这条删除再也不会重传——删除意图彻底丢失。
 *
 * 这正面撞上红线「远端删除失败不得把本地标记成已删除」。
 * 现在统一读 `effectiveErrno`（`error_code` 优先），这里用 MockWebServer 钉住。
 */
class DeleteErrorCodeRegressionTest {

    private lateinit var server: MockWebServer
    private lateinit var dataSource: BaiduFileDataSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val client = OkHttpClient()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/rest/2.0/xpan/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        dataSource = BaiduFileDataSource(
            fileApi = retrofit.create(BaiduFileApi::class.java),
            multimediaApi = retrofit.create(BaiduMultimediaApi::class.java),
            downloadClient = client,
            storageConfig = FakeStorageConfig,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `error_code 失败响应解析出的 effectiveErrno 不是 0`() {
        // 直接用 DTO 钉住解析口径：这是整条链路的源头。
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val response = json.decodeFromString(
            BaiduCreateFileResponse.serializer(),
            """{"error_code":31024,"error_msg":"app id is empty","request_id":1}""",
        )
        assertEquals("errno 缺字段时应保持 0（这是 JSON 的默认值行为）", 0, response.errno)
        assertEquals("effectiveErrno 必须取 error_code", 31024, response.effectiveErrno)
        assertEquals("app id is empty", response.effectiveErrmsg)
        assertFalse("删除失败不得被判成功", response.isSuccess)
    }

    @Test
    fun `删除失败必须抛出而不是静默成功`() = runBlocking {
        server.enqueue(json("""{"error_code":31024,"error_msg":"app id is empty"}"""))

        val thrown = runCatching {
            dataSource.delete("${NetdiskPaths.DEFAULT_ROOT}/e2e/x.jpg")
        }.exceptionOrNull()

        assertTrue("删除失败被当成了成功（红线事故）：$thrown", thrown != null)
        assertTrue("应抛 DinnerException：$thrown", thrown is DinnerException)
    }

    @Test
    fun `文件本就不存在时删除视为已达成目标`() = runBlocking {
        // 31066 = 云端文件或目录不存在。删除幂等，不该报错。
        server.enqueue(json("""{"errno":31066,"errmsg":"file not exist"}"""))

        val thrown = runCatching {
            dataSource.delete("${NetdiskPaths.DEFAULT_ROOT}/e2e/gone.jpg")
        }.exceptionOrNull()

        assertTrue("已不存在的文件不应报错：$thrown", thrown == null)
    }

    @Test
    fun `删除成功时正常返回`() = runBlocking {
        server.enqueue(json("""{"errno":0,"errmsg":"succ","request_id":1}"""))

        val thrown = runCatching {
            dataSource.delete("${NetdiskPaths.DEFAULT_ROOT}/e2e/ok.jpg")
        }.exceptionOrNull()

        assertTrue("正常删除不应报错：$thrown", thrown == null)
    }

    @Test
    fun `删除请求确实打到 delete 接口`() = runBlocking {
        server.enqueue(json("""{"errno":0,"errmsg":"succ"}"""))

        dataSource.delete("${NetdiskPaths.DEFAULT_ROOT}/e2e/path.jpg")

        val request = server.takeRequest()
        assertEquals("delete", request.requestUrl?.queryParameter("method"))
        assertTrue(
            "path 必须带上且落在根目录内",
            request.body.readUtf8().contains("path="),
        )
    }

    /** 只需要 paths() 与根目录，其余配置与本测试无关。 */
    private object FakeStorageConfig : StorageConfigRepository {
        override fun observePaths(): Flow<NetdiskPaths> = flowOf(NetdiskPaths.Default)
        override suspend fun paths(): NetdiskPaths = NetdiskPaths.Default
        override suspend fun setRoot(raw: String): Result<NetdiskPaths> =
            Result.success(NetdiskPaths.Default)
        override suspend fun resetToDefault() = Unit
        override fun observeGroupId(): Flow<String?> = flowOf(null)
        override suspend fun groupId(): String? = null
        override suspend fun setGroupId(id: String?) = Unit
        override fun observeCompressMedia(): Flow<Boolean> = flowOf(false)
        override suspend fun compressMedia(): Boolean = false
        override suspend fun setCompressMedia(enabled: Boolean) = Unit
        override fun observeRetentionDays(): Flow<Int> = flowOf(30)
        override suspend fun retentionDays(): Int = 30
        override suspend fun setRetentionDays(days: Int) = Unit
        override fun observeNetworkRetries(): Flow<Int> =
            flowOf(StorageConfigRepository.DEFAULT_NETWORK_RETRIES)
        override suspend fun networkRetries(): Int =
            StorageConfigRepository.DEFAULT_NETWORK_RETRIES
        override suspend fun setNetworkRetries(times: Int) = Unit
    }
}
