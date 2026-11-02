package com.guolongli.dinner.data.avatar

import java.io.File

/**
 * 头像缓存文件的命名规则。
 *
 * 远端仍然是 `<authorId>.jpg`（按人固定，方便别人下载），
 * 本地缓存带上版本号 `<authorId>__v<时间戳>.jpg`。
 *
 * 为什么要版本号：头像文件路径一成不变时，本地状态（Map<String, File>）内容相等，
 * 不会触发任何界面刷新，图片库也会按路径命中旧缓存，于是「换了头像时间线还是旧图」。
 * 换名即换路径，刷新与缓存失效两件事一起解决。
 *
 * 作者标识形如 `dev-<uuid>`（本身带连字符）或 11 位手机号，
 * 都不含 `__v`，所以分隔符用 `__v` 不会截断 id。
 *
 * 只认现行命名：更早的固定名 `<authorId>.jpg` 不是有效缓存，
 * [parse] 返回 null，由调用方当作旧数据清掉。
 */
internal object AvatarFiles {

    private const val JPG = ".jpg"
    private const val VERSION_MARKER = "__v"

    /** 现行命名：`<id>__v<版本>.jpg`。贪婪匹配保证 id 里的 `__v` 不会截错。 */
    private val NAME_REGEX = Regex("(.+)$VERSION_MARKER(\\d+)" + Regex.escape(JPG))

    /** 解析结果：所属成员 id 与版本号。 */
    data class Entry(val id: String, val version: Long)

    /** 本地缓存文件名。 */
    fun versionedName(id: String, version: Long): String = "$id$VERSION_MARKER$version$JPG"

    /**
     * 从缓存文件名解析出成员 id 与版本号。
     *
     * 只认 [versionedName] 生成的现行命名；其他任何形式（含旧版固定名）
     * 一律返回 null，调用方按「不认识的旧数据」处理。
     */
    fun parse(file: File): Entry? {
        val match = NAME_REGEX.matchEntire(file.name) ?: return null
        val version = match.groupValues[2].toLongOrNull() ?: return null
        return Entry(id = match.groupValues[1], version = version)
    }
}
