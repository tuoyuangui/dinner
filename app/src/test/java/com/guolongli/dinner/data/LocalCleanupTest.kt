package com.guolongli.dinner.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.local.MediaKind
import com.guolongli.dinner.data.media.MediaCache
import com.guolongli.dinner.data.sync.RoomLocalStore
import com.guolongli.dinner.data.sync.SyncEngine
import com.guolongli.dinner.testing.FakeRemoteStore
import com.guolongli.dinner.ui.model.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 本地保留策略（按天数清理本机媒体）。
 *
 * 这里守的是一条**数据安全红线**：
 * 只有「云端确认已有副本」的媒体才允许删本机文件。
 * 还没上传成功的照片是这位用户唯一的一份，删了就真没了——
 * 之前的实现是「按记录时间到点就删」，会静默吃掉没传上去的媒体，
 * 所以把它钉成测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class LocalCleanupTest {

    private lateinit var db: DinnerDatabase
    private lateinit var repo: RecordRepository
    private lateinit var cache: MediaCache

    private val clock = 100L * DAY

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, DinnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = MediaCache(context)
        repo = RecordRepository(
            db = db,
            engine = SyncEngine(RoomLocalStore(db), FakeRemoteStore()),
            user = { LocalUser("13800138000", "我") },
            mediaCache = cache,
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** 在媒体目录里造一个真文件，模拟相册导入的结果 */
    private fun realFile(name: String): String {
        val file = cache.newFile(name, "jpg")
        file.writeBytes(ByteArray(64) { 1 })
        return file.absolutePath
    }

    @Test
    fun `已上传的旧媒体会被清理，本地路径也清空`() = runBlocking {
        val uploaded = realFile("old-uploaded")
        val id = repo.addRecord(
            type = RecordType.LUNCH,
            note = "旧的一餐",
            rating = 4,
            media = listOf(MediaInput(uploaded, MediaKind.PHOTO)),
            createdAt = clock - 90 * DAY,
        )
        db.recordDao().markMediaUploaded(id, 0, "/apps/dinner/media/old.jpg")

        val removed = repo.cleanupLocalMedia(retentionDays = 30)

        assertEquals(1, removed)
        assertFalse("文件应该被删掉", File(uploaded).exists())
        assertNull(
            "数据库里的死路径必须一起清掉",
            db.recordDao().mediaOf(id).single().localPath,
        )
        // 云端路径还在，记录本身也还在
        assertEquals("/apps/dinner/media/old.jpg", db.recordDao().mediaOf(id).single().remotePath)
    }

    @Test
    fun `没上传成功的旧媒体绝不能被删`() = runBlocking {
        val pending = realFile("old-pending")
        val id = repo.addRecord(
            type = RecordType.LUNCH,
            note = "还没传上去",
            rating = 3,
            media = listOf(MediaInput(pending, MediaKind.PHOTO)),
            createdAt = clock - 90 * DAY,
        )

        val removed = repo.cleanupLocalMedia(retentionDays = 30)

        assertEquals("这是一份唯一的数据，不能算作清理成果", 0, removed)
        assertTrue("文件必须还在", File(pending).exists())
        assertEquals(pending, db.recordDao().mediaOf(id).single().localPath)
    }

    @Test
    fun `保留期内的媒体不动`() = runBlocking {
        val fresh = realFile("fresh")
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "昨天的",
            rating = 5,
            media = listOf(MediaInput(fresh, MediaKind.PHOTO)),
            createdAt = clock - 2 * DAY,
        )
        db.recordDao().markMediaUploaded(id, 0, "/apps/dinner/media/fresh.jpg")

        val removed = repo.cleanupLocalMedia(retentionDays = 30)

        assertEquals(0, removed)
        assertTrue(File(fresh).exists())
    }

    @Test
    fun `永久保留等于不清理`() = runBlocking {
        val old = realFile("forever")
        val id = repo.addRecord(
            type = RecordType.SNACK,
            note = "很久以前",
            rating = 2,
            media = listOf(MediaInput(old, MediaKind.PHOTO)),
            createdAt = clock - 999 * DAY,
        )
        db.recordDao().markMediaUploaded(id, 0, "/apps/dinner/media/forever.jpg")

        assertEquals(0, repo.cleanupLocalMedia(retentionDays = 0))
        assertTrue(File(old).exists())
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
