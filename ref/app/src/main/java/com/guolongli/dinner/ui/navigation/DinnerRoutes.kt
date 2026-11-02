package com.guolongli.dinner.ui.navigation

/**
 * 路由定义与参数名。
 *
 * 单独成文件（不含任何 Composable），这样各 feature 可以安全引用路由常量，
 * 而不会和 DinnerApp 形成包级循环依赖。
 */
object DinnerRoute {

    const val TIMELINE = "timeline"
    const val MINE = "mine"
    const val EDIT = "edit"
    const val PAIRING = "pairing"
    const val AUTH = "auth"
    const val ABOUT = "about"

    /** 群组：建群、入群、管理员核对加入申请。 */
    const val GROUP = "group"

    /** 月历：按月看哪天有记录。 */
    const val CALENDAR = "calendar"

    /** 记录明细列表：按筛选条件列出记录。 */
    const val RECORDS = "records"

    /**
     * 记录详情（只读展示）。
     *
     * 时间线与日历点进来默认看这条记录本身，改内容要显式点「编辑」——
     * 一进来就是编辑表单，容易误触改掉别人的记录，也看不清照片。
     */
    const val DETAIL = "record"

    /** 编辑页可选参数：传入表示编辑已有记录，不传表示新建。 */
    const val ARG_RECORD_ID = "recordId"


    /** 记录列表的筛选条件与可选的具体日期（yyyy-MM-dd）。 */
    const val ARG_FILTER = "filter"
    const val ARG_DAY = "day"

    /** 需要底部导航栏的顶层目的地。 */
    val TOP_LEVEL = setOf(TIMELINE, MINE)

    /** 编辑页带可选参数的路由模板。 */
    const val EDIT_PATTERN = "$EDIT?$ARG_RECORD_ID={$ARG_RECORD_ID}"


    /** 记录列表路由模板：筛选条件必填，日期可选。 */
    const val RECORDS_PATTERN = "$RECORDS?$ARG_FILTER={$ARG_FILTER}&$ARG_DAY={$ARG_DAY}"

    /** 详情页路由模板：recordId 是路径参数，必填。 */
    const val DETAIL_PATTERN = "$DETAIL/{$ARG_RECORD_ID}"

    fun edit(recordId: String? = null): String =
        if (recordId.isNullOrBlank()) EDIT else "$EDIT?$ARG_RECORD_ID=$recordId"

    /** 记录详情路由。id 需要 URL 编码，避免 UUID 之外的字符破坏路径。 */
    fun detail(recordId: String): String = "$DETAIL/${java.net.URLEncoder.encode(recordId, "UTF-8")}"

    /** 记录列表路由。[day] 形如 2026-10-28，仅在按天查看时传入。 */
    fun records(filter: String, day: String? = null): String {
        val base = "$RECORDS?$ARG_FILTER=$filter"
        return if (day.isNullOrBlank()) base else "$base&$ARG_DAY=$day"
    }

    /** 授权页路由。授权结果由百度 OAuth SDK 从 Activity 回调，不再走深链参数。 */
    const val AUTH_ROUTE = AUTH
}
