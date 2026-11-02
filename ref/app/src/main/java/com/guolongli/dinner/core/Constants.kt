package com.guolongli.dinner.core

/**
 * 网盘路径见 [NetdiskPaths]，根目录可配置，不再在此处写死。
 */

object BaiduApi {
    /** 网盘接口统一 Base URL，access_token 统一放查询参数。 */
    const val PAN_BASE_URL = "https://pan.baidu.com/rest/2.0/xpan/"

    /**
     * 分片上传走的是**另一台主机**，不是 pan.baidu.com。
     *
     * 这是百度网盘接口里最容易被忽略的一处：precreate 和 create 在
     * pan.baidu.com/rest/2.0/xpan/file，中间这一步却要打到
     * d.pcs.baidu.com/rest/2.0/pcs/superfile2，且必须带 type=tmpfile。
     * 打到 xpan/file?method=superfile2 一律失败。
     */
    const val PCS_BASE_URL = "https://d.pcs.baidu.com/"

    /**
     * 分片上传 / 单步上传的接口路径。主机由「获取上传域名」决定，**不得写死**。
     *
     * 官方文档原文：「上传文件数据前必须调用本接口，不要长期缓存或固定写死某个
     * 上传域名」。早先这里写死了 d.pcs.baidu.com 与 c3.pcs 兜底，
     * 一旦百度调整调度（换机房 / 换线路），上传会整片失败而原因完全看不出来。
     */
    const val PCS_SUPERFILE_PATH = "rest/2.0/pcs/superfile2"

    /** 单步上传接口路径（小文件用，免掉预上传 + 分片两步）。 */
    const val PCS_SINGLE_FILE_PATH = "rest/2.0/pcs/file"

    /** 「获取上传域名」接口固定挂在 [PCS_BASE_URL] 之下。 */
    const val PCS_LOCATE_PATH = "rest/2.0/pcs/file"

    /** 分片上传的固定值，百度要求原样传。 */
    const val PCS_UPLOAD_TYPE = "tmpfile"

    /**
     * 老的 openapi OAuth2 host。当前授权走 union/oauth（见 [OAuthConfig]）后
     * 这里只留给历史文档，不再有调用方；不要拿它去拼授权 URL。
     */
    const val OAUTH_BASE_URL = "https://openapi.baidu.com/"

    /** 下载大于约 20 MB 文件时百度要求携带的 User-Agent，由拦截器统一设置。 */
    const val USER_AGENT = "pan.baidu.com"

    /** 频控错误码：必须指数退避重试。 */
    const val ERRNO_FREQUENCY_LIMIT = 20012
    const val ERRNO_OK = 0

    /** access_token 无效 / 过期。-6 是网盘接口最常见的鉴权失败码。 */
    const val ERRNO_TOKEN_INVALID = -6

    /**
     * nas 接口报「token 无效」（错误信息为 `nas token无效`）。
     *
     * 实测：`GET /rest/2.0/xpan/nas?method=uinfo` 传失效 token 时返回的就是这个码，
     * 而不是 -6。它同样是鉴权失败，必须归到鉴权类触发刷新 / 重新授权，
     * 否则用户只会看到一句原始报错，界面也不给重新授权的入口。
     */
    const val ERRNO_NAS_TOKEN_INVALID = 20017

    /** 用户不存在，多数情况是登录态（授权）已失效。 */
    const val ERRNO_USER_NOT_EXIST = 31045

    /** 目标路径已存在同名文件。create 不带 rtype=3 时就会撞上。 */
    const val ERRNO_FILE_EXIST = 31061

    /** 要操作的文件或目录不存在。-9 是 list 时「目录不存在」的返回码。 */
    const val ERRNO_FILE_NOT_EXIST = 31066

    /** 目录不存在（list 接口返回这个码，不应当成失败）。 */
    const val ERRNO_DIR_NOT_EXIST = -9

    /**
     * 文件列表单页条数。
     *
     * 取 100 而不是官方允许的更大值，是为了让「本页不足一页」这个终止条件
     * 在两种分页语义下都成立：无论服务端按 start+limit 还是 page+num 理解，
     * 都会老老实实返回不超过 100 条，翻页判断不会提前收工。
     */
    const val LIST_PAGE_SIZE = 100

    /** 单目录最多翻这么多条，防止异常目录把开发期 100 次/小时的配额烧光。 */
    const val LIST_MAX_ITEMS = 10_000
}

/**
 * 一条记录的字段上限。
 *
 * 单一来源：写入侧（EditViewModel 的输入把关）与编码侧（MealRecordMapper 的归一化）
 * 都必须读这里的常量。分两处定义时数值一旦漂移，界面会显示一个值、落库变成另一个值，
 * 用户看到的是标签凭空被截断。
 */
object RecordLimits {
    /** 每条记录最多 6 个标签。 */
    const val MAX_TAGS = 6

    /** 单个标签最多 16 个字符。 */
    const val MAX_TAG_LENGTH = 16

    /** 评分范围 1..5，null 表示未评分。 */
    const val MIN_RATING = 1
    const val MAX_RATING = 5
}

object OAuthConfig {
    /**
     * 走百度网盘开放平台的「快速授权」（union/oauth），**不用 OAuth SDK**。
     *
     * 为什么换掉 SDK：SDK 2.0.7 的 `BdSsOHandler` / `OauthWebView` 每次请求都会
     * 自动带上 `pkgname` 与 `key_hash`（包名 + 签名指纹），服务端拿它们与后台登记
     * 比对；后台那个「软件」类型应用没有安卓包名/签名登记区，于是必然回
     * `#error=pkgname_mismatch&error_description=Invalid+Packagename+or+BundleID`，
     * 授权页只停留零点几秒就关掉。实机日志已确认。
     *
     * `union/oauth` 只校验 client_id / client_secret / redirect_uri / scope，
     * 不涉及包名与签名，因此「只填 AppKey + SecretKey」就能授权。
     */

    /**
     * 授权页地址（浏览器打开）。
     *
     * 注意 host 是 `pan.baidu.com`，不是 `openapi.baidu.com`：
     * 这是网盘开放平台的目录级授权入口，与老 OAuth2 授权页是两套。
     */
    const val AUTHORIZE_ENDPOINT = "https://pan.baidu.com/union/oauth/authorize"

    /**
     * 回调地址。必须与「应用后台配置」「发起授权」「换取 token」三处完全一致。
     *
     * 必须登记成应用可以接住的地址：本项目没有自建服务端，
     * 因此用自定义 scheme 由本机 `MainActivity` 接住回调。
     */
    const val REDIRECT_URI = "dinner://oauth/callback"

    /** 需要放行重定向的 scheme/host，供 Manifest 与 Intent 过滤使用。 */
    const val REDIRECT_SCHEME = "dinner"
    const val REDIRECT_HOST = "oauth"
    const val REDIRECT_PATH = "/callback"

    /**
     * 申请的权限范围。
     *
     * `nd_open_read` / `nd_open_write`：目录级授权的读写。
     * 走 union/oauth 时**不要**再带老的 `basic,netdisk`，那套是 openapi OAuth2 的。
     */
    const val SCOPE = "nd_open_read,nd_open_write"

    /** 换取 token 的接口地址。 */
    const val TOKEN_ENDPOINT = "https://pan.baidu.com/xpan/oauth/get"

    /** 授权码一次性有效时限（文档口径：10 分钟），仅用于文案与诊断。 */
    const val CODE_TTL_MS = 10 * 60 * 1000L
}

object UploadConfig {
    /**
     * 分片大小随会员档位变化：普通用户 4 MB，超级会员 32 MB。
     * 默认取普通档位，避免普通账号上传被拒。
     */
    const val BLOCK_SIZE_DEFAULT = 4 * 1024 * 1024
    const val BLOCK_SIZE_SUPER = 32 * 1024 * 1024

    /** 分片数上限。 */
    const val MAX_BLOCKS = 1024

    /** 上传前压缩：JPEG 质量 80%，最长边不超过 1920px。 */
    const val JPEG_QUALITY = 80
    const val MAX_EDGE_PX = 1920

    /**
     * 「不压缩」时转成 JPEG 的质量。
     *
     * 只在源图不是 JPEG（HEIF / PNG / WebP）时才用到——那类图没法直接沿用原字节，
     * 用一个接近无损的高质量档把画质损失压到肉眼难辨。
     */
    const val JPEG_QUALITY_ORIGINAL = 95

    /**
     * 单个视频体积上限。
     *
     * 视频不做转码（端上转码成本高且必然掉画质），因此只能在入口限制体积：
     * 超过上限直接拒绝并提示先用系统相册裁剪，避免一次上传几百 MB
     * 触发频控、超时或半途失败留下半成品。
     */
    const val MAX_VIDEO_BYTES = 200L * 1024 * 1024

    /** 视频封面帧的 JPEG 质量与最大边长（仅用于列表展示，不需要原画质）。 */
    const val THUMBNAIL_QUALITY = 80
    const val THUMBNAIL_MAX_EDGE_PX = 1280
}

object SyncConfig {
    /** 每批上传条数。 */
    const val BATCH_SIZE = 10

    /** 指数退避：初始 1s。 */
    const val RETRY_INITIAL_DELAY_MS = 1_000L

    /**
     * 单次网盘请求最多尝试几次的**默认档**。
     *
     * 用户可在「我的 → 存储与网络」改（1 / 2 / 3 / 5），实际次数从
     * `StorageConfigRepository.networkRetries()` 取；这里只是没设过时的兜底，
     * 两处必须是同一个值，否则改了设置也不生效。
     */
    const val RETRY_MAX_ATTEMPTS = 3

    /** 可选的重试档位。1 表示不重试（失败即报错）。 */
    val RETRY_OPTIONS = listOf(1, 2, 3, 5)

    const val RETRY_MIN = 1
    const val RETRY_MAX = 5

    const val WORK_NAME_PERIODIC = "dinner-sync-periodic"
    const val WORK_NAME_ONE_SHOT = "dinner-sync-oneshot"
}
