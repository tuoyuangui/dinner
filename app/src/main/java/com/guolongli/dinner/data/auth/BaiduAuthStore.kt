package com.guolongli.dinner.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.guolongli.dinner.core.BaiduConfig

/**
 * 授权材料与 token 的落地：
 * - AppKey / SecretKey / 回调：一律取应用内「网盘」页用户手动填写的值（默认为空，
 *   未填写时 [BaiduAuthManager.hasKeys] 为 false，授权链路会先提示补填）；
 * - access_token / refresh_token / 过期时间：授权成功后写入。
 *
 * **敏感字段一律经 [KeystoreCipher] 加密后落盘**（client_secret、access_token、
 * refresh_token）。它们等价于「长期访问这个用户网盘目录」的钥匙，
 * 明文躺在 SharedPreferences 里，设备被 root 或做过 adb backup 就等于直接泄露。
 *
 * ## 两把钥匙，两类字段
 *
 * 应用凭据（SecretKey）与访问 token 用**不同的 Keystore 别名**。
 *
 * 这不是洁癖，是一个真实踩过的坑：早期版本一把钥匙加密所有敏感字段，
 * 而「退出授权 / 换应用」要清掉 token，顺手就把这把钥匙也删了——
 * 于是**刚保存的 SecretKey 当场变成永远解不开的密文**，
 * 用户点完「保存并授权」又被告知「请先填写 AppKey 与 SecretKey」，
 * 而且再填一次还是同样的结果（因为每次保存都再删一次钥匙）。
 *
 * 现在凭据与 token 各自一把钥匙：[clearToken] 只动 token 那把，
 * 保存凭据不受影响。
 *
 * ## 老数据迁移
 *
 * 早期版本用的是同一个别名（[ALIAS_LEGACY]）。启动时会把能用旧钥匙解开的密文
 * 重新加密到新别名下，然后删掉旧钥匙——用户不用重新填一遍凭据。
 *
 * AppKey / 回调 / 过期时间不加密：它们不是机密，加密只会让排查问题变难。
 */
class BaiduAuthStore(
    context: Context,
    /**
     * 造加密器的工厂，按别名给。默认走 Android Keystore；
     * 测试里换成内存实现，就能验证「清 token 不会误伤凭据」这类逻辑。
     */
    cipherFactory: (String) -> SecretCipher = { KeystoreCipher(it) },
) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("baidu_auth", Context.MODE_PRIVATE)

    /** 应用凭据（SecretKey）专用 */
    private val credCipher = cipherFactory(ALIAS_CREDENTIAL)

    /** 访问 token 专用；退出授权时只删这一把 */
    private val tokenCipher = cipherFactory(ALIAS_TOKEN)

    /** 早期版本一把钥匙加密所有字段时用的别名，只在迁移时读一次 */
    private val legacyCipher = cipherFactory(ALIAS_LEGACY)

    var appKey: String
        get() = prefs.getString(KEY_APP_KEY, "").orEmpty()
        set(value) = writeAppKey(value.trim())

    var appSecret: String
        get() = secret(KEY_APP_SECRET, credCipher)
        set(value) = writeAppSecret(value.trim())

    var redirectUri: String
        get() = prefs.getString(KEY_REDIRECT, "").orEmpty().ifBlank { BaiduConfig.defaultRedirectUri }
        set(value) = prefs.edit().putString(KEY_REDIRECT, value.trim()).apply()

    var accessToken: String
        get() = secret(KEY_ACCESS_TOKEN, tokenCipher)
        set(value) = putSecret(KEY_ACCESS_TOKEN, value, tokenCipher)

    var refreshToken: String
        get() = secret(KEY_REFRESH_TOKEN, tokenCipher)
        set(value) = putSecret(KEY_REFRESH_TOKEN, value, tokenCipher)

    /** token 过期时间戳（毫秒） */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    var scope: String
        get() = prefs.getString(KEY_SCOPE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SCOPE, value).apply()

    init {
        migrateLegacy()
    }

    fun saveToken(info: TokenInfo) {
        accessToken = info.accessToken
        if (info.refreshToken.isNotBlank()) refreshToken = info.refreshToken
        expiresAt = System.currentTimeMillis() + info.expiresInSeconds * 1000L
        if (info.scope.isNotBlank()) scope = info.scope
    }

    /**
     * 解除授权。
     *
     * 除了抹掉 token，还把 **token 那把** Keystore 钥匙删掉——否则本地残留的密文
     * 只是「暂时解不开」，密钥一旦被重新生成就有可能被读回来。这里要的是彻底作废。
     *
     * **不动凭据那把钥匙**：用户填的 AppKey / SecretKey 与「授权了哪个网盘账号」
     * 是两件事，退出授权不该逼他重新填一遍。
     */
    fun clearToken() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_SCOPE)
            .apply()
        tokenCipher.wipe()
    }

    /**
     * 写 AppKey。
     *
     * **换应用 = 旧 token 作废**，这条不变量放在这里（而不是只放在
     * [BaiduAuthManager.updateKeys]）守着，因为凭据可以经由很多条路被改：
     * 管理器、界面、甚至将来某个「导入配置」的功能。
     * 只要漏了任何一条，旧 token 就会留在盘上——它绑的是上一个 appKey，
     * 拿去请求必然 401，而用户看到的现象是「明明授权过了，却提示未授权」。
     *
     * 值没变就不动 token：用户点「保存」时不该被无谓地踢出授权状态。
     */
    private fun writeAppKey(value: String) {
        if (value == prefs.getString(KEY_APP_KEY, "").orEmpty()) return
        // 先清 token 再写 appKey：反过来写的话，中间那一瞬间是「新 appKey + 旧 token」，
        // 万一进程被杀，下次启动就是这个组合——拿旧 token 去请求必然 401。
        clearToken()
        prefs.edit().putString(KEY_APP_KEY, value).apply()
    }

    /** 写 SecretKey，规则与 [writeAppKey] 一致：换应用即作废旧 token。 */
    private fun writeAppSecret(value: String) {
        val current = secret(KEY_APP_SECRET, credCipher)
        if (value == current) return
        clearToken()
        putSecret(KEY_APP_SECRET, value, credCipher)
    }

    /**
     * 读一个可能加密过的字段。
     *
     * 三种情况都是空串（对上层就是「没授权」，会引导重新授权）：
     * 从来没存过、解不开（密钥被清 / 系统升级丢了）、老版本存的是明文但能读——
     * 明文那种会被 [secret] 里的兼容逻辑认出来并原样返回。
     */
    private fun secret(key: String, cipher: SecretCipher): String {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        cipher.decrypt(raw)?.let { return it }
        // 新钥匙解不开，试试早期版本那把（迁移期间可能还没搬完）
        return legacyCipher.decrypt(raw).orEmpty()
    }

    private fun putSecret(key: String, value: String, cipher: SecretCipher) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, cipher.encrypt(value)).apply()
    }

    /**
     * 把旧别名下的密文搬到新别名下。
     *
     * 搬完就把旧钥匙删掉——旧密文从此彻底作废，也避免以后再出现
     * 「两把钥匙同时存在、到底哪把是权威」的歧义。
     */
    private fun migrateLegacy() {
        if (!legacyCipher.hasKey()) return
        var migrated = false
        migrate(KEY_APP_SECRET, credCipher)?.let { migrated = true }
        migrate(KEY_ACCESS_TOKEN, tokenCipher)?.let { migrated = true }
        migrate(KEY_REFRESH_TOKEN, tokenCipher)?.let { migrated = true }
        if (migrated) legacyCipher.wipe()
    }

    /** 单个字段的迁移：旧钥匙能解开就重新加密存到新钥匙下 */
    private fun migrate(key: String, cipher: SecretCipher): Boolean {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isEmpty() || !raw.startsWith(ENC_PREFIX)) return false
        // 已经能用新钥匙解开，说明搬过了
        if (cipher.decrypt(raw) != null) return false
        val plain = legacyCipher.decrypt(raw) ?: return false
        prefs.edit().putString(key, cipher.encrypt(plain)).apply()
        return true
    }

    private companion object {
        /** 应用凭据（SecretKey） */
        const val ALIAS_CREDENTIAL = "dinner_credential_key_v1"

        /** 访问 token */
        const val ALIAS_TOKEN = "dinner_token_key_v1"

        /** 早期版本一把钥匙加密所有字段时用的别名 */
        const val ALIAS_LEGACY = "dinner_secret_key_v1"

        /** 密文前缀，与 [KeystoreCipher] 保持一致 */
        const val ENC_PREFIX = "enc:v1:"

        const val KEY_APP_KEY = "app_key"
        const val KEY_APP_SECRET = "app_secret"
        const val KEY_REDIRECT = "redirect_uri"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SCOPE = "scope"
    }
}
