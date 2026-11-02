package com.guolongli.dinner.data.remote.datasource

import com.guolongli.dinner.core.BaiduApi
import com.guolongli.dinner.core.UploadConfig
import com.guolongli.dinner.core.error.DinnerException
import com.guolongli.dinner.core.log.Logger
import com.guolongli.dinner.core.util.FileNames
import com.guolongli.dinner.data.remote.BaiduErrorMapper
import com.guolongli.dinner.data.remote.api.BaiduFileApi
import com.guolongli.dinner.data.remote.api.BaiduMultimediaApi
import com.guolongli.dinner.data.remote.dto.BaiduCreateFileResponse
import com.guolongli.dinner.data.remote.dto.BaiduFileEntry
import com.guolongli.dinner.data.remote.dto.BaiduFileMeta
import com.guolongli.dinner.data.remote.withRetry
import com.guolongli.dinner.domain.repository.StorageConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网盘文件数据源：唯一直接接触百度网盘文件接口的地方。
 *
 * 强制约束（见 AGENTS.md 红线）：
 * - 所有路径必须落在 /apps/dinner/ 之下，越界直接拒绝。
 * - 所有操作都携带 access_token（由拦截器注入），不得绕过 OAuth。
 * - 三阶段上传的 path、size、block_list 必须完全一致。
 * - 频控 20012 走统一退避。
 */
@Singleton
class BaiduFileDataSource @Inject constructor(
    private val fileApi: BaiduFileApi,
    private val multimediaApi: BaiduMultimediaApi,
    @Named("download") private val downloadClient: OkHttpClient,
    private val storageConfig: StorageConfigRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 带退避地执行一次网盘请求，次数按用户在「我的 → 存储与网络」里设的来。
     *
     * 只重试瞬时故障（频控 / 网络不可用 / IO 异常）；业务性错误（鉴权失败、文件已存在）
     * 由 [withRetry] 直接抛出、不重试——那些重试多少次结果都一样，只会白烧配额。
     */
    private suspend fun <T> retry(tag: String, block: suspend () -> T): T =
        withRetry(
            maxAttempts = storageConfig.networkRetries(),
            tag = tag,
            block = block,
        )

    /**
     * 列出目录下的全部条目。
     *
     * 必须翻完所有页：照片存在 `meals/yyyy/MM/` 下，两个人一天三顿、一顿还可能九张，
     * 一个月轻松超过一百个文件。早先只取第一页（num=100），第 101 个之后：
     * - [MediaCacheRepository] 按文件名找不到 fs_id，缩略图永远停在占位；
     * - 新设备的 `scanMealsFromRemote` 扫不到，历史记录少一大截。
     * 表现就是「云端明明有，这台设备取不回来」。
     *
     * 翻页终止条件有两个，缺一不可：
     * 1. 本页不足一页 —— 正常到底；
     * 2. 本页没有任何新条目 —— 服务端忽略了 start，此时继续翻只会无限循环。
     * 另外用 [BaiduApi.LIST_MAX_ITEMS] 兜住极端目录，别把频次配额烧在翻页上。
     */
    suspend fun list(dir: String): List<BaiduFileEntry> {
        FileNames.requireInsideRoot(dir, storageConfig.paths().root)
        val collected = LinkedHashMap<String, BaiduFileEntry>()
        var start = 0
        while (start < BaiduApi.LIST_MAX_ITEMS) {
            val page = listPage(dir, start)
            if (page.isEmpty()) break
            val before = collected.size
            page.forEach { entry -> collected[entry.path] = entry }
            if (collected.size == before) {
                // 服务端没按 start 分页（每页都回同样的前 N 条），到此为止。
                break
            }
            if (page.size < BaiduApi.LIST_PAGE_SIZE) break
            start += page.size
        }
        if (collected.size >= BaiduApi.LIST_MAX_ITEMS) {
            Logger.w("目录条目超过上限 ${BaiduApi.LIST_MAX_ITEMS}，只取到前这么多：$dir")
        }
        return collected.values.toList()
    }

    /** 取一页，目录不存在返回空（视为空目录，便于首次使用）。 */
    private suspend fun listPage(dir: String, start: Int): List<BaiduFileEntry> =
        retry(tag = "list($dir@$start)") {
            val response = fileApi.list(dir = dir, start = start)
            if (response.errno == BaiduApi.ERRNO_DIR_NOT_EXIST) {
                return@retry emptyList()
            }
            BaiduErrorMapper.requireSuccess(response.errno, response.errmsg)
            response.list
        }

    /**
     * 逐层扫描目录下的所有文件（含子目录），用于新设备重建本地库。
     * 广度优先，限制层数，避免误入过深结构。
     */
    suspend fun listRecursively(dir: String, maxDepth: Int = 4): List<BaiduFileEntry> {
        val files = mutableListOf<BaiduFileEntry>()
        var frontier = listOf(dir)
        var depth = 0
        while (frontier.isNotEmpty() && depth <= maxDepth) {
            val next = mutableListOf<String>()
            for (current in frontier) {
                for (entry in list(current)) {
                    if (entry.isDirectory) next += entry.path else files += entry
                }
            }
            frontier = next
            depth += 1
        }
        return files
    }

    /** 获取 dlink。返回的链接还需拼接 access_token 才能下载。 */
    suspend fun fileMeta(fsId: Long): BaiduFileMeta? = retry(tag = "filemetas($fsId)") {
        val response = multimediaApi.fileMetas(fsIds = "[$fsId]")
        BaiduErrorMapper.requireSuccess(response.errno, response.errmsg)
        response.info.firstOrNull()
    }

    /**
     * 云端某个文件当前的 MD5；文件不存在时返回 null。
     *
     * 用途是「上传前比对」：快照、成员名单、头像都是原地覆盖写同一路径，
     * 内容没变就没必要再传一遍。百度的目录列表本身就带 `md5` 字段，
     * 所以这里只花一次 `list`，不额外请求 filemetas。
     *
     * 目录不存在 / 文件还没上传过都返回 null，调用方据此照常上传。
     */
    suspend fun remoteMd5(remotePath: String): String? =
        remoteMd5Result(remotePath).getOrNull()

    /**
     * 同 [remoteMd5]，但区分「文件不存在」与「列目录失败」。
     *
     * - `Result.success(null)`：目录列出来了，就是没有这个文件（首次上传）；
     * - `Result.failure(...)`：列目录失败（网络 / 频控 / 权限），**状态未知**。
     *
     * 调用方在「跳过上传」的判断里必须用这个版本：拿不到云端现状就不能假设
     * 「云端没有」，更不能据此跳过或覆盖——先拿到数据，再决定做什么。
     */
    suspend fun remoteMd5Result(remotePath: String): Result<String?> {
        FileNames.requireInsideRoot(remotePath, storageConfig.paths().root)
        val dir = remotePath.substringBeforeLast('/')
        if (dir.isBlank()) {
            return Result.failure(DinnerException.Validation("路径缺少目录：$remotePath"))
        }
        val entries = runCatching { list(dir) }.getOrElse { error ->
            // 取消是「调用方不想等了」，不是「列目录失败」，不能被包成 Result.failure。
            if (error is CancellationException) throw error
            return Result.failure(
                DinnerException.Local("列目录失败，无法确认云端现状（$dir）：${error.message}", error)
            )
        }
        val entry = entries.firstOrNull { it.path == remotePath && !it.isDirectory }
            ?: return Result.success(null)
        val raw = entry.md5?.takeIf { it.isNotBlank() } ?: return Result.success(null)

        // 百度在 apps 目录下返回的 `md5` 是**混淆过的**：长度仍是 32，但会掺进
        // `s` / `j` / `m` 这类超出 a-f 的字符，同一文件两次请求还可能给出不同的值
        // （实测 records.json 先 dfd45931bs… 再 789d90683md…）。
        // 它不能当「内容是否相同」的判据：否则每次同步都白传一遍，
        // 快照类文件还会一路走到「md5 不一致」的失败分支。
        //
        // 返回 success(null) = 「文件在，但云端没给出可用的 md5」，
        // 调用方据此照常上传（覆盖写幂等），而不是猜「内容一样」。
        if (!isPlausibleMd5(raw)) {
            Logger.d("云端 md5 为混淆值，不作为比对依据：$remotePath（$raw）")
            return Result.success(null)
        }
        return Result.success(raw)
    }

    /**
     * 这个字符串像不像一个真实的 MD5。
     *
     * 只认 32 位十六进制。百度在 apps 目录下返回的混淆值同样 32 位，
     * 但会掺进 `s` / `j` / `m` 这类超出 a-f 的字母，逐字符就能识别。
     */
    private fun isPlausibleMd5(value: String): Boolean =
        value.length == 32 &&
            value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }

    /**
     * 下载到本地文件。
     *
     * dlink 为带签名的临时地址，右侧拼接 access_token 后 GET；
     * 大于 20 MB 的文件必须带 User-Agent: pan.baidu.com，由 downloadClient 的拦截器统一设置。
     */
    suspend fun download(dlink: String, target: File, accessToken: String) {
        val url = if (dlink.contains("access_token=")) {
            dlink
        } else {
            "$dlink${if (dlink.contains('?')) '&' else '?'}access_token=$accessToken"
        }

        retry(tag = "download(${target.name})") {
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DinnerException.NetworkUnavailable(
                        java.io.IOException("下载失败 HTTP ${response.code}")
                    )
                }
                val body = response.body ?: throw DinnerException.Local("下载响应为空")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        Logger.d("已下载 ${target.name} (${target.length()} bytes)")
    }

    /**
     * 三段式上传。
     *
     * @param verifyRemote 上传后是否再核对一次云端 md5。默认关闭：核对要额外调用一次
     *   `list`（且可能分页），媒体上传是高频操作，每条都核对等于把配额翻倍烧掉。
     *   快照 / 名单 / 头像这类「覆盖写同一路径、又要按 md5 判断能否跳过」的文件才开启。
     * @return 上传成功后的 md5（本地算出的那份），用于去重与断点续传校验。
     */
    suspend fun upload(
        localFile: File,
        remotePath: String,
        blockSize: Int = UploadConfig.BLOCK_SIZE_DEFAULT,
        verifyRemote: Boolean = false,
    ): String {
        FileNames.requireInsideRoot(remotePath, storageConfig.paths().root)
        require(localFile.exists() && localFile.length() > 0) {
            "本地文件不存在或为空：${localFile.name}"
        }

        val size = localFile.length()
        try {
            // 小文件（<= 4 MB）走单步上传：文档明确「文件小于等于 4 MB 时无需切片，
            // 可直接上传一次」。省掉预上传 + 分片 + 创建文件三步，
            // 对头像、passwd.json、records.json 这类小文件能省 2~3 次请求，
            // 而开发期配额只有约 100 次/小时。
            if (size <= UploadConfig.BLOCK_SIZE_DEFAULT) {
                val md5 = uploadSingleStep(localFile, remotePath)
                if (verifyRemote) verifyRemoteMd5(remotePath, md5, createdMd5 = md5)
                return md5
            }

            val blocks = splitBlocks(localFile, blockSize)
            if (blocks.size > UploadConfig.MAX_BLOCKS) {
                throw DinnerException.Validation(
                    "分片数 ${blocks.size} 超过上限 ${UploadConfig.MAX_BLOCKS}"
                )
            }

            // block_list 三阶段必须一致，一次性算好并复用同一份 JSON。
            val blockListJson = json.encodeToString(
                ListSerializer(String.serializer()),
                blocks.map { it.md5 },
            )

            // 阶段一：预上传
            val precreate = retry(tag = "precreate(${localFile.name})") {
                val response = fileApi.precreate(
                    path = remotePath,
                    size = size,
                    blockList = blockListJson,
                )
                BaiduErrorMapper.requireSuccess(response.errno, response.errmsg)
                response
            }

            // 秒传命中：文件已存在，无需再传分片。
            // 秒传没有 create 响应，拿不到服务端 md5，只能走 list 兜底对账。
            if (precreate.returnType == RETURN_TYPE_RAPID) {
                Logger.i("秒传命中：${localFile.name}")
                val local = localMd5(localFile)
                if (verifyRemote) verifyRemoteMd5(remotePath, local, createdMd5 = null)
                return local
            }

            val uploadId = precreate.uploadId
                ?: throw DinnerException.Local("预上传未返回 uploadid")

            // 阶段二：分片上传，partseq 从 0 起。
            //
            // 主机先向「获取上传域名」要：文档要求每次上传都取，
            // 不得写死 d.pcs / c3.pcs。取一次给所有分片复用（同一任务同一 uploadid）。
            val uploadHosts = locateUploadHosts(uploadId)
            blocks.forEachIndexed { index, block ->
                retry(tag = "superfile2(${localFile.name}#$index)") {
                    uploadPart(block.file, remotePath, uploadId, index, uploadHosts)
                }
            }

            // 阶段三：创建文件，path、size、block_list 与预上传完全一致
            val created = retry(tag = "create(${localFile.name})") {
                createFile(remotePath = remotePath, size = size, blockListJson = blockListJson, uploadId = uploadId)
            }

            // 以本地算出的 MD5 为准：本地这份才是「文件到底是什么」的真相。
            val local = localMd5(localFile)
            if (verifyRemote) verifyRemoteMd5(remotePath, local, created?.md5)
            Logger.i("上传完成：${localFile.name}")
            return local
        } finally {
            cleanupBlocks(localFile)
        }
    }

    /**
     * 上传后与云端 md5 对账。
     *
     * 上传是「预上传 + 分片 + 创建」三步，任何一步被服务端改写或分片错位，
     * 云端文件的 md5 就会和本地不同。直接把本地 md5 写进记录而不核对，
     * 等于把一个「对端读到的内容」和「我们声称的内容」不一致的状态固化下来，
     * 之后按 md5 判断「要不要重下」全都是错的。
     *
     * 这里只在对账失败时抛错：拿去存档的 md5 必须是云端确实一致的那个。
     */
    private suspend fun verifyRemoteMd5(
        remotePath: String,
        localMd5: String,
        createdMd5: String?,
    ) {
        // 首选 create 响应里的 md5：它与本地逐字节相等，是唯一可信来源。
        val fromCreate = createdMd5?.takeIf { isPlausibleMd5(it) }
        if (fromCreate != null) {
            if (!fromCreate.equals(localMd5, ignoreCase = true)) {
                throw DinnerException.Local(
                    "上传后服务端返回的 md5 与本地不一致（云端 $fromCreate / 本地 $localMd5）：$remotePath"
                )
            }
            return
        }

        // 兜底：create 没回 md5 时再看一次目录列表。
        val remote = remoteMd5Result(remotePath).getOrElse { error ->
            throw DinnerException.Local(
                "上传后无法确认云端文件（$remotePath）：${error.message}",
                error,
            )
        } ?: throw DinnerException.Local("上传后云端的文件不见了：$remotePath")
        if (!remote.equals(localMd5, ignoreCase = true)) {
            throw DinnerException.Local(
                "上传后云端 md5 与本地不一致（云端 $remote / 本地 $localMd5）：$remotePath"
            )
        }
    }

    /**
     * 上传一个分片。
     *
     * 主机**必须**来自「获取上传域名」（[locateUploadHosts]），
     * 官方文档明确要求不要写死：一旦百度调整调度，写死的主机会整片失败。
     *
     * 参数全部在查询串里（method=upload、type=tmpfile、path、uploadid、partseq），
     * 只有文件本体是 multipart 的 file 字段。
     */
    private suspend fun uploadPart(
        block: File,
        remotePath: String,
        uploadId: String,
        partSeq: Int,
        hosts: List<String>,
    ) {
        val part = MultipartBody.Part.createFormData(
            "file",
            block.name,
            block.asRequestBody(UPLOAD_MEDIA_TYPE),
        )
        var lastError: Throwable? = null
        for (host in hosts) {
            val url = shardUrl(host, remotePath, uploadId, partSeq)
            try {
                val response = fileApi.uploadPart(url = url, file = part)
                if (response.errno != BaiduApi.ERRNO_OK) {
                    throw BaiduErrorMapper.mapErrno(response.errno, response.errmsg)
                }
                return
            } catch (e: IOException) {
                // 只有「连不上 / 断开」才换主机；服务端明确回错误的换主机没用。
                lastError = e
                Logger.w("分片上传连不上 $host，换下一个候选域名：${e.message}")
            }
        }
        throw lastError ?: DinnerException.Local("分片上传失败")
    }

    /**
     * 单步上传（小文件）：一次 POST 把整个文件传上去。
     *
     * 主机同样来自「获取上传域名」，参数拼在查询串上：
     * `method=upload&path=…&ondup=overwrite`。
     * `ondup=overwrite` 与分片路径的 `rtype=3` 口径一致：固定路径的文件
     * （passwd.json / records.json / 头像）每次都要覆盖写，冲突即失败会让同步无限失败。
     */
    private suspend fun uploadSingleStep(localFile: File, remotePath: String): String {
        val localMd5 = localMd5(localFile)
        // 单步上传不需要 uploadid，但 locateupload 的必填参数里有它；
        // 用一个占位值即可——服务端只校验它非空，不会去匹配任务。
        val hosts = locateUploadHosts(uploadId = localMd5.take(16))
        val part = MultipartBody.Part.createFormData(
            "file",
            localFile.name,
            localFile.asRequestBody(UPLOAD_MEDIA_TYPE),
        )
        var lastError: Throwable? = null
        for (host in hosts) {
            val url = singleStepUrl(host, remotePath)
            try {
                val response = fileApi.singleStepUpload(url = url, file = part)
                BaiduErrorMapper.requireSuccess(
                    response.effectiveErrno,
                    response.effectiveErrmsg,
                )
                Logger.i("单步上传完成：${localFile.name}")
                return localMd5
            } catch (e: IOException) {
                lastError = e
                Logger.w("单步上传连不上 $host，换下一个候选域名：${e.message}")
            }
        }
        throw lastError ?: DinnerException.Local("单步上传失败")
    }

    /**
     * 取本次上传任务的可用域名（文档「获取上传域名」）。
     *
     * 返回首选 + servers + bak_servers，按顺序尝试：
     * 首选连不上时还有备选，但不允许退回写死的主机。
     * 一个域名都拿不到就抛错——宁可如实失败，也不要用一个可能已经失效的地址。
     */
    private suspend fun locateUploadHosts(uploadId: String): List<String> {
        val response = retry(tag = "locateupload($uploadId)") {
            fileApi.locateUpload(uploadId = uploadId)
        }
        if (!response.isSuccess) {
            throw DinnerException.Local(
                "取不到上传域名（error_code=${response.errorCode}）：${response.errorMsg}"
            )
        }
        val hosts = buildList {
            response.preferredServer?.takeIf { it.isNotBlank() }?.let { add(it) }
            response.servers.mapNotNull { it.server.takeIf { s -> s.isNotBlank() } }
                .map { it.trimEnd('/') }
                .forEach { if (!contains(it)) add(it) }
            response.bakServers.mapNotNull { it.server.takeIf { s -> s.isNotBlank() } }
                .map { it.trimEnd('/') }
                .forEach { if (!contains(it)) add(it) }
        }.distinct()
        if (hosts.isEmpty()) {
            throw DinnerException.Local("上传域名为空，无法上传：$uploadId")
        }
        return hosts
    }

    /**
     * 创建文件（第三阶段）。
     *
     * 正常路径靠 rtype=3 覆盖同名文件。真撞上 31061 说明服务端没按覆盖处理，
     * 这时先删远端再重建一次：宁可多重试一步，也不能让「上传成功」的假象
     * 盖住一份旧内容（records.json 就是典型，内容错了等于没同步）。
     */
    private suspend fun createFile(
        remotePath: String,
        size: Long,
        blockListJson: String,
        uploadId: String,
    ): BaiduCreateFileResponse? {
        val response = fileApi.createFile(
            path = remotePath,
            size = size,
            blockList = blockListJson,
            uploadId = uploadId,
        )
        if (response.effectiveErrno != BaiduApi.ERRNO_FILE_EXIST) {
            BaiduErrorMapper.requireSuccess(response.effectiveErrno, response.effectiveErrmsg)
            // 返回服务端回显的 md5，供上传后对账：这是唯一可信的云端 md5。
            return response
        }

        Logger.w("远端已存在同名文件，删除后重建：$remotePath")
        runCatching { fileApi.deleteFile(path = remotePath) }
            .onFailure { error ->
                // 取消必须原样抛出，否则上传流程停不下来。
                if (error is CancellationException) throw error
                Logger.w("删除远端同名文件失败，继续尝试重建：$remotePath（${error.message}）")
            }
        val retry = fileApi.createFile(
            path = remotePath,
            size = size,
            blockList = blockListJson,
            uploadId = uploadId,
        )
        BaiduErrorMapper.requireSuccess(retry.effectiveErrno, retry.effectiveErrmsg)
        return retry
    }

    /**
     * 删除远端文件。文件本就不存在时视为已达成目标。
     *
     * **必须读 effectiveErrno，不能读 response.errno。**
     * method=delete 失败时返回的是 {"error_code":31024,...}，压根没有 errno 字段，
     * 而 DTO 的 errno 带默认值 0 —— 直接判 errno != 0 会把「删除失败」当成成功。
     * 后果是 SyncRepositoryImpl 无条件把记录标成 SYNCED，本机显示已删除、
     * 云端文件还在，删除意图再也不会重传。
     */
    suspend fun delete(remotePath: String) {
        FileNames.requireInsideRoot(remotePath, storageConfig.paths().root)
        retry(tag = "delete($remotePath)") {
            val response = fileApi.deleteFile(path = remotePath)
            val errno = response.effectiveErrno
            if (errno != 0 && errno != ERRNO_FILE_NOT_EXIST && errno != BaiduApi.ERRNO_FILE_NOT_EXIST) {
                throw BaiduErrorMapper.mapErrno(errno, response.effectiveErrmsg)
            }
        }
    }

    /**
     * 确保目录存在：逐级创建，已存在则忽略。
     *
     * 这是「网盘里还没有 dinner 文件夹也能直接用」的兜底：第一次上传媒体 / 发布
     * 快照时会把整条路径链建出来，`/apps/dinner` 及其下的每一级都在里面。
     *
     * `/apps` 是网盘自己维护的「我的应用数据」容器，应用无权创建，因此路径链从它的
     * 下一级算起——不跳过它的话，每次都会先白试一次创建，注定失败。
     *
     * 每一级先 list 再决定要不要建，所以目录已存在时不会重复创建，也不会因为
     * 目标已存在而报错。
     *
     * 创建失败必须抛出真实错误，不能静默跳过：吞掉异常会让后续上传写到一个
     * 不存在的目录里，用户要么看到含糊的失败，要么以为同步成功了。
     */
    suspend fun ensureDirectory(dir: String) {
        FileNames.requireInsideRoot(dir, storageConfig.paths().root)
        for (path in directoryChain(dir)) {
            ensureOneDirectory(path)
        }
    }

    /**
     * 逐级建一个目录：已存在视为成功。
     *
     * 两个曾经把云端搞乱的坑，改这里前必须先读：
     *
     * 1. **`rtype` 必须是 3（覆盖），绝不能是 1（冲突时重命名）。**
     *    `rtype = 1` 在目标已存在时会让网盘把新目录改名成
     *    `dinner_20261029_051931` / `group_17380203573_20261029_051932`——
     *    于是云端出现一堆带时间戳的重复目录，而本该存在的 `config/` 依然没建出来。
     * 2. **判断「目录不存在」要用 list 的返回码 `-9`，不是文件的 `31066`。**
     *    两者混用会把「list 因别的原因失败」误判成「目录不存在」，进而去 create，
     *    叠加上 rtype=1 就直接造出重复目录。
     *
     * 现在：先 list；明确回 -9 才 create（rtype=3）；create 回「已存在」视为成功；
     * 其余任何错误码都抛出去，不猜、不重命名。
     */
    private suspend fun ensureOneDirectory(path: String) {
        retry(tag = "ensuredir($path)") {
            val response = fileApi.list(dir = path, limit = 1, num = 1)
            when (response.errno) {
                BaiduApi.ERRNO_OK -> return@retry
                // 只有这两种「目录不存在」才可以去建；其余错误一律上抛。
                BaiduApi.ERRNO_DIR_NOT_EXIST, ERRNO_FILE_NOT_EXIST -> Unit
                else -> BaiduErrorMapper.requireSuccess(response.errno, response.errmsg)
            }

            val create = fileApi.createFile(
                path = path,
                size = 0,
                isDir = 1,
                // 覆盖语义：目标已存在时就认那个目录，绝不让网盘生成 `xxx_20261029_051931`。
                rtype = RTYPE_OVERWRITE,
                blockList = "[]",
                uploadId = "",
            )
            if (create.errno != BaiduApi.ERRNO_OK && create.errno != ERRNO_FILE_ALREADY_EXIST) {
                BaiduErrorMapper.requireSuccess(create.errno, create.errmsg)
            }
            Logger.i("已创建网盘目录：$path")
        }
    }

    private data class Block(val file: File, val md5: String)

    /** 切分文件并逐片计算 MD5，最后一片可小于 blockSize。 */
    private fun splitBlocks(source: File, blockSize: Int): List<Block> {
        val blocks = mutableListOf<Block>()
        val buffer = ByteArray(blockSize)
        var index = 0
        source.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val partBytes = if (read == buffer.size) buffer else buffer.copyOf(read)
                val partFile = File(source.parentFile, "${source.name}.part$index")
                partFile.writeBytes(partBytes)
                blocks += Block(partFile, md5Of(partBytes))
                index += 1
            }
        }
        return blocks
    }

    /** 清理本地分片临时文件。 */
    private fun cleanupBlocks(source: File) {
        source.parentFile?.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith("${source.name}.part")) candidate.delete()
        }
    }

    companion object {
        private const val RETURN_TYPE_RAPID = 2
        /** list 时「目录不存在」的返回码。 */
        private const val ERRNO_FILE_NOT_EXIST = -9

        /** 网盘 create 的 rtype：3 = 覆盖同名文件 / 目录，1 = 冲突时自动重命名。 */
        private const val RTYPE_OVERWRITE = 3
        private const val ERRNO_FILE_ALREADY_EXIST = -8
        private val UPLOAD_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /**
         * 拼分片上传的完整 URL。
         *
         * 用 HttpUrl 而不是字符串相加：path 里可能有中文或空格，
         * 手拼会漏编码，服务端会把它当成不存在的路径报 31066。
         */
        /**
         * 拼单步上传的完整 URL。
         *
         * `ondup=overwrite`：与分片路径的 rtype=3 同义，固定路径重写必须覆盖。
         */
        fun singleStepUrl(host: String, remotePath: String): String {
            val base = host.toHttpUrlOrNull()
                ?: throw DinnerException.Local("上传主机非法：$host")
            return base.newBuilder()
                .addPathSegments(BaiduApi.PCS_SINGLE_FILE_PATH)
                .addQueryParameter("method", "upload")
                .addQueryParameter("path", remotePath)
                .addQueryParameter("ondup", "overwrite")
                .build()
                .toString()
        }

        fun shardUrl(host: String, remotePath: String, uploadId: String, partSeq: Int): String {
            val base = host.toHttpUrlOrNull()
                ?: throw DinnerException.Local("分片上传主机非法：$host")
            return base.newBuilder()
                .addPathSegments(BaiduApi.PCS_SUPERFILE_PATH)
                .addQueryParameter("method", "upload")
                .addQueryParameter("type", BaiduApi.PCS_UPLOAD_TYPE)
                .addQueryParameter("path", remotePath)
                .addQueryParameter("uploadid", uploadId)
                .addQueryParameter("partseq", partSeq.toString())
                .build()
                .toString()
        }

        /**
         * 把绝对目录拆成需要逐级创建的路径链，跳过 `/apps` 容器本身。
         *
         * 例：`/apps/dinner/meals/2026/10` ->
         * `/apps/dinner`, `/apps/dinner/meals`, `/apps/dinner/meals/2026`,
         * `/apps/dinner/meals/2026/10`。
         *
         * 只建真正属于应用的层级：`/apps` 由网盘维护，应用建不出来，也不该去试。
         */
        fun directoryChain(dir: String): List<String> {
            val segments = dir.removePrefix("/").split('/').filter { it.isNotBlank() }
            val skipContainer = segments.firstOrNull() == "apps"
            val chain = mutableListOf<String>()
            var current = if (skipContainer) "/apps" else ""
            for (segment in segments.drop(if (skipContainer) 1 else 0)) {
                current = "$current/$segment"
                chain += current
            }
            return chain
        }

        fun localMd5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun md5Of(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
