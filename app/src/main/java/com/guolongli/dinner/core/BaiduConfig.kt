package com.guolongli.dinner.core

/**
 * 百度网盘接入常量。
 *
 * 只要 AppKey / SecretKey 正确、应用名是 dinner，授权链路就能跑通：
 * 网盘接口只允许读写 /apps/{应用名}/ 目录，应用名叫 dinner，目录就是 /apps/dinner/。
 */
object BaiduConfig {

    /** 网盘开放平台的应用名，决定可访问目录 /apps/dinner/ */
    const val APP_NAME = "dinner"

    /** 应用数据目录（我的应用数据下） */
    const val APP_DIR = "/apps/" + APP_NAME

    /** 记录用的子目录 */
    const val RECORD_DIR = APP_DIR + "/records"

    /** 媒体子目录 */
    const val MEDIA_DIR = APP_DIR + "/media"

    /** 成员头像：一人一个 <authorId>.jpg，别和记录媒体混在一起 */
    const val AVATAR_DIR = APP_DIR + "/avatars"

    /** 群组配置（群名 / 入群密码 / 管理员口令）放一处，跨设备才对得上 */
    const val CONFIG_DIR = APP_DIR + "/config"

    /**
     * 群组门禁文件名，固定的 `passwd.json`（README「口令」一节的约定）。
     * 改名等于换一套门禁：别的设备会读不到，已授权的成员得重新入群。
     */
    const val GROUP_CONFIG_FILE = CONFIG_DIR + "/passwd.json"

    /**
     * 成员名单：一人一行，`config/members.json`。
     *
     * 为什么不从记录里反推成员：一个人入群之后可能好几天没记东西，
     * 「只看记录里出现过谁」会让他从名单里消失，管理员也就无法把管理权转给他。
     */
    const val MEMBERS_FILE = CONFIG_DIR + "/members.json"

    /** 成员人数上限：超过后拒绝加入，避免名单与快照无限膨胀 */
    const val MAX_MEMBERS = 10

    /** OAuth 回调：oob 表示由百度授权页直接展示授权码 */
    const val DEFAULT_REDIRECT_URI = "oob"

    /** 网盘接口需要的 scope */
    const val SCOPE = "basic,netdisk"

    /** 授权页地址 */
    const val AUTHORIZE_URL = "https://openapi.baidu.com/oauth/2.0/authorize"

    /** 换取 token 的地址 */
    const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"

    /** 设备码：申请短码 */
    const val DEVICE_CODE_URL = "https://openapi.baidu.com/oauth/2.0/device/code"

    /** 设备码：轮询换 token */
    const val DEVICE_TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/device/token"

    /** 设备码授权确认页（用户在任意设备打开这个网址、输入短码即可） */
    const val DEVICE_VERIFY_URL = "https://openapi.baidu.com/device"

    /** 分片大小：文档建议 4 MB */
    const val CHUNK_SIZE = 4 * 1024 * 1024

    /**
     * 小文件走「单步上传」的体积阈值，与分片大小同值。
     *
     * 文档写明 ≤ 4 MB 的文件不用切片，可以一次 POST 传完。省掉预上传 + 分片 + 合并
     * 三步请求，对头像、记录 JSON 这类小文件意义很大——开发期配额只有约 100 次/小时，
     * 一次小文件上传从 3~4 次请求降到 1 次。
     */
    const val SINGLE_STEP_MAX_SIZE: Long = 4L * 1024 * 1024

    /**
     * 单个文件的分片上限。
     * 4 MB × 1024 ≈ 4 GB，超过这个体积说明调用方给错了文件，应当在入口就拒绝。
     */
    const val MAX_BLOCKS = 1024

    /** list 单页条数。取 100 让「本页不足一页」这个终止条件在两种分页语义下都成立 */
    const val LIST_PAGE_SIZE = 100

    /** 单目录最多翻这么多条，防止异常目录把配额烧光 */
    const val LIST_MAX_ITEMS = 10_000

    // ------------------------------------------------------------ 云端整理

    /**
     * 墓碑（`deleted = true` 的记录文件）在云端保留多少天才真删。
     *
     * 为什么不能立刻删：删记录靠的是「把墓碑传上去」而不是删文件——别的设备
     * 拉列表时只会发现「少了一项」，永远不知道这条被删过，于是各自留着一份
     * 已经删掉的记录。墓碑必须留够时间让所有设备都看到。
     *
     * 为什么又要删：一份墓碑只有几百字节，但它会一直占着列表里的一行，
     * 十年后时间线真的删干净了，云端还躺着几千个 `deleted` 文件。
     * 30 天是权衡：超过一个月没开过 App 的设备，回来后把旧记录顶回来的风险
     * 已经可以接受（而且下次同步会因为本地 updatedAt 更旧被覆盖回去）。
     */
    const val TOMBSTONE_RETENTION_DAYS = 30

    /**
     * 一次整理最多删这么多个文件。
     *
     * 开发期配额只有约 100 次/小时，一个「第一次整理」的账号可能有几百份墓碑，
     * 不限量会直接把当天的配额烧光，导致接下来一整天同步全失败。
     */
    const val JANITOR_MAX_DELETES = 40

    /** 瞬时故障重试次数（与「我的 → 网盘连接」里的设置同源） */
    const val DEFAULT_RETRY = 3

    // ------------------------------------------------------------ 错误码

    /** 成功 */
    const val ERRNO_OK = 0

    /** 频控：必须指数退避重试 */
    const val ERRNO_FREQUENCY_LIMIT = 20012

    /** access_token 无效 / 过期，网盘接口最常见的鉴权失败码 */
    const val ERRNO_TOKEN_INVALID = -6

    /** list 时「目录不存在」。注意它和文件级的 31066 不是一回事 */
    const val ERRNO_DIR_NOT_EXIST = -9

    /** create 时「目标已存在」 */
    const val ERRNO_FILE_ALREADY_EXIST = -8

    /** 文件已存在（create 不带 rtype=3 时就会撞上） */
    const val ERRNO_FILE_EXIST = 31061

    /** 文件或目录不存在 */
    const val ERRNO_FILE_NOT_EXIST = 31066

    /**
     * 单步上传专属：「上传路径错误」。
     * 文档口径是 path 必须落在 /apps/{appname} 内并完成 URL 编码，
     * 实测最常见的原因是**父目录不存在**——把它当「文件不存在」一起处理，
     * 先重建目录再传，用户不用自己去网盘里补文件夹。
     */
    const val ERRNO_UPLOAD_PATH_WRONG = 31064

    /** 没有申请上传权限（应用没开通上传能力） */
    const val ERRNO_NO_UPLOAD_PERMISSION = 31024

    /** 参数错误：服务端对空上传任务建目录、假 uploadid 等都回它 */
    const val ERRNO_PARAM = 2

    /** create 的「参数异常」 */
    const val ERRNO_PARAM_INVALID = 31355

    /**
     * 本地合成的「网络故障」码：连接被掐、HTTP 5xx 且响应体为空、响应体为空。
     *
     * 为什么单独立一个码：这些确实是瞬时故障，值得退避重试；而 -1 表示的是
     * 「参数不对」这类稳定故障，混在一起会让参数错误被白重试 3 次（开发期配额
     * 只有约 100 次/小时，三次就是 3%）。
     */
    const val ERRNO_NETWORK = -130

    /**
     * 鉴权类错误：拿到这些码说明 token 已经不能用了，重试多少次都一样，
     * 必须回去刷新 token 或让用户重新授权。
     */
    val AUTH_ERRNOS = setOf(
        ERRNO_TOKEN_INVALID, // -6
        110,                 // access token invalid
        111,                 // access token expired
        20017,               // nas 接口报「token 无效」（实测值，不是 -6）
        31045,               // 用户不存在，等价于登录态失效
        213,                 // 绑定的设备号不匹配
    )

    /**
     * 重试有意义的错误码。
     *
     * 20012 是网盘接口的频控码；31034 是 listall 专属频控（文档口径：每分钟 8~10 次）；
     * 31360 是「下载链接过期」，文档明确可重试——重试会重新走 filemetas 拿到新 dlink；
     * 42211 / 42212 / 42214 是查询文件信息的条目级错误（文档口径均可重试）；
     * -130 是本地合成的网络故障（见 [ERRNO_NETWORK]）。
     * **-1 不在里面**：它表示参数错误，重试多少次结果都一样。
     */
    val TRANSIENT_ERRNOS = setOf(
        ERRNO_FREQUENCY_LIMIT,
        31034,
        31360,
        ERRNO_NETWORK,
        31363,
        42211,
        42212,
        42214,
    )

    /** 「目标本来就不存在」——删除 / 下载这类操作遇到它可以当成功 */
    val NOT_FOUND_ERRNOS = setOf(ERRNO_DIR_NOT_EXIST, ERRNO_FILE_NOT_EXIST, 12)

    /** 有明确含义、值得单独给用户一句话的错误码 */
    val FRIENDLY_ERRNOS = mapOf(
        ERRNO_FILE_EXIST to "云端已存在同名文件",
        ERRNO_FILE_NOT_EXIST to "云端文件或目录不存在",
        ERRNO_DIR_NOT_EXIST to "云端目录不存在",
        ERRNO_PARAM to "网盘接口参数有误（多半是应用目录还没建出来）",
        ERRNO_PARAM_INVALID to "上传参数异常，请再试一次",
        ERRNO_NO_UPLOAD_PERMISSION to "应用没有上传权限，请到开放平台开通",
        ERRNO_UPLOAD_PATH_WRONG to "上传路径错误（文件必须在应用目录下且父目录存在）",
        31363 to "分片校验未通过，请重试",
        31364 to "分片数量超过上限",
        31299 to "分片过小（非最后一个分片须达到 4 MB）",
        31190 to "云端找不到文件分片，请重新上传",
        10 to "创建文件失败（分片可能没传全，请重试）",
        31326 to "下载被防盗链拦截（UA 不对）",
        31362 to "下载链接签名不完整，请重新同步",
        20011 to "路径不在允许访问的范围内",
        -7 to "文件或目录名非法，或无权访问该路径",
        -10 to "网盘容量不足",
        ERRNO_FREQUENCY_LIMIT to "请求过于频繁，请稍后再试",
    )

    /**
     * 应用凭据不再随包内置：AppKey / SecretKey 一律由用户在「网盘」页手动填写，
     * 经 [com.guolongli.dinner.data.auth.BaiduAuthStore] 落盘（SecretKey 加密存储）。
     * 空凭据时 [com.guolongli.dinner.data.auth.BaiduAuthManager.hasKeys] 返回 false，
     * 授权链路会提示「请先填写 AppKey 与 SecretKey」。
     */
    val defaultRedirectUri: String get() = DEFAULT_REDIRECT_URI
}
