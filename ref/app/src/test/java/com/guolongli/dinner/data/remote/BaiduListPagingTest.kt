package com.guolongli.dinner.data.remote

import com.guolongli.dinner.core.NetdiskPaths
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.api.BaiduMultimediaApi
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
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
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 目录列表翻页的回归测试。
 *
 * 这里守的是一个「云端明明有、这台设备就是取不回来」的坑：
 * 照片按月放在 `meals/yyyy/MM/` 下，两个人一天三顿、一顿最多九张，
 * 一个月轻松过百。早先 list 只取第一页（num=100），第 101 个之后：
 * - 媒体按文件名找不到 fs_id，缩略图永远停在占位；
 * - 新设备扫描兜底扫不到，历史记录少一大截。
 */
class BaiduListPagingTest {

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

    @Test
    fun `目录超过一页时继续翻页直到取完`() = runBlocking {
        server.enqueue(page(from = 0, count = 100))
        server.enqueue(page(from = 100, count = 3))

        val files = dataSource.list("/apps/dinner/meals/2026/10")

        assertEquals(103, files.size)
        assertEquals("第一页的第一个文件", "/apps/dinner/meals/2026/10/f0.jpg", files.first().path)
        assertEquals("第二页的文件也要在全量结果里", "/apps/dinner/meals/2026/10/f102.jpg",
            files.last().path)

        val first = server.takeRequest()
        assertEquals("0", first.requestUrl?.queryParameter("start"))
        val second = server.takeRequest()
        assertEquals("100", second.requestUrl?.queryParameter("start"))
        // 一页一百条就够判断是否到底，传大了反而让「本页不足一页」这个终止条件失效。
        assertEquals("100", second.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun `服务端忽略 start 时不会无限翻页`() = runBlocking {
        // 每次都返回一模一样的前 100 条，模拟服务端没按 start 分页。
        repeat(5) { server.enqueue(page(from = 0, count = 100)) }

        val files = dataSource.list("/apps/dinner/meals/2026/10")

        assertEquals(100, files.size)
        assertEquals("发现没有新条目就该停，不能一直翻", 2, server.requestCount)
    }

    @Test
    fun `目录不存在时返回空列表而不是失败`() = runBlocking {
        server.enqueue(json("""{"errno":-9,"errmsg":"file not exist"}"""))

        val files = dataSource.list("/apps/dinner/data")

        assertEquals(0, files.size)
    }

    /** 造一页数据：path 从 from 开始，共 count 条，保证路径唯一。 */
    private fun page(from: Int, count: Int): MockResponse {
        val entries = (from until from + count).joinToString(",") { index ->
            """{"fs_id":${index + 1},"path":"/apps/dinner/meals/2026/10/f$index.jpg",
                "md5":"md5-$index","size":10,"server_mtime":1,"isdir":0}"""
        }
        return json("""{"errno":0,"errmsg":"succ","list":[$entries],"has_more":0}""")
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

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
