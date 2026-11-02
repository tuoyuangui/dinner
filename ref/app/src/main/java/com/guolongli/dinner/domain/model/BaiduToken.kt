package com.guolongli.dinner.domain.model

/**
 * 一次 OAuth 授权得到的 token 组合，按设备隔离加密落盘。
 *
 * refresh_token 单次有效：刷新成功后必须整体覆盖旧值，不得复用。
 */
data class BaiduToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String? = null,
    /**
     * 用户本次实际授权的目录列表（union/oauth 的 `authorized_paths`）。
     *
     * 必须落库并在请求前校验：请求路径不在这个范围内会被服务端拒绝。
     * 刷新 token 不会改变它（继承原授权）。
     */
    val authorizedPaths: List<String> = emptyList(),
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    /** 提前 10 分钟视为需要刷新，避免请求边界上刚好过期。 */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean =
        now >= expiresAt - REFRESH_MARGIN_MS

    companion object {
        const val REFRESH_MARGIN_MS = 10 * 60 * 1000L
    }
}

/** 应用内录入、由 Keystore 加密保管的开放平台凭证。 */
data class BaiduCredentials(
    val appKey: String,
    val secretKey: String,
) {
    val isConfigured: Boolean get() = appKey.isNotBlank() && secretKey.isNotBlank()
}
