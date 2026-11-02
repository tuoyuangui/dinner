package com.guolongli.dinner.data.auth

import com.guolongli.dinner.core.BaiduConfig
import com.guolongli.dinner.data.netdisk.NetdiskException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 谁要调网盘接口就先要一个 token。
 * 抽成接口是为了让网盘层能脱离授权链被测试——测试里给个固定 token 就够了。
 */
interface TokenSource {
    suspend fun token(): String
}

/**
 * 授权的统一入口：谁要调网盘接口，都先来这里换一个还没过期的 token。
 * token 过期前 5 分钟自动用 refresh_token 续期，续不上就要求重新授权。
 */
class BaiduAuthManager(
    private val store: BaiduAuthStore,
    private val oauth: BaiduOAuthClient = BaiduOAuthClient(),
    private val device: DeviceCodeClient = DeviceCodeClient(),
) : TokenSource {

    private val mutex = Mutex()

    val appKey: String get() = store.appKey
    val appSecret: String get() = store.appSecret
    val redirectUri: String get() = store.redirectUri

    fun hasKeys(): Boolean = store.appKey.isNotBlank() && store.appSecret.isNotBlank()

    fun isAuthorized(): Boolean = hasKeys() && store.accessToken.isNotBlank()

    fun scope(): String = store.scope

    fun expiresAt(): Long = store.expiresAt

    fun authorizeUrl(): String {
        if (!hasKeys()) throw NetdiskException(-6, "请先填写 AppKey 与 SecretKey")
        return oauth.authorizeUrl(store.appKey, store.redirectUri)
    }

    // ---------------------------------------------------------------- 设备码

    /** 申请一个设备码，把 userCode 显示给用户 */
    suspend fun requestDeviceCode(): DeviceCode {
        if (!hasKeys()) throw NetdiskException(-6, "请先填写 AppKey 与 SecretKey")
        return device.request(store.appKey, BaiduConfig.SCOPE)
    }

    /** 轮询直到用户点同意；成功后 token 直接落库 */
    suspend fun waitForDeviceToken(
        code: DeviceCode,
        onWait: (Long) -> Unit = {},
    ): TokenInfo {
        val info = device.awaitToken(store.appKey, store.appSecret, code, onWait = onWait)
        store.saveToken(info)
        return info
    }

    /** 用授权页拿到的 code 换 token 并落库 */
    suspend fun exchange(code: String): TokenInfo {
        val info = oauth.exchangeCode(
            appKey = store.appKey,
            appSecret = store.appSecret,
            code = code.trim(),
            redirectUri = store.redirectUri,
        )
        store.saveToken(info)
        return info
    }

    /** 外部已经拿到 token（比如从别处粘过来）时直接落地 */
    fun applyToken(info: TokenInfo) = store.saveToken(info)

    /** 取一个可用 token；过期就续，续不动就报 -6 让界面提示重新授权 */
    override suspend fun token(): String = mutex.withLock {
        val current = store.accessToken
        if (current.isBlank()) throw NetdiskException(-6, "尚未授权百度网盘")

        val expiring = store.expiresAt - 5 * 60 * 1000L < System.currentTimeMillis()
        if (!expiring) return current

        val refreshToken = store.refreshToken
        if (refreshToken.isBlank()) throw NetdiskException(-6, "授权已过期，请重新授权")

        val renewed = oauth.refresh(store.appKey, store.appSecret, refreshToken)
        store.saveToken(renewed)
        renewed.accessToken
    }

    fun logout() {
        store.clearToken()
    }

    /**
     * 换应用凭据。
     *
     * 顺序有讲究：**先清旧 token，再写新凭据**。
     * 换了应用，旧 token 一定不通用（网盘侧按应用校验），留着只会让下一次
     * 请求拿着一个必然失败的 token 去撞 401。
     *
     * 写成「先写凭据再清 token」的话，中间有一瞬间「凭据已更新、token 还在」的状态；
     * 万一这时进程被杀，下次启动拿到的是旧 token + 新凭据，授权状态判断会错乱。
     * 先清后写最坏情况只是「token 没了、凭据还是旧的」，重新授权一次即可。
     *
     * [BaiduAuthStore] 自己也在守着「换应用即作废旧 token」这条不变量
     * （写凭据时若值发生变化会自动清 token），这里的 [BaiduAuthStore.clearToken]
     * 是额外的一层保险：即使填的是**完全相同**的凭据，这次「保存并授权」
     * 也必然是一次全新的授权，旧 token 不该被复用。
     */
    fun updateKeys(appKey: String, appSecret: String) {
        store.clearToken()
        store.appKey = appKey
        store.appSecret = appSecret
    }
}
