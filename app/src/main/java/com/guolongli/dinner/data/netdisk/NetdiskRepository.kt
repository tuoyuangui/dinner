package com.guolongli.dinner.data.netdisk

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.auth.TokenSource
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 网盘上的一条文件记录 */
data class RemoteFile(
    val fsId: Long,
    val path: String,
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val modifiedAt: Long,
    /** 云端哈希。注意：/apps 目录下它**不是**文件真实 MD5，只能当线索用，见 [isPlausibleMd5] */
    val md5: String = "",
)

/**
 * 网盘业务层：把「确保目录 → 上传 → 建文件 → 列表 → 下载 → 删除」这套流程收口。
 * 所有请求都先经 [TokenSource] 取 token，token 过期自动续。
 *
 * 只允许读写 `/apps/dinner/`——这是网盘开放平台的硬约束，越界请求在
 * [requireInsideAppDir] 这一层就被拦下，不必等云端返回 -7。
 *
 * 几条来自真机调试的结论，改动前请先读：
 *
 * - **小文件别走三阶段。** ≤ 4 MB 直接用单步上传，一次请求顶三次。
 *   开发期配额只有约 100 次/小时，头像、记录 JSON 全靠这一条省出来。
 * - **上传域名必须动态取，且要能换。** locateupload 会回一串候选域名
 *   （host → servers → bak_servers），首选连不上时依次退。写死域名在百度换机房后
 *   会让整片上传失败，而现象只是「上传超时」。
 * - **`rtype` 只能是 3。** 1 表示「冲突时重命名」，会把目录建成
 *   `dinner_20261029_051931` 这种带时间戳的副本，而本该存在的目录依然没建出来。
 * - **「目录不存在」只认 -9 / 31066。** 其它错误码一律上抛，不能猜：
 *   把频控误判成「目录不存在」就会去建目录，叠加上面的 rtype 问题就会造重复目录。
 * - **云端 md5 不能直接当校验和。** 文档写明 list 里的 md5 是「云端哈希值，
 *   非文件真实 MD5」，/apps 下还会掺进 s/j/m 这类超出 a-f 的字符。详见 [isPlausibleMd5]。
 */
class NetdiskRepository(
    private val auth: TokenSource,
    private val api: NetdiskApi = NetdiskApi(),
    private val appDir: String = BaiduConfig.APP_DIR,
    /** 分片大小，默认 4 MB；测试里调小才能覆盖多分片路径 */
    private val chunkSize: Int = BaiduConfig.CHUNK_SIZE,
    /**
     * 走单步上传的体积上限。默认 4 MB（文档口径）。
     * 传 0 表示关闭单步上传、一律走三阶段——测试覆盖多分片路径时用得到。
     */
    private val singleStepMaxSize: Long = BaiduConfig.SINGLE_STEP_MAX_SIZE,
    /**
     * 瞬时故障重试次数，与「我的 → 网盘重试」里的设置同源。
     *
     * 每次请求都重新读一次，用户改完立刻生效——抓成构造参数的话，
     * 改设置要重启应用才管用，而这恰恰是用户试「多试几次能不能传上去」的地方。
     */
    private val retryTimes: () -> Int = { BaiduConfig.DEFAULT_RETRY },
    /** 退避间隔；测试里传 { 0 } 免得白等 */
    private val retryDelayMs: (attempt: Int) -> Long = { attempt -> 400L * attempt },
) {

    /** 已经确认存在的目录。一次会话里不必反复 list，省下的都是配额 */
    private val ensuredDirs = ConcurrentHashMap.newKeySet<String>()

    /**
     * 服务端是否拒绝过「单步上传用占位 uploadid」。
     *
     * 一旦被拒过就记下来，后续小文件直接走真实预上传拿 uploadid，
     * 免得每次传文件都先白挨一个错误。只在内存里，重启重探一次即可。
     */
    @Volatile
    private var singleStepNeedsRealUploadId = false

    // ------------------------------------------------------------ 目录

    /** [appDir] 本身 */
    suspend fun ensureAppDir(): Boolean = ensureDir(appDir)

    /**
     * 确保单个目录存在，返回 true 表示这次真的建了。
     *
     * 判断「存不存在」只有一条路：list 这个目录本身。
     * 早先的实现是 list 父目录再找有没有同名项，父目录条目一多就会漏
     * （list 有分页上限），于是每次同步都以为目录不存在、每次都去 create。
     */
    suspend fun ensureDir(dir: String): Boolean {
        requireInsideAppDir(dir)
        if (ensuredDirs.contains(dir)) return false

        val exists = retry {
            val token = auth.token()
            try {
                api.list(token, dir, start = 0, limit = 1)
                true
            } catch (e: NetdiskException) {
                // 只有「不存在」才是该去建的信号；其余错误（频控 / 鉴权 / 无权限）必须抛出去
                if (e.isNotFound()) false else throw e
            }
        }
        if (exists) {
            ensuredDirs += dir
            return false
        }

        createDirectory(dir)
        ensuredDirs += dir
        return true
    }

    /**
     * 建目录失败时最有用的一句话：`/apps` 底下到底有哪些应用目录。
     *
     * 只用于**诊断**，不进同步主链路：
     * - 应用目录名不等于开放平台里登记的应用名时（比如登记成「食伴」而代码按 dinner 找），
     *   云端永远不会有 `/apps/dinner`，光看 errno=2 是猜不出这一点的；
     * - 有些账号/权限下 `/apps` 本身就读不到，那就退回空列表，别把诊断变成新的失败。
     */
    suspend fun listAppContainer(): List<String> = try {
        parseFiles(api.list(auth.token(), "/apps", start = 0, limit = 100)).map { it.name }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    }

    /**
     * 真的在云端建一个目录。
     *
     * 三套参数按顺序试，因为服务端对「建目录」的要求存在版本差异，而失败时只回一个
     * errno=2 / 31355（参数异常），根本看不出缺哪个字段：
     *
     * 1. `create(path, isdir=1, size=0, block_list=[])` —— 文档口径，建目录不预上传；
     * 2. 同上再加 `rtype=3` —— 少数版本要靠 rtype 才肯落盘；
     * 3. `precreate(isdir=1)` + `create(uploadid=…)` —— 老接口口径。
     *
     * 只有「参数类错误」才换下一套；容量不足、鉴权失败、路径越界这类换参数也没用，
     * 直接抛出去。撞上「已存在」（-8 / 31061）说明目标达成，算成功。
     */
    private suspend fun createDirectory(dir: String) {
        var lastParamError: NetdiskException? = null
        for (attempt in 0..2) {
            val token = auth.token()
            try {
                when (attempt) {
                    0 -> api.createDir(token, dir)
                    1 -> api.createDir(token, dir, rtype = 3)
                    else -> {
                        val prepared = api.precreate(token, dir, 0L, true, emptyList())
                        api.createDir(
                            token,
                            dir,
                            uploadId = prepared.optString("uploadid"),
                            rtype = 3,
                        )
                    }
                }
                return
            } catch (e: NetdiskException) {
                // 并发下别人已经建好了 / 上一套其实成功了一半，都算达成目标
                if (e.errno == BaiduConfig.ERRNO_FILE_ALREADY_EXIST ||
                    e.errno == BaiduConfig.ERRNO_FILE_EXIST
                ) {
                    return
                }
                if (e.isAuthError() || !isParamError(e)) throw e
                lastParamError = e
            }
        }
        throw lastParamError ?: NetdiskException(-1, "建目录失败：$dir")
    }

    /** 参数类错误：换一套参数还有救，值得重试一次别的写法 */
    private fun isParamError(e: NetdiskException): Boolean = e.errno in PARAM_ERRNOS

    /**
     * 把 [dir] 及其祖先从「已确认存在」缓存里摘掉。
     *
     * 上传回 31066 / 31064 说明目录在云端其实没了（用户手动删过、或上一次建目录
     * 只成功了一半），这时必须让 ensureDirectories 重新走一遍 list + create，
     * 否则缓存会一直说「存在」，每次同步都在往不存在的目录里传。
     */
    private fun forgetEnsured(dir: String) {
        ensuredDirs.removeIf { it == dir || dir.startsWith("$it/") || it.startsWith("$dir/") }
    }

    /**
     * 把整条目录链都建出来（跳过 `/apps`，那是网盘自己的容器）。
     *
     * 上传前调用一次即可：`/apps/dinner/records`、`/apps/dinner/media/2026/10`
     * 这种多级路径靠它一次建完，不必让每个调用方自己拆。
     */
    suspend fun ensureDirectories(dir: String): Boolean {
        requireInsideAppDir(dir)
        var created = false
        for (path in NetdiskApi.directoryChain(dir)) {
            if (ensureDir(path)) created = true
        }
        return created
    }

    // ------------------------------------------------------------ 上传

    /**
     * 上传一个文件，返回它在云端的绝对路径。
     *
     * 体积 ≤ [singleStepMaxSize] 时走单步上传，否则走三阶段分片。
     */
    suspend fun uploadFile(
        local: File,
        remoteName: String = local.name,
        remoteDir: String = appDir,
    ): String {
        require(local.exists()) { "本地文件不存在：${local.absolutePath}" }
        require(local.length() > 0) { "本地文件是空的：${local.absolutePath}" }
        val remotePath = "$remoteDir/$remoteName"
        requireInsideAppDir(remotePath)
        // 目录由调用方先 ensureDirectories：上传是高频操作，不该每次都多一次 list。
        // 这条约定写在 NetdiskRemoteStore / GroupService 的每个上传入口上。
        try {
            putFile(local, remotePath)
        } catch (e: NetdiskException) {
            // 目录在云端没了（用户手动删过、或上一次建目录只成功了一半）。
            // 这里重建一次再传：不然症状是「同步一直失败，但界面只说文件不存在」，
            // 用户根本不知道要去网盘里把 dinner 文件夹加回来。
            if (!isMissingParent(e)) throw e
            forgetEnsured(remoteDir)
            ensureDirectories(remoteDir)
            putFile(local, remotePath)
        }
        return remotePath
    }

    /** 31066 / 31064 / -9：目标或它所在的目录在云端不存在 */
    private fun isMissingParent(e: NetdiskException): Boolean =
        e.errno == BaiduConfig.ERRNO_FILE_NOT_EXIST ||
            e.errno == BaiduConfig.ERRNO_DIR_NOT_EXIST ||
            e.errno == BaiduConfig.ERRNO_UPLOAD_PATH_WRONG

    /** 上传一段 JSON 文本（一条记录、一份快照） */
    suspend fun uploadRecord(json: String, name: String, dir: String = BaiduConfig.RECORD_DIR): String {
        ensureDirectories(dir)
        val temp = File.createTempFile("record-", ".json")
        temp.writeText(json)
        return try {
            uploadFile(temp, name, dir)
        } finally {
            temp.delete()
        }
    }

    /**
     * 「内容没变就别传」版上传：先比对云端 md5，一致就跳过。
     *
     * 快照 / 名单 / 头像都是原地覆盖写同一个路径，内容没变时重传纯烧配额。
     * 云端的 md5 值不可信时（见 [isPlausibleMd5]）一律照常上传——覆盖写是幂等的，
     * 多传一次的代价远小于「以为一样、其实不一样」。
     *
     * @return true 表示这次真的传了
     */
    suspend fun uploadIfChanged(
        local: File,
        remoteName: String = local.name,
        remoteDir: String = appDir,
    ): Boolean {
        val remotePath = "$remoteDir/$remoteName"
        requireInsideAppDir(remotePath)
        val localMd5 = localMd5(local)
        if (remoteMd5(remotePath) == localMd5) return false
        uploadFile(local, remoteName, remoteDir)
        return true
    }

    /** 云端某个文件的 md5；拿不到（不存在 / 混淆值 / 列目录失败）时返回 null */
    suspend fun remoteMd5(remotePath: String): String? {
        requireInsideAppDir(remotePath)
        val dir = remotePath.substringBeforeLast('/').ifBlank { return null }
        val files = try {
            list(dir)
        } catch (e: Exception) {
            // 列目录失败不等于「云端没有」，但也不能因此跳过上传：返回 null 让调用方照传
            return null
        }
        val raw = files.firstOrNull { it.path == remotePath && !it.isDir }?.md5.orEmpty()
        return raw.takeIf { isPlausibleMd5(it) }
    }

    /** 单步上传：≤ 4 MB 的文件一次 POST 完成，省掉预上传 + 分片 + 合并 */
    private suspend fun uploadSingleStep(local: File, remotePath: String): String {
        val bytes = local.readBytes()
        val localMd5 = md5Of(bytes)
        return retry {
            val token = auth.token()
            // 快路径：占位 uploadid 顶替，省一次预上传。风险是服务端不认——而且拒绝
            // 可能发生在**上传阶段**（locateupload 照样回域名，直到 POST 文件才报
            // 参数错误）。所以快路径整段（取域名 + 上传）都可能触发回退，
            // 只兜 locate 一处是接不住的——现象就是「小文件永远传不上去」。
            if (!singleStepNeedsRealUploadId) {
                try {
                    val quick = api.locateUploadHosts(token, remotePath, localMd5.take(16))
                    if (quick.isNotEmpty()) {
                        return@retry postSmall(quick, token, remotePath, bytes, localMd5)
                    }
                } catch (e: NetdiskException) {
                    // 鉴权 / 瞬时 / 目录丢失 / 无权限 / 容量不足：换 uploadid 也救不了，照抛
                    if (e.isAuthError() || e.isTransient() || isMissingParent(e) ||
                        e.errno == BaiduConfig.ERRNO_NO_UPLOAD_PERMISSION || e.errno == ERRNO_QUOTA_FULL
                    ) {
                        throw e
                    }
                    singleStepNeedsRealUploadId = true
                }
            }
            // 正路径：真实预上传任务的 uploadid，顺带享受秒传（return_type=2）
            val prepared = api.precreate(token, remotePath, bytes.size.toLong(), false, listOf(localMd5))
            if (prepared.optInt("return_type") == 2) return@retry localMd5
            val uploadId = prepared.optString("uploadid")
            if (uploadId.isBlank()) throw NetdiskException(-1, "预上传没有返回 uploadid")
            val hosts = api.locateUploadHosts(token, remotePath, uploadId)
            if (hosts.isEmpty()) throw NetdiskException(-1, "拿不到上传域名")
            postSmall(hosts, token, remotePath, bytes, localMd5)
        }
    }

    /** 把文件依次往候选域名上单步上传；业务错误直接抛（换域名没用），连不上才换下一个 */
    private suspend fun postSmall(
        hosts: List<String>,
        token: String,
        remotePath: String,
        bytes: ByteArray,
        localMd5: String,
    ): String {
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                val json = api.uploadSmall(host, token, remotePath, bytes)
                return json.optString("md5").ifBlank { localMd5 }
            } catch (e: NetdiskException) {
                if (!e.isTransient()) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: NetdiskException(-1, "单步上传失败")
    }

    /**
     * 三阶段分片上传：预上传 → 逐片 superfile2 → create 合并
     */
    private suspend fun uploadMultiPart(local: File, remotePath: String, size: Long): String {
        val slices = sliceMd5(local)
        if (slices.size > BaiduConfig.MAX_BLOCKS) {
            throw NetdiskException(-1, "分片数 ${slices.size} 超过上限 ${BaiduConfig.MAX_BLOCKS}")
        }
        // block_list 三个阶段必须完全一致，算一次复用
        val blockList = slices.map { it.second }

        // 阶段一：预上传
        val prepared = retry {
            api.precreate(auth.token(), remotePath, size, false, blockList)
        }
        // return_type = 2：云端已有同 MD5 的文件，秒传完成
        if (prepared.optInt("return_type") == 2) return localMd5(local)

        val uploadId = prepared.optString("uploadid")
        if (uploadId.isBlank()) throw NetdiskException(-1, "预上传没有返回 uploadid")

        val token = auth.token()
        // 阶段二：分片上传，partseq 从 0 起
        val hosts = retry { api.locateUploadHosts(token, remotePath, uploadId) }
        if (hosts.isEmpty()) throw NetdiskException(-1, "拿不到上传域名")

        // 文档（分片上传注意事项）：**只上传预上传响应 block_list 指定的分片**——
        // 它是「需要上传的分片序号列表」，续传场景下服务端可能已经持有部分分片，
        // 全量重传既烧配额也可能因分片状态不一致报 31363。
        val needSeqs = parseNeededSeqs(prepared, slices.size)

        val blockMd5s = ArrayList<String>(slices.size)
        for (index in slices.indices) {
            if (index !in needSeqs) {
                // 服务端已有的分片：MD5 用本地算的（文档口径「返回的 md5 应与预上传请求
                // block_list 中对应值一致」，且 create 的 block_list 要与预上传完全一致）
                blockMd5s += slices[index].second
                continue
            }
            val bytes = readSlice(local, slices[index].first, chunkSize)
            blockMd5s += retry { uploadPart(hosts, token, remotePath, uploadId, index, bytes, slices[index].second) }
        }

        // 阶段三：创建文件
        retry { createFileWithFallback(token, remotePath, size, blockMd5s, uploadId, isDir = false) }
        return localMd5(local)
    }

    /**
     * 预上传响应里的「需要上传的分片序号」。
     *
     * 文档口径是整数序号数组（`block_list: [0, 2]`）；万一服务端回的是对象数组
     * （`[{"partseq":0}]`）也认。字段缺失或解析不出有效序号时退回「全都要传」，
     * 与旧行为一致，宁可多传也不能漏传。
     */
    private fun parseNeededSeqs(prepared: JSONObject, total: Int): Set<Int> {
        val array = prepared.optJSONArray("block_list") ?: return (0 until total).toSet()
        val seqs = LinkedHashSet<Int>()
        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue
            val seq = when (item) {
                is Number -> item.toInt()
                is JSONObject -> item.optInt("partseq", item.optInt("index", -1))
                else -> -1
            }
            if (seq in 0 until total) seqs += seq
        }
        return if (seqs.isEmpty()) (0 until total).toSet() else seqs
    }

    /**
     * 传一个分片。
     *
     * 只有「连不上 / 被断开」才换下一个候选域名；服务端明确回错误的换域名没用。
     */
    private suspend fun uploadPart(
        hosts: List<String>,
        token: String,
        remotePath: String,
        uploadId: String,
        partSeq: Int,
        bytes: ByteArray,
        fallbackMd5: String,
    ): String {
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                val json = api.uploadSlice(host, token, remotePath, uploadId, partSeq, bytes)
                // 分片成功时百度回 md5，合并阶段要用它；没回就退回本地算的值
                return json.optString("md5").ifBlank { fallbackMd5 }
            } catch (e: NetdiskException) {
                if (!e.isTransient()) throw e
                lastError = e
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: NetdiskException(-1, "分片 $partSeq 上传失败")
    }

    /**
     * 创建文件（目录同理）。撞上「已存在」时先删远端再建一次。
     *
     * `rtype=3` 本该覆盖同名文件，但真机上仍会遇到 31061 / -8。
     * 这时如果直接当成功，云端留着的是一份旧内容——记录快照错了等于没同步，
     * 而且下一次还会因为「md5 一致」跳过上传，永远修不回来。
     */
    private suspend fun createFileWithFallback(
        token: String,
        path: String,
        size: Long,
        blockList: List<String>,
        uploadId: String,
        isDir: Boolean,
    ) {
        try {
            api.create(token, path, size, isDir, blockList, uploadId)
        } catch (e: NetdiskException) {
            if (e.errno != BaiduConfig.ERRNO_FILE_EXIST &&
                e.errno != BaiduConfig.ERRNO_FILE_ALREADY_EXIST
            ) {
                throw e
            }
            runCatching { api.delete(token, listOf(path)) }
            api.create(token, path, size, isDir, blockList, uploadId)
        }
    }

    private suspend fun putFile(local: File, remotePath: String): String {
        val size = local.length()
        if (singleStepMaxSize > 0 && size <= singleStepMaxSize) {
            return uploadSingleStep(local, remotePath)
        }
        return uploadMultiPart(local, remotePath, size)
    }

    // ------------------------------------------------------------ 读取

    /**
     * 列一个目录（不递归），自动翻完所有页。
     *
     * 翻页必须翻完：照片存在 `media/yyyy/MM/` 下，一家人一天三顿、一顿九张，
     * 一个月轻松过百。只取第一页的直接后果是「云端明明有，这台设备取不回来」。
     *
     * 两个终止条件缺一不可：本页不足一页（正常到底）、本页没有新条目
     * （服务端忽略了 start，再翻就是死循环）。
     */
    suspend fun list(dir: String): List<RemoteFile> {
        requireInsideAppDir(dir)
        val collected = LinkedHashMap<String, RemoteFile>()
        var start = 0
        while (start < BaiduConfig.LIST_MAX_ITEMS) {
            val page = try {
                retry {
                    parseFiles(api.list(auth.token(), dir, start = start, limit = BaiduConfig.LIST_PAGE_SIZE))
                }
            } catch (e: NetdiskException) {
                // 目录还不存在 = 空目录，别让调用方为此失败
                if (e.isNotFound()) return emptyList()
                throw e
            }
            if (page.isEmpty()) break
            val before = collected.size
            page.forEach { entry -> collected[entry.path] = entry }
            if (collected.size == before) break
            if (page.size < BaiduConfig.LIST_PAGE_SIZE) break
            start += page.size
        }
        return collected.values.toList()
    }

    /**
     * 递归列出 [appDir] 下所有文件。
     *
     * 用 `multimedia?method=listall`，按文档的 `has_more` + `cursor` 翻页——
     * 注意下一页的起点是响应里的 cursor，不是 `start + limit`。
     */
    suspend fun listAll(recursive: Boolean = true): List<RemoteFile> = retry {
        val token = auth.token()
        val collected = LinkedHashMap<String, RemoteFile>()
        var start = 0
        var pages = 0
        while (pages < MAX_LIST_ALL_PAGES) {
            pages += 1
            val json = try {
                api.listAll(token, appDir, recursion = recursive, start = start, limit = LIST_ALL_PAGE_SIZE)
            } catch (e: NetdiskException) {
                // 云端还没有 /apps/dinner = 一个文件都没有，不该让整次同步失败
                if (e.isNotFound()) return@retry emptyList()
                throw e
            }
            parseFiles(json).forEach { entry -> collected[entry.path] = entry }
            if (json.optInt("has_more") != 1) break
            val cursor = json.optInt("cursor", -1)
            if (cursor <= start) break
            start = cursor
        }
        collected.values.toList()
    }

    /** 下载一个远端文件到本地 */
    suspend fun download(remotePath: String, target: File): File {
        requireInsideAppDir(remotePath)
        val dir = remotePath.substringBeforeLast('/').ifBlank { appDir }
        val entry = list(dir).firstOrNull { it.path == remotePath && !it.isDir }
            ?: throw NetdiskException(BaiduConfig.ERRNO_FILE_NOT_EXIST, "云端没有这个文件：$remotePath")
        return download(entry, target)
    }

    /**
     * 已经有 [RemoteFile] 时用它下载：省掉一次 re-list。
     *
     * 云端整理一轮要读好几份墓碑，`download(path)` 每次都重新列一遍目录，
     * 三倍配额换一个已经知道的 fs_id，不划算。
     */
    suspend fun download(entry: RemoteFile, target: File): File = retry {
        requireInsideAppDir(entry.path)
        val token = auth.token()
        val meta = api.fileMetas(token, listOf(entry.fsId), dlink = true)
        // 文档（查询文件信息）的 fsids 批量响应放在 `list` 数组里；老接口 / 个别版本
        // 用 `info` 数组，两种都认，避免「换了个接口版本就下不动」。
        val arr = meta.optJSONArray("list") ?: meta.optJSONArray("info")
        val objs = arr?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
        // 对号入座：先按 fs_id 严格匹配；批量条目混着别的文件时绝不能顺手拿错 dlink
        //——那会把 A 文件下成 B 文件，比下载失败更难发现。只有单条目时才退而求其次。
        val matched = objs.firstOrNull { it.optLong("fs_id") == entry.fsId } ?: objs.singleOrNull()
        val dlink = matched?.optString("dlink").orEmpty().trim()
        if (dlink.isBlank()) {
            // 文档口径「批量查询时先判断顶层 errno，再按 list 条目处理结果」：
            // 条目自己的 errno 比「拿不到下载地址」有用得多，42214 这类还可重试
            val entryErrno = matched?.optInt("errno", 0) ?: 0
            if (entryErrno != 0) {
                throw NetdiskException(entryErrno, matched?.optString("errmsg").orEmpty())
            }
            // 注意：这里用 IllegalStateException 而不是 NetdiskException(-1)。
            // 后者被 retry 当成「瞬时故障」会无限重试 filemetas，白白烧配额——拿不到
            // dlink 是稳定故障（filemetas 压根没返回），重试多少次都一样。
            throw IllegalStateException("拿不到下载地址（filemetas 未返回 dlink）")
        }
        api.download(dlink, token, target)
        target
    }

    /**
     * 删除云端文件。「本来就不在」视为已达成目标。
     *
     * 删除失败时百度回的是 `error_code` 而不是 `errno`，由 [NetdiskApi.effectiveErrno]
     * 统一识别——这一点错了会把失败当成功，本机显示已删除、云端文件还在。
     */
    suspend fun delete(remotePath: String) {
        requireInsideAppDir(remotePath)
        retry {
            val token = auth.token()
            try {
                api.delete(token, listOf(remotePath))
            } catch (e: NetdiskException) {
                if (!e.isNotFound()) throw e
            }
        }
    }

    /** 容量：总数 / 已用 / 剩余（字节） */
    suspend fun quota(): Triple<Long, Long, Long> = retry {
        val json = api.quota(auth.token())
        Triple(json.optLong("total"), json.optLong("used"), json.optLong("free"))
    }

    // ------------------------------------------------------------ 内部

    /** 红线：任何读写都必须落在 `/apps/dinner/` 之下 */
    private fun requireInsideAppDir(path: String) {
        if (path == appDir || path.startsWith("$appDir/")) return
        throw NetdiskException(-7, "只能读写 $appDir 之下的路径：$path")
    }

    private fun parseFiles(json: JSONObject): List<RemoteFile> {
        val array = json.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val path = item.optString("path")
                add(
                    RemoteFile(
                        fsId = item.optLong("fs_id"),
                        path = path,
                        name = item.optString("server_filename").ifBlank { path.substringAfterLast('/') },
                        size = item.optLong("size"),
                        isDir = item.optInt("isdir") == 1,
                        modifiedAt = item.optLong("server_mtime"),
                        md5 = item.optString("md5"),
                    ),
                )
            }
        }
    }

    /**
     * 这个字符串像不像一个真实的 MD5。
     *
     * 文档对 list 的 md5 字段写得很清楚：「云端哈希值，**非文件真实 MD5**」。
     * 实测 /apps 下返回的混淆值长度仍是 32，但会掺进 `s` / `j` / `m` 这类超出 a-f 的字符，
     * 同一个文件两次请求还可能给出不同的值。拿它当「内容是否相同」的判据，
     * 结果是每次同步都白传一遍，或者一路走进「md5 不一致」的失败分支。
     */
    private fun isPlausibleMd5(value: String): Boolean =
        value.length == 32 && value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }

    /** 频控 / 断网重试；鉴权错误与业务错误直接抛，不浪费次数 */
    private suspend fun <T> retry(
        times: Int = retryTimes().coerceAtLeast(1),
        block: suspend () -> T,
    ): T {
        var lastError: Exception? = null
        for (attempt in 0 until times) {
            try {
                return block()
            } catch (e: NetdiskException) {
                lastError = e
                if (e.isAuthError() || !e.isTransient()) throw e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < times - 1) delay(retryDelayMs(attempt + 1))
        }
        throw lastError ?: NetdiskException(-1, "请求失败")
    }

    /** 每个分片的起始偏移与 MD5（空文件按一个空分片算） */
    private fun sliceMd5(file: File): List<Pair<Long, String>> {
        val result = ArrayList<Pair<Long, String>>()
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(chunkSize)
        FileInputStream(file).use { input ->
            var offset = 0L
            while (true) {
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read == 0) break
                digest.reset()
                digest.update(buffer, 0, read)
                result += offset to digest.digest().toHex()
                offset += read
                if (read < buffer.size) break
            }
        }
        if (result.isEmpty()) {
            digest.reset()
            result += 0L to digest.digest().toHex()
        }
        return result
    }

    private fun readSlice(file: File, offset: Long, length: Int): ByteArray {
        val size = minOf(length.toLong(), file.length() - offset).toInt().coerceAtLeast(0)
        val bytes = ByteArray(size)
        FileInputStream(file).use { input ->
            input.channel.position(offset)
            var read = 0
            while (read < size) {
                val count = input.read(bytes, read, size - read)
                if (count < 0) break
                read += count
            }
        }
        return bytes
    }

    private fun localMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
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

    private companion object {
        /** listall 单页条数：文档默认 1000，上限 10000 */
        const val LIST_ALL_PAGE_SIZE = 1000

        /** 递归列举最多翻这么多页，防止异常目录把配额烧光 */
        const val MAX_LIST_ALL_PAGES = 12

        /** 网盘容量已满（create / precreate 的 -10）：换参数、换 uploadid 都没用 */
        const val ERRNO_QUOTA_FULL = -10

        /**
         * 「参数类错误」：换一套参数写法还有救。
         *
         * 2 是服务端最常见的参数错误码（建目录缺 uploadid、locateupload 收到假 uploadid
         * 都回它）；-1 是本地兜底的「参数错误」语义；31355 是 create 的「参数异常」。
         */
        val PARAM_ERRNOS = setOf(2, -1, 31355)
    }
}
