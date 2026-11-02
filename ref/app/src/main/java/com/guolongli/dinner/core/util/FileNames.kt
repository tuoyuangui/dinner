package com.guolongli.dinner.core.util

import com.guolongli.dinner.core.error.DinnerException
import java.util.UUID

/**
 * 网盘文件名生成。文件名不得含中文或特殊字符，
 * 采用 timestamp + shortHash 形式，例如 1729500000_a1b2c3.jpg。
 *
 * 本地路径由业务层保证多设备不冲突，因此时间戳前缀之外再叠加短哈希。
 */
object FileNames {
    private val HEX = "0123456789abcdef".toCharArray()

    /** 生成一个符合约定的文件名。[extension] 不含点号。 */
    fun mealPhoto(
        timestampMillis: Long = System.currentTimeMillis(),
        extension: String = "jpg",
        random: java.util.Random = java.util.Random(),
    ): String {
        val safeExt = sanitizeExtension(extension)
        return "${timestampMillis}_${shortHash(random)}.$safeExt"
    }

    /**
     * 生成上传文件名：记录的创建时间，精确到秒。
     *
     * 形如 `2026-10-29_11_22_35.jpg`。全部是 ASCII 数字，
     * 既符合网盘文件名约定，按名字排序也能直接读出先后。
     */
    fun timestampedMedia(createdAtMillis: Long, extension: String = "jpg"): String {
        return "${formatStamp(createdAtMillis)}.${sanitizeExtension(extension)}"
    }

    /**
     * 同一秒内有多段媒体时加后缀：`2026-10-29_11_22_35_2.jpg`。
     *
     * 一条记录最多 9 段媒体，而它们共用同一个记录时间，秒级精度区分不开。
     * 用后缀而不是造假时间戳：宁可名字长一点，也不让时间对不上。
     */
    fun withDedupSuffix(
        createdAtMillis: Long,
        sequence: Int,
        extension: String = "jpg",
    ): String {
        val suffix = if (sequence <= 1) "" else "_$sequence"
        return "${formatStamp(createdAtMillis)}$suffix.${sanitizeExtension(extension)}"
    }

    /**
     * 时间戳一律用 [Ascii] 拼装，绝不用 `String.format` 的默认 Locale。
     *
     * 实测过的坑：设备语言是阿拉伯语（`ar-EG-u-nu-arab`）或波斯语（`fa-IR`）时，
     * `String.format("%04d", 2026)` 会给出 `٢٠٢٦`——云端目录变成 `meals/٢٠٢٦/١٠`，
     * 文件名变成 `٢٠٢٦-١٠-٢٩_١١_٢٢_٣٥.jpg`，而 `MEDIA_NAME_REGEX` 用的是 `\d`…
     * 那两个是 ASCII 版正则里的 `[0-9]`，于是自己的文件自己都认不回来，
     * 兜底扫描、按名找 fs_id 全部失效。文件名必须是纯 ASCII，这是网盘约定，
     * 也是名字能反解日期的前提。
     */
    private fun formatStamp(createdAtMillis: Long): String {
        val at = java.time.Instant.ofEpochMilli(createdAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
        return Ascii.pad(at.year, 4) + "-" + Ascii.pad(at.monthValue, 2) + "-" +
            Ascii.pad(at.dayOfMonth, 2) + "_" + Ascii.pad(at.hour, 2) + "_" +
            Ascii.pad(at.minute, 2) + "_" + Ascii.pad(at.second, 2)
    }

    /** 六位十六进制短哈希。 */
    fun shortHash(random: java.util.Random = java.util.Random()): String {
        val chars = CharArray(6) { HEX[random.nextInt(HEX.size)] }
        return String(chars)
    }

    fun extensionOf(path: String?): String {
        val name = path?.substringAfterLast('/', "") ?: ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return "jpg"
        return sanitizeExtension(name.substring(dot + 1))
    }

    /**
     * 只保留 ASCII 字母数字，避免任何特殊字符进入文件名。
     *
     * 视频扩展名必须原样保留：早先这里把「非 png/jpg/webp」一律收敛成 jpg，
     * 于是上传的视频在网盘上被命名成 `.jpg`——内容明明是 MP4。
     * 下载方按扩展名判断类型，就会把视频当图片处理。
     */
    fun sanitizeExtension(raw: String): String {
        val cleaned = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (cleaned.isEmpty()) return "jpg"
        return when {
            cleaned == "jpeg" -> "jpg"
            cleaned in IMAGE_EXTENSIONS -> cleaned
            cleaned in VIDEO_EXTENSIONS -> cleaned
            else -> "jpg"
        }
    }

    /** 视频扩展名白名单。不在白名单内一律按 mp4 处理，保证远端文件名可控。 */
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "webm", "mkv")

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "webp")

    /**
     * 依据 MIME 推断视频扩展名。
     *
     * 网盘文件名不得含中文或特殊字符，只允许白名单内的 ASCII 扩展名；
     * 系统相册返回的 MIME 五花八门（video/quicktime、video/x-matroska 等），
     * 统一在这里收敛，避免把奇怪后缀带到网盘上。
     */
    fun videoExtension(mimeType: String?): String {
        val subtype = mimeType?.substringAfter('/', "")?.lowercase()
            ?.filter { it in 'a'..'z' || it in '0'..'9' }
            ?.takeIf { it.isNotEmpty() }
        val normalized = when (subtype) {
            "quicktime" -> "mov"
            "xmatroska", "matroska" -> "mkv"
            "3gpp" -> "3gp"
            else -> subtype
        }
        return normalized?.takeIf { it in VIDEO_EXTENSIONS } ?: "mp4"
    }

    /** 是否为受支持的视频扩展名（远端恢复时用）。 */
    fun isVideoExtension(extension: String): Boolean =
        extension.lowercase() in VIDEO_EXTENSIONS

    /**
     * 原样取小写扩展名（不做白名单收敛）。
     *
     * 用于判断已有文件到底是图片还是视频；[extensionOf] 会把未知扩展名
     * 一律收敛成 jpg，不能用来判类型。
     */
    fun rawExtension(path: String?): String {
        val name = path?.substringAfterLast('/', "") ?: return ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 视频文件的扩展名；无法识别时按 mp4 处理。 */
    fun videoExtensionOf(path: String?): String {
        val raw = rawExtension(path)
        return raw.takeIf { it in VIDEO_EXTENSIONS } ?: "mp4"
    }

    /** 本地缓存文件名，供图片下载与预览使用。 */
    fun localFileName(remotePath: String?): String {
        val base = remotePath?.substringAfterLast('/', "")?.takeIf { it.isNotBlank() }
        return base?.takeIf { it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' } }
            ?: UUID.randomUUID().toString().replace("-", "").take(16) + ".jpg"
    }

    /**
     * 校验一个远端路径确实落在数据根目录（默认 /apps/dinner/）之下，防止越界读写。
     *
     * 只做 `startsWith` 是不够的：`/apps/dinner/g/../other/x` 开头合法，
     * 但解析之后已经跑到根目录之外。所以先把 `.` 与 `..` 段解析掉再比对，
     * 并且要求解析过程不能越过根本身（`/apps/dinner/../../etc` 同样拒绝）。
     */
    fun requireInsideRoot(path: String, root: String) {
        val normalizedRoot = root.trim().trimEnd('/')
        val ok = path.trim()
            .takeIf { it.startsWith("/") }
            ?.let { canonicalize(it) }
            ?.let { canonical ->
                canonical == normalizedRoot || canonical.startsWith("$normalizedRoot/")
            } == true
        if (!ok) {
            throw DinnerException.Validation("非法网盘路径：只允许读写 $root 之下")
        }
    }

    /**
     * 把绝对路径里的 `.` 与 `..` 段解析掉，得到规范化路径。
     *
     * 只在内存里做字符串化简，不碰文件系统——远端路径在本机不一定存在，
     * 用 `File.canonicalPath` 反而会按本机的目录结构给出错误答案。
     * 返回 null 表示路径试图退到根之上（越界）。
     */
    private fun canonicalize(absolute: String): String? {
        val out = ArrayDeque<String>()
        for (segment in absolute.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit // 连续斜杠与当前目录，忽略
                segment == ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    /**
     * 从媒体文件名里取出日期，返回当天 0 点的毫秒。
     *
     * 新设备兜底扫描没有 records.json 可读，只能靠文件名反推时间。
     * **只认现行命名**：`2026-10-29_11_22_35.jpg`，以及同秒去重后的
     * `2026-10-29_11_22_35_2.jpg`。其它名字一律返回 null。
     *
     * 认不出来也不能「猜」：宁可这条不进兜底结果，也不要按错日期塞进时间线。
     */
    fun dayMillisOf(fileName: String): Long? {
        val zone = java.time.ZoneId.systemDefault()
        val name = fileName.substringAfterLast('/')
        val match = MEDIA_NAME_REGEX.matchEntire(name) ?: return null
        val (year, month, day) = Triple(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
        return runCatching {
            java.time.LocalDate.of(year, month, day)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /**
     * 现行媒体文件名的完整形态：`yyyy-MM-dd_HH_mm_ss` + 可选 `_<序号>` + 扩展名。
     *
     * 必须 `matchEntire` 而不是 `find`：`find` 只要求某处能对上，
     * `2026-10-28-xiaoming-2.jpg` 的前十个字符也是合法日期，会被误判成媒体文件。
     */
    private val MEDIA_NAME_REGEX = Regex(
        "(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})_(\\d{2})_(\\d{2})(?:_\\d+)?\\.[A-Za-z0-9]+"
    )
}
