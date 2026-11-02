package com.guolongli.dinner.data.remote.api

import com.guolongli.dinner.data.remote.dto.UnionTokenResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 网盘开放平台 union/oauth 的 token 接口。Base URL 为 https://pan.baidu.com/。
 *
 * 与老 `openapi.baidu.com/oauth/2.0/token` 的区别：
 * - 路径 `xpan/oauth/get`，用 `method=token` / `method=refresh_token` 区分动作；
 * - 不再有 `grant_type`，也**不需要包名/签名**（这正是能绕开 pkgname_mismatch 的原因）；
 * - 响应把 payload 包在 `data` 里，并带 `authorized_paths`。
 *
 * client_secret 不入库、不硬编码，运行时由 CredentialStore 解密后传入。
 */
interface BaiduOAuthApi {

    /**
     * 用一次性 code 换取 token。
     *
     * `redirect_uri` 必须与「应用后台配置」和「发起授权」三处完全一致，
     * 换一个字节都会以 redirect_uri 不匹配失败。
     */
    @GET("xpan/oauth/get")
    suspend fun exchangeCode(
        @Query("method") method: String = "token",
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String,
        @Query("code") code: String,
        @Query("redirect_uri") redirectUri: String,
        @Query("state") state: String? = null,
    ): UnionTokenResponse

    /**
     * 用 refresh_token 刷新。
     *
     * refresh_token 单次有效：刷新成功后必须整体覆盖旧值，旧值立刻作废。
     * 刷新不会改变授权目录（authorized_paths 继承原授权）。
     */
    @GET("xpan/oauth/get")
    suspend fun refreshToken(
        @Query("method") method: String = "refresh_token",
        @Query("client_id") clientId: String,
        @Query("client_secret") clientSecret: String,
        @Query("refresh_token") refreshToken: String,
    ): UnionTokenResponse
}
