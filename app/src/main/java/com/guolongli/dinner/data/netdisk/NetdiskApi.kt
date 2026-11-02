package com.guolongli.dinner.data.netdisk

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.auth.urlEncoded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 网盘接口的业务错误：errno 非 0 时抛出。
 *
 * [message] 优先用 [BaiduConfig.FRIENDLY_ERRNOS] 里给人看的那句话，
 * 没有对应词条才退回百度原文——原始 errmsg 多半是英文或空字符串，
 * 直接怼到界面上用户看不懂。
 */
class NetdiskException(val errno: Int, message: String) : Exception(
    BaiduConfig.FRIENDLY_ERRNOS[errno] ?: message.ifBlank { "网盘接口返回错误 $errno" },
) {

    /** 频控、断网这类瞬时故障，重试有意义 */
    fun isTransient(): Boolean = errno in BaiduConfig.TRANSIENT_ERRNOS

    /** 鉴权类错误，需要重新授权 */
    fun isAuthError(): Boolean = errno in BaiduConfig.AUTH_ERRNOS

    /** 目标本来就不存在：删除 / 下载遇到它应当当成功 */
    fun isNotFound(): Boolean = errno in BaiduConfig.NOT_FOUND_ERRNOS
}

/**
 * 百度网盘开放平台的裸接口层，逐个对应 ref 里的文档：
 * 预上传 precreate / 分片上传 superfile2 / 创建文件 create / 单步上传 upload
 * 上传域名 locateupload / 列表 list / 递归列表 listall / 文件信息 filemetas
 * 容量 quota / 删除 delete
 *
 * 三处最容易踩的协议细节，改这里之前先读一遍：
 *
 * 1. **两台主机**。precreate / create / list 在 `pan.baidu.com/rest/2.0/xpan/file`，
 *    分片上传却在 `pcs/file|superfile2`，且必须带 `type=tmpfile`。
 *    打到 xpan/file?method=superfile2 一律失败。
 * 2. **上传域名不许写死**。文档原文要求「每次上传都调 locateupload，不要长期缓存或固定
 *    某个上传域名」。百度换机房后写死的域名会让整片上传失败，而现象只是「上传超时」。
 * 3. **delete 的错误码字段是 `error_code` 不是 `errno`**。只读 errno 会把失败当成功，
 *    于是本机显示已删除、云端文件还在，删除意图再也不会重传。
 *
 * 约定：method 与 access_token 走 query，其余参数走 form（与文档一致）。
 */
class NetdiskApi(
    private val client: OkHttpClient = defaultClient(),
    /** 正式环境是 https://pan.baidu.com；单元测试指到本地 MockWebServer */
    private val panHost: String = PAN_HOST,
    /** 分片上传 / 获取上传域名的入口主机；实际上传主机由 locateupload 动态返回 */
    private val pcsHost: String = PCS_HOST,
) {

    companion object {
        const val PAN_HOST = "https://pan.baidu.com"
        const val PCS_HOST = "https://d.pcs.baidu.com"
        const val USER_AGENT = "pan.baidu.com"
        const val APP_ID = "250528"

        /** 分片上传接口路径，主机由 locateupload 决定 */
        const val PCS_SUPERFILE_PATH = "rest/2.0/pcs/superfile2"

        /** 单步上传接口路径（小文件用，免掉预上传 + 分片两步） */
        const val PCS_SINGLE_FILE_PATH = "rest/2.0/pcs/file"

        /** 分片上传的固定值，百度要求原样传 */
        const val PCS_UPLOAD_TYPE = "tmpfile"

        /** 下载最多跟几跳 302；dlink 正常一跳就到 CDN，超过说明链路有问题 */
        const val MAX_DOWNLOAD_REDIRECTS = 5

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

        /**
         * 一个响应里真正的错误码。
         *
         * 网盘的失败响应有两种写法：`errno` 与 `error_code`。**必须先看 errno，
         * 但也要接受它缺席**——delete 失败时返回的是 `{"error_code":31024}`，
         * 压根没有 errno 字段；而 JSONObject.optInt 对缺席字段给的是 0，
         * 直接判 `errno != 0` 就会把「删除失败」当成成功。
         */
        fun effectiveErrno(json: JSONObject): Int {
            if (json.has("errno") && !json.isNull("errno")) return json.optInt("errno", 0)
            if (json.has("error_code") && !json.isNull("error_code")) return json.optInt("error_code", 0)
            return BaiduConfig.ERRNO_OK
        }

        /** 同上，取错误文案 */
        fun effectiveErrmsg(json: JSONObject): String =
            json.optString("errmsg").ifBlank {
                json.optString("error_msg").ifBlank {
                    json.optString("error_description")
                }
            }

        /**
         * 拼分片上传的完整 URL。
         *
         * 用 HttpUrl 而不是字符串相加：path 里可能有中文或空格，
         * 手拼会漏编码，服务端会把它当成不存在的路径报 31066。
         */
        fun shardUrl(host: String, remotePath: String, uploadId: String, partSeq: Int, token: String): String {
            val base = host.toHttpUrlOrNull()
                ?: throw NetdiskException(-1, "上传主机非法：$host")
            return base.newBuilder()
                .addPathSegments(PCS_SUPERFILE_PATH)
                .addQueryParameter("method", "upload")
                .addQueryParameter("type", PCS_UPLOAD_TYPE)
                .addQueryParameter("access_token", token)
                .addQueryParameter("path", remotePath)
                .addQueryParameter("uploadid", uploadId)
                .addQueryParameter("partseq", partSeq.toString())
                .build()
                .toString()
        }

        /**
         * 拼单步上传的完整 URL。
         *
         * `ondup=overwrite`：与分片路径的 `rtype=3` 同义。固定路径的文件
         * （头像、记录 JSON）每次都要覆盖写，冲突即失败会让同步无限失败。
         */
        fun singleStepUrl(host: String, remotePath: String, token: String): String {
            val base = host.toHttpUrlOrNull()
                ?: throw NetdiskException(-1, "上传主机非法：$host")
            return base.newBuilder()
                .addPathSegments(PCS_SINGLE_FILE_PATH)
                .addQueryParameter("method", "upload")
                .addQueryParameter("access_token", token)
                .addQueryParameter("path", remotePath)
                .addQueryParameter("ondup", "overwrite")
                .build()
                .toString()
        }

        /**
         * 把绝对目录拆成需要逐级创建的路径链，跳过 `/apps` 容器本身。
         *
         * 例：`/apps/dinner/media/2026/10` →
         * `/apps/dinner`, `/apps/dinner/media`, `/apps/dinner/media/2026`,
         * `/apps/dinner/media/2026/10`。
         *
         * `/apps` 是网盘自己维护的「我的应用数据」容器，应用无权创建；
         * 不跳过它的话每次都会先白试一次注定失败的创建。
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
    }

    // ---------------------------------------------------------------- 上传

    /** 预上传：告知大小与各分片 MD5，拿到 uploadid 与需要上传的分片清单 */
    suspend fun precreate(
        token: String,
        path: String,
        size: Long,
        isDir: Boolean,
        blockList: List<String>,
        rtype: Int = 3,
    ): JSONObject {
        val form = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("isdir", if (isDir) "1" else "0")
            .add("autoinit", "1")
            .add("rtype", rtype.toString())
            .add("block_list", JSONArray(blockList).toString())
            .build()
        return post("/rest/2.0/xpan/file?method=precreate&access_token=" + token.urlEncoded(), form)
    }

    /** 分片上传：把第 partseq 片塞到上传域名上 */
    suspend fun uploadSlice(
        host: String,
        token: String,
        path: String,
        uploadId: String,
        partSeq: Int,
        data: ByteArray,
    ): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "blob", data.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(shardUrl(host, path, uploadId, partSeq, token))
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return parse(execute(request))
    }

    /**
     * 创建目录：**直接 create，不走预上传**。
     *
     * 文档（创建文件）原话：「创建目录时也使用本接口，但无需执行预上传和分片上传」。
     * 早先照文件那套先 `precreate(isdir=1, size=0, block_list=[])` 再 create，
     * 服务端对这种「空上传任务 + 目录」的组合回 errno=2（参数错误）——
     * 现象就是新账号第一次同步时上传全失败，而日志里只有一句「网盘接口返回错误 2」，
     * 完全看不出是建目录这一步挂了。
     *
     * 三个参数都做了「可省略」处理，交给调用方按顺序试：
     * - [uploadId] 传 null 表示不带该字段（官方建目录示例就没有它）；
     * - [rtype] 传 null 表示使用默认策略（冲突即失败 → 回 -8，调用方当「已存在」处理，
     *   这样绝不会因为覆盖语义造出 `dinner_20261029_051931` 那种重复目录）。
     */
    suspend fun createDir(
        token: String,
        path: String,
        uploadId: String? = null,
        rtype: Int? = null,
    ): JSONObject {
        val builder = FormBody.Builder()
            .add("path", path)
            .add("size", "0")
            .add("isdir", "1")
            .add("block_list", "[]")
        if (rtype != null) builder.add("rtype", rtype.toString())
        if (uploadId != null) builder.add("uploadid", uploadId)
        return post("/rest/2.0/xpan/file?method=create&access_token=" + token.urlEncoded(), builder.build())
    }

    /** 创建文件 / 目录：合并分片，或建一个目录 */
    suspend fun create(
        token: String,
        path: String,
        size: Long,
        isDir: Boolean,
        blockList: List<String>,
        uploadId: String,
        rtype: Int = 3,
    ): JSONObject {
        val form = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("isdir", if (isDir) "1" else "0")
            .add("rtype", rtype.toString())
            .add("uploadid", uploadId)
            .add("block_list", JSONArray(blockList).toString())
            .build()
        return post("/rest/2.0/xpan/file?method=create&access_token=" + token.urlEncoded(), form)
    }

    /** 单步上传：≤ 4 MB 的文件一次搞定，不需要预上传 */
    suspend fun uploadSmall(
        host: String,
        token: String,
        path: String,
        data: ByteArray,
    ): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "blob", data.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(singleStepUrl(host, path, token))
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return parse(execute(request))
    }

    /**
     * 获取上传域名，返回**按优先级排好的一串候选**。
     *
     * 顺序：首选域名 → servers → bak_servers。首选连不上时还有备选，
     * 但**不允许退回写死的主机**——百度调整调度后，写死的域名会整片失败，
     * 而现象只是「上传超时」，非常难查。
     *
     * 一次都拿不到就返回空列表，由调用方如实报错：宁可失败，也不要拿一个
     * 可能已经失效的地址去传。
     */
    suspend fun locateUploadHosts(token: String, path: String, uploadId: String): List<String> {
        val url = "$pcsHost/rest/2.0/pcs/file?method=locateupload&appid=$APP_ID" +
            "&access_token=" + token.urlEncoded() +
            "&path=" + path.urlEncoded() +
            "&uploadid=" + uploadId.urlEncoded() +
            "&upload_version=2.0"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val json = parse(execute(request))

        fun normalize(host: String?): String? {
            val value = host?.trim().orEmpty().trimEnd('/')
            if (value.isBlank()) return null
            return if (value.startsWith("http")) value else "https://$value"
        }

        val hosts = LinkedHashSet<String>()
        // 文档里的字段叫 host（推荐主机），老版本实现里叫 server，两个都认
        normalize(json.optString("host")).let { if (it != null) hosts += it }
        normalize(json.optString("server")).let { if (it != null) hosts += it }
        for (key in listOf("servers", "bak_servers")) {
            val array = json.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                normalize(item.optString("server")).let { if (it != null) hosts += it }
            }
        }
        return hosts.toList()
    }

    /** 只要一个域名的旧入口，保留给不需要 failover 的场景 */
    suspend fun locateUploadHost(token: String, path: String, uploadId: String): String? =
        locateUploadHosts(token, path, uploadId).firstOrNull()

    // ---------------------------------------------------------------- 读取

    /**
     * 目录列表（不递归）。
     *
     * 单页默认 100 条：这个值同时是 [com.guolongli.dinner.data.netdisk.NetdiskRepository]
     * 的翻页步长，两边必须一致，否则「本页不足一页」的终止条件会提前收工。
     */
    suspend fun list(
        token: String,
        dir: String,
        start: Int = 0,
        limit: Int = BaiduConfig.LIST_PAGE_SIZE,
    ): JSONObject {
        val url = "$panHost/rest/2.0/xpan/file?method=list" +
            "&access_token=" + token.urlEncoded() +
            "&dir=" + dir.urlEncoded() +
            "&start=$start&limit=$limit&order=time&desc=1"
        return get(url)
    }

    /** 递归列表：一次把 /apps/dinner 下所有文件拿全 */
    suspend fun listAll(
        token: String,
        path: String,
        recursion: Boolean = true,
        start: Int = 0,
        limit: Int = BaiduConfig.LIST_MAX_ITEMS,
    ): JSONObject {
        val url = "$panHost/rest/2.0/xpan/multimedia?method=listall" +
            "&access_token=" + token.urlEncoded() +
            "&path=" + path.urlEncoded() +
            "&recursion=" + (if (recursion) 1 else 0) +
            "&start=$start&limit=$limit&order=time&desc=1"
        return get(url)
    }

    /**
     * 文件信息：dlink=true 时顺带拿到下载地址。
     *
     * 文档口径（查询文件信息）：**按 fsid 批量查询必须用 `xpan/multimedia?method=filemetas`**，
     * 路径批量（target）才走 `xpan/file?method=filemetas`。两者返回结构也不同——
     * 这里只走 fsids 批量，所以固定打到 multimedia。早年误打到 xpan/file 会让整条下载
     * 请求 404 / 返回空，现象就是「同步回来了却怎么都打不开照片」。
     */
    suspend fun fileMetas(token: String, fsIds: List<Long>, dlink: Boolean = true): JSONObject {
        val ids = "[" + fsIds.joinToString(",") + "]"
        val url = "$panHost/rest/2.0/xpan/multimedia?method=filemetas" +
            "&access_token=" + token.urlEncoded() +
            "&fsids=" + ids.urlEncoded() +
            "&dlink=" + if (dlink) 1 else 0
        return get(url)
    }

    /** 容量信息 */
    suspend fun quota(token: String): JSONObject {
        val url = "$panHost/api/quota?access_token=" + token.urlEncoded() + "&checkfree=1&checkexpire=1"
        return get(url)
    }

    /**
     * 删除：filelist 是绝对路径的 JSON 数组。
     *
     * 注意失败响应走的是 `error_code`，由 [parse] → [effectiveErrno] 统一识别。
     */
    suspend fun delete(token: String, paths: List<String>): JSONObject {
        val form = FormBody.Builder()
            .add("async", "0")
            .add("filelist", JSONArray(paths).toString())
            .build()
        return post("/rest/2.0/xpan/file?method=delete&access_token=" + token.urlEncoded(), form)
    }

    /**
     * 下载：dlink 后面拼 access_token，且必须带 pan.baidu.com 的 UA，否则会 403。
     *
     * 两个文档明确要求的细节，缺一个都会「下载莫名其妙失败」：
     *
     * 1. **302 跳转要跟，而且每一跳都要保留 access_token**。dlink 常先 302 到 CDN，
     *    Location 里未必带鉴权参数，丢了就 401/403。OkHttp 自动跟随时用的是 Location
     *    原文、token 会丢，所以这里关掉自动跟随、手动逐跳补 token。
     * 2. **失败响应是 JSON 不是文件**。文档口径「下载失败且返回 JSON 时」带 errno——
     *    不识别的话，一份 `{"errno":31360,…}` 会被原样写进目标文件，用户拿到一个
     *    打不开的“照片”，而同步却显示成功。
     *
     * 注意：JSON **正文**（比如记录快照 .json）不是错误——只有带 errno / error_code
     * 字段的 JSON 才是错误响应，判据必须落在字段上而不是 Content-Type 上。
     */
    suspend fun download(dlink: String, token: String, target: File) {
        withContext(Dispatchers.IO) {
            var url = withAccessToken(dlink, token)
            var hops = 0
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                noRedirectClient().newCall(request).execute().use { response ->
                    if (response.code in 301..308 && response.code != 304) {
                        val location = response.header("Location")
                            ?: throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "下载重定向缺少 Location")
                        val resolved = response.request.url.resolve(location)?.toString() ?: location
                        url = withAccessToken(resolved, token)
                        hops += 1
                        if (hops > MAX_DOWNLOAD_REDIRECTS) {
                            throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "下载重定向次数过多")
                        }
                        return@use // 继续 while，跟下一跳
                    }
                    if (!response.isSuccessful) {
                        // 5xx 是服务端抖动，用网络码交给上层退避重试；4xx 是稳定故障
                        val errno = if (response.code >= 500) BaiduConfig.ERRNO_NETWORK else response.code
                        throw NetdiskException(errno, "下载失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "下载失败：响应为空")
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("json", ignoreCase = true)) {
                        // 带 errno 的 JSON 是错误响应；不带的是真 JSON 文件（记录快照），照常落盘
                        val text = body.string()
                        val json = try {
                            JSONObject(text)
                        } catch (e: JSONException) {
                            null
                        }
                        if (json != null && (json.has("errno") || json.has("error_code"))) {
                            throw NetdiskException(effectiveErrno(json), effectiveErrmsg(json))
                        }
                        target.parentFile?.mkdirs()
                        target.writeText(text)
                    } else {
                        target.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    return@withContext // 落盘完成，收工
                }
                // 走到这里说明刚才那一跳是 302，继续 while 跟下一跳
            }
        }
    }

    /** 下载用的客户端：关掉自动重定向，302 由 [download] 手动跟（要逐跳补 token） */
    private fun noRedirectClient(): OkHttpClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** dlink 每一跳都要带 access_token；已有的不重复拼 */
    private fun withAccessToken(url: String, token: String): String =
        if (url.contains("access_token=")) {
            url
        } else {
            url + (if (url.contains('?')) "&" else "?") + "access_token=" + token.urlEncoded()
        }

    // ---------------------------------------------------------------- 内部

    private suspend fun get(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return parse(execute(request))
    }

    private suspend fun post(pathAndQuery: String, form: FormBody): JSONObject {
        val request = Request.Builder()
            .url(panHost + pathAndQuery)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        return parse(execute(request))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && body.isBlank()) {
                // HTTP 层的失败（502 / 断流）是瞬时故障，用网络码让上层退避重试
                throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "HTTP ${response.code}")
            }
            body
        }
    }

    private fun parse(text: String): JSONObject {
        if (text.isBlank()) throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "接口返回为空")
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            // 网关拦下请求时回的是 HTML 错误页而不是 JSON。这种响应重试有意义
            //（换条链路可能就通了），按网络故障处理，别让 JSONException 裸抛成天书。
            throw NetdiskException(BaiduConfig.ERRNO_NETWORK, "接口返回的不是 JSON（可能被网关拦截）")
        }
        val errno = effectiveErrno(json)
        if (errno != BaiduConfig.ERRNO_OK) {
            throw NetdiskException(errno, effectiveErrmsg(json))
        }
        return json
    }
}
