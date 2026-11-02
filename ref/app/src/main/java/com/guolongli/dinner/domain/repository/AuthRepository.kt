package com.guolongli.dinner.domain.repository

import com.guolongli.dinner.domain.model.BaiduToken

/**
 * 百度 OAuth 授权入口。
 *
 * 走百度网盘开放平台的**快速授权（union/oauth）**，授权页由系统浏览器打开。
 *
 * 不再用 OAuth SDK：SDK 会强制上送 `pkgname` + `key_hash`（包名 + 签名指纹），
 * 后台没登记这两项就必然 `pkgname_mismatch`，授权页瞬间关闭（实机已验证）。
 * union/oauth 只校验 AppKey / SecretKey / redirect_uri / scope，
 * 与「App 里只填 AppKey + SecretKey」的口径一致。
 *
 * 应用不自己承载授权页：AGENTS.md 禁止自建 WebView UI，
 * 系统浏览器也是官方推荐的做法。
 */
interface AuthRepository {

    /**
     * 拼装授权页 URL（含随机 state）。
     *
     * 交给系统浏览器打开。`redirect_uri` 会做 URL Encode，
     * scope 用逗号分隔——三处 redirect_uri 必须逐字节一致。
     */
    fun buildAuthorizeUrl(appKey: String, state: String): String

    /**
     * 发起授权，返回一次性授权 code。
     *
     * 挂起直到用户在授权页完成或取消。失败必须如实返回失败，不得静默重试。
     * state 由实现内部生成并校验，调用方拿到的是「已经对过 state」的 code。
     */
    suspend fun authorize(): Result<String>

    /**
     * 用一次性 code 换取 token，code 10 分钟内有效，需立即调用。
     */
    suspend fun exchangeCode(code: String): Result<BaiduToken>

    /**
     * 用 refresh_token 刷新。
     *
     * 返回的 token 必须整体覆盖旧值；失败一律返回失败，
     * 调用方不得用旧值循环重试，而应引导用户重新授权。
     */
    suspend fun refresh(refreshToken: String): Result<BaiduToken>

    /**
     * 注入当前设备的开放平台凭证，供换取/刷新 token 与初始化 SDK 时使用。
     * 只驻留内存，不落盘、不入日志。
     */
    fun setCredentials(appKey: String, secretKey: String)

    /**
     * 解除授权时清掉 SDK 内部的百度登录 Cookie。
     *
     * 不清的话，下次点「开始授权」会直接沿用上一次的百度账号，
     * 用户以为换了账号、实际还在用旧的。
     */
    fun clearSdkSession()
}
