package com.guolongli.dinner.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 网盘接口通用字段。成功判定一律 errno == 0，非 0 视为失败。
 */
@Serializable
open class BaiduBaseResponse {
    @SerialName("errno")
    var errno: Int = 0

    @SerialName("errmsg")
    var errmsg: String = ""

    @SerialName("request_id")
    var requestId: String? = null

    val isSuccess: Boolean get() = errno == 0
}

/** GET /file?method=list 的单条文件项。 */
@Serializable
data class BaiduFileEntry(
    @SerialName("fs_id") val fsId: Long = 0,
    @SerialName("path") val path: String = "",
    @SerialName("md5") val md5: String? = null,
    @SerialName("size") val size: Long = 0,
    @SerialName("server_mtime") val serverMtime: Long = 0,
    @SerialName("server_ctime") val serverCtime: Long = 0,
    @SerialName("isdir") val isDir: Int = 0,
) {
    val isDirectory: Boolean get() = isDir == 1
}

/** GET /file?method=list */
@Serializable
data class BaiduFileListResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("list") val list: List<BaiduFileEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("cursor") val cursor: Int = 0,
) {
    val isSuccess: Boolean get() = errno == 0
    val hasMorePages: Boolean get() = hasMore == 1
}

/** GET /multimedia?method=filemetas 的 dlink 项。 */
@Serializable
data class BaiduFileMeta(
    @SerialName("fs_id") val fsId: Long = 0,
    @SerialName("path") val path: String = "",
    @SerialName("md5") val md5: String? = null,
    @SerialName("size") val size: Long = 0,
    @SerialName("dlink") val dlink: String? = null,
)

/** GET /multimedia?method=filemetas */
@Serializable
data class BaiduFileMetaResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("info") val info: List<BaiduFileMeta> = emptyList(),
) {
    val isSuccess: Boolean get() = errno == 0
}

/** POST /file?method=precreate */
@Serializable
data class BaiduPrecreateResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("uploadid") val uploadId: String? = null,
    @SerialName("return_type") val returnType: Int = 0,
    @SerialName("block_list") val blockList: List<Int> = emptyList(),
) {
    val isSuccess: Boolean get() = errno == 0
}

/**
 * POST /file?method=create，以及 **delete**。
 *
 * 两个接口共用一个响应结构是有原因的：它们都返回 `errno` / `request_id`。
 * 但 **`delete` 走的是另一套错误字段**：失败时返回的是
 * `{"error_code":31024,"error_msg":"app id is empty"}`，**没有 `errno`**。
 *
 * 实测（用失效 token 打 `method=delete` 拿到的就是这个）：
 * ```
 * {"error_code":31024,"error_msg":"app id is empty","request_id":...}
 * ```
 * 而 `errno` 是带默认值 0 的 Int 字段，反序列化时缺字段就**保持 0**——
 * 于是「删除失败」被读成「errno == 0，删除成功」。
 *
 * 这正是红线里点名的那种事故：`SyncRepositoryImpl.uploadRecord` 在
 * `record.isDeleted` 分支里删完就无条件 `updateSyncState(..., SYNCED)`，
 * 删除失败却标成已同步，删除意图从此再也不会重传——本机显示「已删除」，
 * 云端照片还在，别人打开时间线照样看得见，而本地连重试入口都没有。
 *
 * 所以这里必须把 `error_code` / `error_msg` 一并接住，并让 [errno] 优先取
 * 那两套字段里非空的那个。
 */
@Serializable
data class BaiduCreateFileResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    /** `delete` 等接口的失败字段，与 [errno] 二选一出现。 */
    @SerialName("error_code") val errorCode: Int? = null,
    /** 与 [errorCode] 配套的说明，对应 [errmsg]。 */
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("fs_id") val fsId: Long = 0,
    @SerialName("path") val path: String? = null,
    @SerialName("md5") val md5: String? = null,
    @SerialName("size") val size: Long = 0,
) {
    /**
     * 归一化后的错误码。
     *
     * 有 `error_code` 就以它为准（delete 的真实口径），否则用 `errno`。
     * 调用方一律读这个，**不要直接读 [errno]**：后者在 delete 的失败响应里恒为 0。
     */
    val effectiveErrno: Int get() = errorCode ?: errno

    /** 归一化后的错误说明。 */
    val effectiveErrmsg: String
        get() = errorMsg?.takeIf { it.isNotBlank() }
            ?: errmsg.ifBlank { "" }

    val isSuccess: Boolean get() = effectiveErrno == 0
}

/**
 * 「获取上传域名」（method=locateupload）的响应。
 *
 * 文档要求：分片上传 / 单步上传**必须**使用这里返回的域名，
 * 不得在客户端固定写死上传服务器地址；结果过期或不可用时重新调用。
 *
 * - [host] 推荐主机名（通常不带协议）
 * - [servers] 可用上传服务地址（带 https 协议）
 * 两者都取不到时由调用方报错，不得静默退回某个写死的主机。
 */
@Serializable
data class BaiduLocateUploadResponse(
    @SerialName("host") val host: String? = null,
    @SerialName("servers") val servers: List<BaiduUploadServer> = emptyList(),
    @SerialName("bak_servers") val bakServers: List<BaiduUploadServer> = emptyList(),
    @SerialName("error_code") val errorCode: Int = 0,
    @SerialName("error_msg") val errorMsg: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("expire") val expire: Int = 0,
) {
    val isSuccess: Boolean get() = errorCode == 0

    /**
     * 首选上传域名，带 https 协议。
     *
     * 优先取 [servers] 的第一项（文档明确「从 servers 中选择 HTTPS 地址」）；
     * 没有 servers 时退回 [host] 并补上协议。
     */
    val preferredServer: String?
        get() {
            val fromServers = servers.firstOrNull()?.server?.takeIf { it.isNotBlank() }
            if (fromServers != null) {
                return fromServers.trimEnd('/')
            }
            val h = host?.takeIf { it.isNotBlank() } ?: return null
            return (if (h.startsWith("http")) h else "https://$h").trimEnd('/')
        }
}

@Serializable
data class BaiduUploadServer(
    @SerialName("server") val server: String = "",
)

/** 分片上传的响应体，errno 与 md5 都需检查。 */
@Serializable
data class BaiduUploadPartResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    @SerialName("md5") val md5: String? = null,
) {
    val isSuccess: Boolean get() = errno == 0
}

/**
 * union/oauth 的 token 响应（换 code 与刷新共用一种结构）。
 *
 * 与老 openapi OAuth2 的差别：整个 payload 包在 `data` 里，并且多了
 * `authorized_paths`（用户本次实际授权的目录列表）。令牌本身是加密串，
 * `xpan-<...>` 开头，**不得拆分、截断或打进日志**。
 *
 * ```
 * {"errno":0,"data":{"access_token":"xpan-...","expires_in":2592000,
 *   "refresh_token":"xpan-...","scope":"nd_open_read,nd_open_write",
 *   "authorized_paths":["/apps/MyApp/图片"]}}
 * ```
 */
@Serializable
data class UnionTokenResponse(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String = "",
    @SerialName("show_msg") val showMsg: String = "",
    @SerialName("data") val data: UnionTokenPayload? = null,
) {
    val isSuccess: Boolean get() = errno == 0 && data?.accessToken?.isNotBlank() == true
}

@Serializable
data class UnionTokenPayload(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("scope") val scope: String? = null,
    @SerialName("authorized_paths") val authorizedPaths: List<String> = emptyList(),
    @SerialName("redirect_uri") val redirectUri: String? = null,
)

/** 通用错误体，用于 ErrorBody 解析。 */
@Serializable
data class BaiduErrorBody(
    @SerialName("errno") val errno: Int? = null,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)
