package com.guolongli.dinner.data.group

import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.data.remote.datasource.BaiduFileDataSource
import com.guolongli.dinner.data.session.SessionManager
import com.guolongli.dinner.domain.repository.CredentialStore
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网盘上「一个小 JSON 文本文件」的读写。
 *
 * 单独抽出来不是为了抽象而抽象，是因为它承载了两条必须能被验证的语义：
 *
 * 1. **读**必须区分「文件不存在」与「读不出来」：
 *    返回 null 表示目录里确实没有这个文件；读失败必须抛错，
 *    不能压成 null —— 「网盘里看得见、应用说没有」会让人完全无从下手。
 * 2. **写**必须落地后再回读，由调用方比对内容。这里只负责「传上去」，
 *    回读比对留在 [GroupConfigRepositoryImpl]，因为「什么算写对了」是门禁的语义。
 *
 * 抽成接口还有一个直接好处：门禁的回归用例可以摆布「云端那份到底是什么」，
 * 从而验证「写后回读不一致必须抛错」这条红线，而不必真的去连百度网盘。
 */
interface CloudTextFile {
    /**
     * 读一个文本文件。
     *
     * @return 文件内容；文件不存在时返回 null
     * @throws DinnerException 目录或文件读不下来（与「不存在」区分开）
     */
    suspend fun read(path: String): String?

    /** 覆盖写一个文本文件（先建父目录，再上传）。失败抛错。 */
    suspend fun write(path: String, content: String)

    /** 父目录，供调用方建目录。 */
    fun parentOf(path: String): String
}

@Singleton
class NetdiskTextFile @Inject constructor(
    private val fileDataSource: BaiduFileDataSource,
    private val sessionManager: SessionManager,
    private val credentialStore: CredentialStore,
    private val storageConfig: StorageConfigRepository,
) : CloudTextFile {

    override fun parentOf(path: String): String = path.substringBeforeLast('/')

    override suspend fun read(path: String): String? {
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "需要先完成网盘授权，才能读取云端文件"
            )
        }
        val dir = parentOf(path)
        // 列目录失败是「读不了」，不是「没有」。
        val listing = runCatching { fileDataSource.list(dir) }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
            .getOrElse { error ->
                throw DinnerException.Local("读不到 ${path.substringAfterLast('/')}：${error.message}", error)
            }

        val entry = listing.firstOrNull { it.path == path && !it.isDirectory }
            ?: return null

        return runCatching {
            val meta = fileDataSource.fileMeta(entry.fsId)
                ?: throw DinnerException.Local("取不到文件信息（fs_id=${entry.fsId}）")
            val dlink = meta.dlink?.takeIf { it.isNotBlank() }
                ?: throw DinnerException.Local("取不到下载地址")
            val token = credentialStore.token()?.accessToken
                ?: throw DinnerException.AuthRequired("token 已失效，请重新授权")

            val temp = File.createTempFile("cloud", ".json")
            try {
                fileDataSource.download(dlink, temp, token)
                if (!temp.isFile || temp.length() == 0L) {
                    throw DinnerException.Local("下载到的文件为空")
                }
                temp.readText()
            } finally {
                temp.delete()
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Logger.w("云端文件读取失败（$path）：${error.message}")
            throw DinnerException.Local("读不到 ${path.substringAfterLast('/')}：${error.message}", error)
        }
    }

    override suspend fun write(path: String, content: String) {
        if (!sessionManager.ensureFreshToken()) {
            throw DinnerException.AuthRequired(
                sessionManager.lastAuthError ?: "需要先完成网盘授权，才能写入云端文件"
            )
        }
        val temp = File.createTempFile("cloud", ".json")
        try {
            temp.writeText(content)
            // 先建到父目录这一级：只 ensureDirectory(群组根) 会漏掉子目录，
            // 上传就会打进一个不存在的目录，表现为「网盘里看不见这个文件」。
            fileDataSource.ensureDirectory(parentOf(path))
            fileDataSource.upload(temp, path)
        } finally {
            temp.delete()
        }
    }
}
