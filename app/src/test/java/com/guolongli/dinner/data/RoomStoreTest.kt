package com.guolongli.dinner.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.guolongli.dinner.data.local.DinnerDatabase
import com.guolongli.dinner.data.local.MediaKind
import com.guolongli.dinner.data.local.OpKind
import com.guolongli.dinner.data.local.SyncState
import com.guolongli.dinner.data.sync.ApplyResult
import com.guolongli.dinner.data.sync.CommentDoc
import com.guolongli.dinner.data.sync.MediaDoc
import com.guolongli.dinner.data.sync.RecordDocument
import com.guolongli.dinner.data.sync.RoomLocalStore
import com.guolongli.dinner.data.sync.SyncEngine
import com.guolongli.dinner.testing.FakeRemoteStore
import com.guolongli.dinner.ui.model.RecordType
import kotlinx.coroutines.flow.first
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

/**
 * 真库测试：Robolectric 起一个内存版 Room，跑的是真正的 SQL 与真正的事务。
 *
 * 这一层之前只有「编译通过」这一个保证——字段写错、NOT NULL 冲突、外键没级联、
 * Flow 查询排序反了，全都要到手机上才发现。这里把它们钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class RoomStoreTest {

    private lateinit var db: DinnerDatabase
    private lateinit var repo: RecordRepository
    private lateinit var store: RoomLocalStore

    /** 固定时钟，断言时间戳时不用跟真实时间赛跑 */
    private var clock = DAY * 20_000 + 9 * 3_600_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DinnerDatabase::class.java,
        ).allowMainThreadQueries().build()

        store = RoomLocalStore(db)
        repo = RecordRepository(
            db = db,
            engine = SyncEngine(store, FakeRemoteStore()),
            user = { LocalUser("me", "我") },
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        testDir.deleteRecursively()
    }

    /** 测试用的临时目录 */
    private val testDir: java.io.File by lazy {
        java.io.File(
            ApplicationProvider.getApplicationContext<Application>().cacheDir,
            "room-store-test",
        ).apply { mkdirs() }
    }

    /**
     * 造一个**真实存在**的文件。
     *
     * 合并远端文档时会检查本地文件是否还在（文件被系统回收后路径会留下），
     * 所以测「保住本地缓存」必须用真文件，虚拟路径测不出这个行为。
     */
    private fun realFile(name: String): java.io.File =
        java.io.File(testDir, name).apply { writeText("fake-image-bytes") }

    // ------------------------------------------------------------------ 写入

    @Test
    fun `新增记录同时落下记录 媒体 与发件箱一条待办`() = runBlocking {
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "番茄牛腩",
            rating = 4,
            media = listOf(
                MediaInput(localPath = "/data/user/0/a.jpg"),
                MediaInput(localPath = "/data/user/0/b.jpg"),
                MediaInput(kind = MediaKind.VIDEO, durationMs = 15_000L),
            ),
        )

        val row = db.recordDao().record(id)!!
        assertEquals("me", row.authorId)
        assertEquals("我", row.authorName)
        assertEquals("DINNER", row.type)
        assertEquals(4, row.rating)
        assertEquals(SyncState.PENDING.name, row.syncState)
        assertFalse(row.deleted)
        assertNull(row.remotePath)

        val media = db.recordDao().mediaOf(id)
        assertEquals(listOf(0, 1, 2), media.map { it.position })
        assertEquals(MediaKind.VIDEO.name, media[2].kind)
        assertEquals(15_000L, media[2].durationMs)
        assertEquals("/data/user/0/a.jpg", media[0].localPath)
        assertFalse(media[0].uploaded)

        val ops = db.outboxDao().pending(10)
        assertEquals(1, ops.size)
        assertEquals(OpKind.UPSERT_RECORD.name, ops.single().kind)
        assertEquals(id, ops.single().targetId)
    }

    @Test
    fun `一条记录最多存九张媒体`() = runBlocking {
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "九宫格",
            rating = 0,
            media = List(12) { MediaInput(localPath = "/data/$it.jpg") },
        )
        assertEquals(RecordRepository.MAX_MEDIA, db.recordDao().mediaOf(id).size)
    }

    @Test
    fun `连续修改同一条只保留一条待办`() = runBlocking {
        val id = repo.addRecord(type = RecordType.LUNCH, note = "食堂", rating = 3)

        clock += 1_000
        repo.addComment(id, "好吃")
        clock += 1_000
        repo.addComment(id, "真的好吃")

        // 三次写操作，云端只需要看到一次最新版本
        assertEquals(1, db.outboxDao().count())
        assertEquals(2, db.recordDao().commentsOf(id).size)
    }

    @Test
    fun `评论挂到记录上 并把父记录重新标成待同步`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "原稿", rating = 3)
        db.recordDao().markSynced(id, SyncState.SYNCED.name, "/apps/dinner/records/rec-$id.json")

        clock += 5_000
        repo.addComment(id, "  少放点盐  ")

        val comments = db.recordDao().commentsOf(id)
        assertEquals(1, comments.size)
        assertEquals("少放点盐", comments.single().text) // 两侧空白被 trim
        assertTrue(comments.single().pendingSync)

        val row = db.recordDao().record(id)!!
        assertEquals(SyncState.PENDING.name, row.syncState)
        assertEquals(clock, row.updatedAt) // updatedAt 前推，否则同步时会被旧版本盖回去
    }

    @Test
    fun `给不存在的记录写评论不会凭空造出一条`() = runBlocking {
        repo.addComment("no-such-record", "幽灵评论")

        assertEquals(0, db.outboxDao().count())
        assertTrue(db.recordDao().commentsOf("no-such-record").isEmpty())
    }

    @Test
    fun `删除是墓碑：行还在 时间线里没了 云端要删`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "待删", rating = 2)

        clock += 1_000
        repo.deleteRecord(id)

        assertTrue(db.recordDao().timelineOnce().isEmpty())
        val row = db.recordDao().record(id)!!
        assertTrue(row.deleted)
        assertEquals(clock, row.updatedAt)

        val op = db.outboxDao().pending(10).single()
        assertEquals(OpKind.DELETE_RECORD.name, op.kind)
    }

    @Test
    fun `删除不存在的记录是空操作`() = runBlocking {
        repo.deleteRecord("nope")
        assertEquals(0, db.outboxDao().count())
    }

    // ------------------------------------------------------------------ 读取

    @Test
    fun `时间线按时间倒序 并且带齐媒体与评论`() = runBlocking {
        val older = repo.addRecord(
            type = RecordType.LUNCH,
            note = "中午",
            rating = 3,
            media = listOf(MediaInput(), MediaInput()),
            createdAt = clock - 8 * 3_600_000L,
        )
        val newer = repo.addRecord(
            type = RecordType.DINNER,
            note = "晚上",
            rating = 5,
            createdAt = clock,
        )
        repo.addComment(older, "也不错")

        val rows = repo.timeline().first()

        assertEquals(listOf(newer, older), rows.map { it.id })
        assertEquals("晚上", rows[0].note)
        assertEquals(2, rows[1].media.size)
        assertEquals(1, rows[1].comments.size)
        assertEquals("我：也不错", rows[1].comments.single().text)
    }

    @Test
    fun `页头统计条数与跨越天数`() = runBlocking {
        repo.addRecord(type = RecordType.DINNER, note = "第一天", rating = 0, createdAt = DAY * 20_000)
        repo.addRecord(type = RecordType.DINNER, note = "第二天", rating = 0, createdAt = DAY * 20_002)

        assertEquals("一共 2 条 · 跨越 2 天", repo.summary().first())
    }

    @Test
    fun `我的页三个数字跟着同步状态变化`() = runBlocking {
        val a = repo.addRecord(type = RecordType.DINNER, note = "A", rating = 0)
        repo.addRecord(type = RecordType.LUNCH, note = "B", rating = 0)

        assertEquals(listOf(2, 2, 0), repo.stats().first().map { it.value })

        db.recordDao().markSynced(a, SyncState.SYNCED.name, "/apps/dinner/records/rec-$a.json")

        assertEquals(listOf(2, 2, 1), repo.stats().first().map { it.value })
        assertEquals(2, repo.timeline().first().size)
    }

    @Test
    fun `三个统计数出自同一份快照 且作者 id 取当前值`() = runBlocking {
        // 「我记的」按当前 user() 的 id 数：绑手机号换了 id 之后，
        // 不能还按老 id 查（那会数出 0），也不能三条独立 COUNT 各查各的
        var currentId = "device-1"
        val dynamicRepo = RecordRepository(
            db = db,
            engine = SyncEngine(store, FakeRemoteStore()),
            user = { LocalUser(currentId, "我") },
            now = { clock },
        )
        dynamicRepo.addRecord(type = RecordType.DINNER, note = "A", rating = 0)
        dynamicRepo.addRecord(type = RecordType.LUNCH, note = "B", rating = 0)

        assertEquals(listOf(2, 2, 0), dynamicRepo.stats().first().map { it.value })

        // 换身份（绑手机号）后「我记的」当场用新 id 数
        currentId = "13800138000"
        assertEquals(listOf(2, 0, 0), dynamicRepo.stats().first().map { it.value })

        // 同一份快照口径：我记的 ≤ 总记录、已同步 ≤ 总记录
        val values = dynamicRepo.stats().first().map { it.value }
        assertTrue(values[1] <= values[0])
        assertTrue(values[2] <= values[0])
    }

    @Test
    fun `改昵称后时间线与评论立刻显示新名字`() = runBlocking {
        var me = LocalUser("me", "旧名字")
        val dynamicRepo = RecordRepository(
            db = db,
            engine = SyncEngine(store, FakeRemoteStore()),
            user = { me },
            now = { clock },
        )
        val id = dynamicRepo.addRecord(type = RecordType.DINNER, note = "A", rating = 0)
        dynamicRepo.addComment(id, "好吃")

        assertEquals("旧名字", dynamicRepo.timeline().first().first().authorName)

        // 改名不动数据库；映射层当场取 user()，新名字立即生效
        me = LocalUser("me", "新名字")
        val row = dynamicRepo.timeline().first().first()
        assertEquals("新名字", row.authorName)
        assertEquals("新", row.authorInitial)
        assertEquals("新名字：好吃", row.comments.single().text)
    }

    @Test
    fun `待传条数就是发件箱行数`() = runBlocking {
        assertEquals(0, repo.pendingCount().first())

        repo.addRecord(type = RecordType.DINNER, note = "A", rating = 0)
        repo.addRecord(type = RecordType.DINNER, note = "B", rating = 0)

        assertEquals(2, repo.pendingCount().first())
    }

    @Test
    fun `日历只取有记录的日期 同一天只算一次`() = runBlocking {
        val day = DAY * 20_000
        repo.addRecord(type = RecordType.DINNER, note = "早", rating = 0, createdAt = day + 1_000)
        repo.addRecord(type = RecordType.DINNER, note = "晚", rating = 0, createdAt = day + 6 * 3_600_000L)
        repo.addRecord(type = RecordType.DINNER, note = "隔天", rating = 0, createdAt = day + DAY)

        assertEquals(listOf(20_000L, 20_001L), db.recordDao().recordedEpochDays())
    }

    @Test
    fun `旧版示例记录会被一次性清掉 真实记录留下`() = runBlocking {
        // 模拟旧版播种：同样的原文 + 类型 + 无附件
        repo.addRecord(type = RecordType.DINNER, note = "今晚的番茄牛腩炖得很到位，饭搭子手艺见长", rating = 4)
        repo.addRecord(type = RecordType.LUNCH, note = "食堂二楼新出的麻辣香锅，值得一试", rating = 3)
        repo.addRecord(type = RecordType.SCENERY, note = "傍晚在江边散步，天边烧起来了", rating = 5, starred = true)
        // 真实记录：不该动
        val real = repo.addRecord(type = RecordType.DINNER, note = "自己做的一顿饭", rating = 5)

        val removed = repo.removeSeedSamples()

        assertEquals(3, removed)
        val left = db.recordDao().timelineOnce()
        assertEquals(1, left.size)
        assertEquals(real, left.first().id)
    }

    // ------------------------------------------------------- 远端合并（真 SQL）

    @Test
    fun `远端更新的版本会覆盖本地 并标记为已同步`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "我写的", rating = 3)
        db.recordDao().markSynced(id, SyncState.SYNCED.name, null)

        clock += 60_000
        val result = store.applyRemote(
            doc(
                id = id,
                note = "老婆改过了",
                authorName = "老婆",
                updatedAt = clock,
                media = listOf(MediaDoc(0, MediaKind.PHOTO.name, "/apps/dinner/media/rec-$id/0.jpg", null)),
                comments = listOf(
                    CommentDoc("c1", "wife", "老婆", "下次多放点辣", clock, deleted = false),
                ),
            ),
        )

        assertEquals(ApplyResult.APPLIED, result)
        val row = db.recordDao().record(id)!!
        assertEquals("老婆改过了", row.note)
        assertEquals("老婆", row.authorName)
        assertEquals(SyncState.SYNCED.name, row.syncState)
        assertEquals("/apps/dinner/records/rec-$id.json", row.remotePath)

        val media = db.recordDao().mediaOf(id).single()
        assertEquals("/apps/dinner/media/rec-$id/0.jpg", media.remotePath)
        assertTrue(media.uploaded)

        val comment = db.recordDao().commentsOf(id).single()
        assertEquals("下次多放点辣", comment.text)
        assertFalse(comment.pendingSync) // 从云端来的，不需要再传
    }

    @Test
    fun `远端路径没变的照片会保住本地已下载的缓存`() = runBlocking {
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "有图",
            rating = 3,
            media = listOf(MediaInput(localPath = "/data/cache/0.jpg")),
        )
        val remoteImage = "/apps/dinner/media/rec-$id/0.jpg"

        // 第一次合并：远端只有路径，本地还没下载
        store.applyRemote(doc(id = id, updatedAt = clock + 1, media = listOf(MediaDoc(0, MediaKind.PHOTO.name, remoteImage, null))))
        assertNull(db.recordDao().mediaOf(id).single().localPath)

        // 用户看过一次图，本地有了缓存。
        // **必须是真实存在的文件**：合并时会检查文件是否还在
        // （存储紧张时文件会被回收，路径却还留在库里），
        // 用虚拟路径的话这个检查会把缓存当成失效的，测不出原本的行为。
        val cached = realFile("cached-0.jpg")
        db.recordDao().upsertMedia(
            db.recordDao().mediaOf(id).map { it.copy(localPath = cached.absolutePath) },
        )

        // 远端又改了文字（照片没换）：本地缓存不该被清掉
        store.applyRemote(
            doc(id = id, note = "改了备注", updatedAt = clock + 2, media = listOf(MediaDoc(0, MediaKind.PHOTO.name, remoteImage, null))),
        )

        val media = db.recordDao().mediaOf(id).single()
        assertEquals(cached.absolutePath, media.localPath)
        assertEquals(remoteImage, media.remotePath)
    }

    @Test
    fun `本地文件已经不在了就不该再当成已缓存`() = runBlocking {
        // 存储空间不足时系统会回收文件，但数据库里的路径还在。
        // 这种「死路径」不能被当成有效缓存——否则界面会以为图还在，
        // 点开却是一片空白，而且永远不会再下载。
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "有图",
            rating = 3,
            media = listOf(MediaInput(localPath = "/data/cache/0.jpg")),
        )
        val remoteImage = "/apps/dinner/media/rec-$id/0.jpg"

        store.applyRemote(doc(id = id, updatedAt = clock + 1, media = listOf(MediaDoc(0, MediaKind.PHOTO.name, remoteImage, null))))

        // 指向一个并不存在的文件
        db.recordDao().upsertMedia(
            db.recordDao().mediaOf(id).map { it.copy(localPath = "/data/cache/已经没了.jpg") },
        )

        store.applyRemote(
            doc(id = id, note = "改了备注", updatedAt = clock + 2, media = listOf(MediaDoc(0, MediaKind.PHOTO.name, remoteImage, null))),
        )

        assertNull("文件已经不在，本地路径就该被丢掉", db.recordDao().mediaOf(id).single().localPath)
    }

    @Test
    fun `本地更新还没传出去时 远端旧版本不覆盖`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "本地新版本", rating = 5)
        clock += 60_000
        repo.addComment(id, "刚加的") // updatedAt 前推 + 仍是 PENDING

        val result = store.applyRemote(
            doc(id = id, note = "远端旧版本", updatedAt = clock - 120_000),
        )

        assertEquals(ApplyResult.STALE, result)
        assertEquals("本地新版本", db.recordDao().record(id)!!.note)
        assertEquals(SyncState.PENDING.name, db.recordDao().record(id)!!.syncState)
    }

    @Test
    fun `两边时间一样且本地干净时什么都不做`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "一致", rating = 3)
        db.recordDao().markSynced(id, SyncState.SYNCED.name, null)

        val result = store.applyRemote(doc(id = id, note = "一致", updatedAt = clock))

        assertEquals(ApplyResult.UNCHANGED, result)
    }

    @Test
    fun `远端删掉的记录落到本地也是墓碑`() = runBlocking {
        val id = repo.addRecord(type = RecordType.DINNER, note = "别人删了", rating = 0)
        db.recordDao().markSynced(id, SyncState.SYNCED.name, null)

        val result = store.applyRemote(doc(id = id, deleted = true, updatedAt = clock + 1))

        assertEquals(ApplyResult.APPLIED, result)
        assertTrue(db.recordDao().record(id)!!.deleted)
        assertTrue(db.recordDao().timelineOnce().isEmpty())
    }

    @Test
    fun `对齐发件箱：同步完成的记录会被标记 失败会留下错误`() = runBlocking {
        val ok = repo.addRecord(type = RecordType.DINNER, note = "能传", rating = 0)
        val bad = repo.addRecord(type = RecordType.DINNER, note = "传不动", rating = 0)

        store.markSynced(ok, "/apps/dinner/records/rec-$ok.json")
        store.markSyncFailed(bad, "网盘写入失败")

        assertEquals(SyncState.SYNCED.name, db.recordDao().record(ok)!!.syncState)
        assertEquals("/apps/dinner/records/rec-$ok.json", db.recordDao().record(ok)!!.remotePath)

        val failed = db.recordDao().record(bad)!!
        assertEquals(SyncState.FAILED.name, failed.syncState)
        assertEquals("网盘写入失败", failed.lastError)
    }

    @Test
    fun `待上传媒体只给出还没传的那些`() = runBlocking {
        val id = repo.addRecord(
            type = RecordType.DINNER,
            note = "两张图",
            rating = 0,
            media = listOf(MediaInput(localPath = "/data/0.jpg"), MediaInput(localPath = "/data/1.jpg")),
        )

        assertEquals(listOf(0, 1), store.mediaToUpload(id).map { it.position })

        store.markMediaUploaded(id, 0, "/apps/dinner/media/rec-$id/0.jpg")

        assertEquals(listOf(1), store.mediaToUpload(id).map { it.position })
        assertEquals(listOf("/apps/dinner/media/rec-$id/0.jpg"), db.recordDao().uploadedMediaPaths(id))
    }

    // ------------------------------------------------------------------ 工具

    private fun doc(
        id: String,
        note: String = "远端",
        authorName: String = "别人",
        updatedAt: Long = clock,
        deleted: Boolean = false,
        media: List<MediaDoc> = emptyList(),
        comments: List<CommentDoc> = emptyList(),
    ) = RecordDocument(
        id = id,
        authorId = "other-device",
        authorName = authorName,
        type = RecordType.DINNER.name,
        note = note,
        rating = 3,
        createdAt = DAY * 20_000,
        updatedAt = updatedAt,
        starred = false,
        deleted = deleted,
        media = media,
        comments = comments,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
