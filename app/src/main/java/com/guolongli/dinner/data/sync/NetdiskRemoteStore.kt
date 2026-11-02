package com.guolongli.dinner.data.sync

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskException
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import java.io.File

/**
 * RemoteStore 的网盘实现。所有目录都落在 /apps/dinner/ 下：
 *   records/  一条记录一个 JSON
 *   media/    照片视频
 *
 * 删除要吞掉「文件本来就不在」，否则删一条从没传成功的记录会一直失败重试。
 */
class NetdiskRemoteStore(
    private val repo: NetdiskRepository,
    private val recordDir: String = BaiduConfig.RECORD_DIR,
    private val mediaDir: String = BaiduConfig.MEDIA_DIR,
) : RemoteStore {

    override suspend fun uploadDocument(doc: RecordDocument): String =
        repo.uploadRecord(doc.toJson(), RecordDocument.fileName(doc.id))

    override suspend fun downloadDocument(path: String): String {
        val temp = File.createTempFile("rec-", ".json")
        return try {
            repo.download(path, temp)
            temp.readText()
        } finally {
            temp.delete()
        }
    }

    override suspend fun listDocuments(): List<RemoteRecordRef> =
        repo.listAll(recursive = true)
            .asSequence()
            .filter { !it.isDir }
            .filter { it.path.startsWith("$recordDir/") }
            .filter { RecordDocument.idOf(it.name) != null }
            .map { RemoteRecordRef(it.path, it.name, it.modifiedAt) }
            .toList()

    override suspend fun deleteDocument(path: String) {
        try {
            repo.delete(path)
        } catch (e: NetdiskException) {
            // 31066 文件不存在 / -9 目录不存在 / 12 无权限（父目录已空）
            if (e.errno !in NOT_FOUND) throw e
        }
    }

    override suspend fun uploadMedia(localPath: String, remoteName: String): String {
        // 媒体按记录落到 media/ 下，目录链一次建全（/apps/dinner、media、可能还有年月）
        repo.ensureDirectories(mediaDir)
        return repo.uploadFile(File(localPath), remoteName, mediaDir)
    }

    private companion object {
        val NOT_FOUND = BaiduConfig.NOT_FOUND_ERRNOS
    }
}
