package com.guolongli.dinner.core

import com.guolongli.dinner.core.util.Ascii

/**
 * 网盘路径集合。
 *
 * 数据根目录固定为 `dinner` 文件夹，默认落在百度网盘「我的应用数据」下，即 `/apps/dinner`。
 * 但 [root] **只作为群组目录的父级**存在，它本身不放任何业务文件。
 *
 * `/apps` 是网盘自己维护的容器目录（「我的应用数据」），应用无权创建、也不需要创建；
 * 所以我们只从它的下一级开始建目录，`/apps/dinner` 及其内部结构才由应用负责。
 *
 * **群组目录是唯一形态**。任何群组的数据都整体落在
 * `/apps/dinner/group_<管理员手机号>/` 之内，不存在「没有群组就写根目录」的退路：
 * ```
 * /apps/dinner/group_<管理员手机号>/
 *   config/passwd.json                 管理员口令（明文）+ 动态入群密码
 *   config/records.json                群组记录快照
 *   config/members.json                成员名单（所有群成员）
 *   avatars/<memberId>.jpg             各人头像
 *   members/<memberId>/meals/yyyy/MM/   各自维护的媒体文件
 * ```
 *
 * 没有 [groupId] 时，本对象**不具备任何可写路径**：所有群组级属性都会抛
 * [IllegalStateException]。这是刻意的——未建组 / 未入组的设备不该往网盘写任何东西，
 * 静默回退到根目录只会把文件散落在 `group_*` 外面。
 *
 * 红线：任何读写都必须落在 [root] 之下，由 [FileNames.requireInsideRoot] 在数据源层校验。
 */
data class NetdiskPaths(
    val root: String = DEFAULT_ROOT,
    /** 群组身份：`group_<管理员手机号>`。null 表示尚未建群组，此时没有可用路径。 */
    val groupId: String? = null,
) {

    val hasGroup: Boolean get() = !groupId.isNullOrBlank()

    /**
     * 群组根目录，形如 `/apps/dinner/group_17380203573`。
     *
     * 没有群组时直接抛错，而不是退回 [root]：退回根目录会让媒体、快照、头像
     * 全部写到 `group_*` 外面，与「每个群所有文件都写在 group_<号码> 内」的约定冲突。
     */
    val groupRoot: String
        get() {
            val id = groupId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "尚未加入群组，没有可用的群组目录：所有数据都必须落在 group_<管理员手机号>/ 之内"
                )
            return "$root/$id"
        }

    /**
     * 群组的配置与快照目录：`group_<号码>/config/`。
     *
     * 口令、入群密码、记录快照、成员名单全部收在这一个目录里，
     * 不再出现「passwd.json 在群组根下、快照在 data/ 里」这种两处分散的布局。
     */
    val configDir: String get() = "$groupRoot/config"

    val recordsSnapshot: String get() = "$configDir/records.json"

    /** 成员名单快照，与记录分开存放，避免每次改昵称都要重写整份记录。 */
    val membersSnapshot: String get() = "$configDir/members.json"

    /**
     * passwd.json：群组唯一的一道门，存管理员口令与动态入群密码（均明文）。
     *
     * 与记录快照 / 成员名单同放在 `config/` 下：三者都是群组的配置性文件，
     * 一处找不到就是整体配置出了问题，排查时不必再猜它到底躺在哪一层。
     *
     * 文件名固定为 `passwd.json`（AGENTS.md 红线）：改名等于换一套门禁，
     * 已授权设备的入群 / 管理员登录会全部失效。
     */
    val passwdFile: String get() = "$configDir/passwd.json"

    val avatarsRoot: String get() = "$groupRoot/avatars"

    /** 某个成员自己的目录，媒体按人分开存，谁传的就在谁的文件夹里。 */
    fun memberRoot(memberId: String): String = "$groupRoot/members/$memberId"

    /**
     * 某个成员某月的媒体目录。这是唯一的媒体归档形态。
     *
     * 年 / 月一律用 [Ascii.pad] 拼，不用 `String.format` 的默认 Locale：
     * 阿拉伯语 / 波斯语设备上 `%04d` 会给出 `٢٠٢٦`，
     * 云端就会出现 `meals/٢٠٢٦/١٠` 这种非 ASCII 目录，
     * 换设备、换语言之后连自己都找不回来（见 [Ascii] 的说明）。
     */
    fun memberMealsMonth(memberId: String, year: Int, month: Int): String =
        "${memberRoot(memberId)}/meals/${Ascii.pad(year, 4)}/${Ascii.pad(month, 2)}"

    /**
     * 记录作者对应的媒体目录。
     *
     * 一律按人分目录：谁传的就在谁的文件夹里，群里多人共用一套网盘也不会互相覆盖。
     */
    fun mediaDirOf(memberId: String, year: Int, month: Int): String =
        memberMealsMonth(memberId, year, month)

    /** 某个成员的稳定头像路径，按 authorId 命名，避免多人互相覆盖。 */
    fun avatarOf(authorId: String): String = "$avatarsRoot/$authorId.jpg"

    companion object {
        /**
         * 百度网盘「我的应用数据」容器目录。
         *
         * 由网盘维护，应用只能在其下读写，不能创建它本身——建目录时这一级会被跳过。
         */
        const val APPS_CONTAINER = "/apps"

        /**
         * 默认根目录：`/apps/dinner`，即「我的应用数据」里的 dinner 文件夹。
         * 改名等于换一套数据目录，历史记录会读不到，因此固定不得随意更改。
         */
        const val DEFAULT_ROOT = "$APPS_CONTAINER/dinner"

        val Default = NetdiskPaths(DEFAULT_ROOT)

        /**
         * 规范化用户输入的根目录：
         * - 去首尾空白与结尾斜杠
         * - 只填一个名字（如 `dinner`）时补成 `/apps/dinner`：用户想的就是那个文件夹
         * - 其余缺前导斜杠时补上
         * - 必须是 `/apps/` 之下，其余一律拒绝（红线：不得在数据目录外读写）
         */
        fun normalize(raw: String): Result<String> {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("路径不能为空"))
            }
            if (trimmed.any { it.isWhitespace() } || trimmed.contains("..")) {
                return Result.failure(IllegalArgumentException("路径不能包含空格或 .."))
            }
            val withSlash = when {
                trimmed.startsWith("/") -> trimmed
                trimmed.contains('/') -> "/$trimmed"
                else -> "$APPS_CONTAINER/$trimmed"
            }
            if (!withSlash.startsWith("$APPS_CONTAINER/")) {
                return Result.failure(
                    IllegalArgumentException("必须放在 $APPS_CONTAINER 之下，例如 $DEFAULT_ROOT")
                )
            }
            if (withSlash.length > 128) {
                return Result.failure(IllegalArgumentException("路径过长"))
            }
            return Result.success(withSlash)
        }
    }
}
