package com.guolongli.dinner.live

import com.guolongli.dinner.data.auth.BaiduOAuthClient
import com.guolongli.dinner.data.auth.TokenInfo
import com.guolongli.dinner.data.auth.TokenSource
import com.guolongli.dinner.data.netdisk.NetdiskApi
import com.guolongli.dinner.data.netdisk.NetdiskRepository
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * 真机联调的凭据。
 *
 * 这些测试会**真的**读写百度网盘，所以默认不跑：
 * - 授权：`DINNER_DEVICE=1 ./gradlew test --tests "*DeviceCodeAuthTest*"`，按提示在浏览器确认；
 * - 联调：拿到 token 后写进 `build/live-token.json`，其余测试检测到文件才执行。
 *
 * AppKey / SecretKey 从工程根目录 local.properties 读（安装包本身不再内置凭据，
 * 见 app/build.gradle.kts），也可以用环境变量 DINNER_APP_KEY / DINNER_APP_SECRET 覆盖。
 */
object LiveEnv {

    private val tokenFile = File("build/live-token.json")

    private val localProps: Properties = Properties().apply {
        val file = File("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    val appKey: String get() = System.getenv("DINNER_APP_KEY") ?: localProps.getProperty("BAIDU_APP_KEY").orEmpty()
    val appSecret: String get() = System.getenv("DINNER_APP_SECRET") ?: localProps.getProperty("BAIDU_APP_SECRET").orEmpty()
    val appId: String get() = localProps.getProperty("BAIDU_APP_ID").orEmpty()

    fun hasKeys(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    fun hasToken(): Boolean = tokenFile.exists() && runCatching { loadToken() }.isSuccess

    fun loadToken(): StoredToken {
        val json = JSONObject(tokenFile.readText())
        return StoredToken(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token"),
            expiresAt = json.optLong("expires_at"),
            scope = json.optString("scope"),
            userName = json.optString("user_name"),
        )
    }

    fun saveToken(info: TokenInfo, userName: String = "") {
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(
            JSONObject()
                .put("access_token", info.accessToken)
                .put("refresh_token", info.refreshToken)
                .put("expires_in", info.expiresInSeconds)
                .put("expires_at", System.currentTimeMillis() + info.expiresInSeconds * 1000L)
                .put("scope", info.scope)
                .put("user_name", userName)
                .put("saved_at", System.currentTimeMillis())
                .toString(2),
        )
    }

    /** 联调用的仓储：重试压到 2 次、间隔短一点，失败要快，不要卡住测试 */
    fun repository(): NetdiskRepository = NetdiskRepository(
        auth = FileTokenSource(),
        api = NetdiskApi(),
        retryTimes = { 2 },
        retryDelayMs = { 800L },
    )
}

data class StoredToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String,
    val userName: String = "",
)

/**
 * 从文件读 token，快过期时用 refresh_token 续。
 * 顺带把「刷新」这条链路也验证了一遍——正式应用里 BaiduAuthManager 干的是同一件事。
 */
class FileTokenSource : TokenSource {

    private var current: StoredToken = LiveEnv.loadToken()

    override suspend fun token(): String {
        val expiring = current.expiresAt - 5 * 60 * 1000L < System.currentTimeMillis()
        if (!expiring) return current.accessToken
        if (current.refreshToken.isBlank()) return current.accessToken

        println("[token] 快过期了，用 refresh_token 续期")
        val renewed = BaiduOAuthClient().refresh(
            appKey = LiveEnv.appKey,
            appSecret = LiveEnv.appSecret,
            refreshToken = current.refreshToken,
        )
        LiveEnv.saveToken(renewed, current.userName)
        current = LiveEnv.loadToken()
        return current.accessToken
    }
}
