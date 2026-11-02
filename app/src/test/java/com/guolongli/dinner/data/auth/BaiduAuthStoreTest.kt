package com.guolongli.dinner.data.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 应用凭据（AppKey / SecretKey）的存取。
 *
 * 这组用例守的是一个已经真实发生过的 bug：
 * 「保存并授权」点下去，凭据写进去了，钥匙也被删了——于是 SecretKey 变成
 * 一段永远解不开的密文，界面永远提示「请先填写 AppKey 与 SecretKey」，
 * 填多少次都没用。
 *
 * 根因是「凭据」和「访问 token」共用一把 Keystore 密钥，
 * 而清 token 的动作顺手把密钥删了。现在两者各用各的钥匙。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class BaiduAuthStoreTest {

    /**
     * 内存版加密器：模拟 Android Keystore 的行为——
     * **每个 alias 一把密钥，跨实例全局共享**；wipe 之后该 alias 下的密文就再也解不开。
     *
     * 「跨实例共享」这一条很关键：真实的 Keystore 是按 alias 存密钥的，
     * 换个 [KeystoreCipher] 实例用同一个 alias，照样能用那把密钥解密。
     * 早期版本这里把密钥表写成实例私有的，于是「旧版本密文自动迁移」这条
     * 用例根本走不到迁移分支——测试替身建模错了，测出来的是假象。
     *
     * 密文格式也照抄生产代码：`enc:v1:<标记>:<明文>`。
     * 前缀 [BaiduAuthStore] 靠它区分「密文」与「老版本明文」，
     * 中间的标记用来判断「这把密文是不是用本 alias 的密钥加密的」——
     * 用错 alias 解不开，和真 Keystore 一样。
     */
    private class FakeCipher(private val alias: String) : SecretCipher {

        override fun encrypt(plain: String): String {
            if (plain.isEmpty()) return plain
            val marker = keys.getOrPut(alias) { "key-$alias-${counter++}" }
            return "$PREFIX$marker:$plain"
        }

        override fun decrypt(stored: String): String? {
            if (stored.isEmpty()) return ""
            // 没有前缀就是加密改造之前的明文，直接可用
            if (!stored.startsWith(PREFIX)) return stored
            val body = stored.removePrefix(PREFIX)
            val sep = body.indexOf(':')
            if (sep <= 0) return null
            val marker = body.substring(0, sep)
            val plain = body.substring(sep + 1)
            // 钥匙被删了就解不开——这正是原 bug 的杀伤力所在
            val myKey = keys[alias] ?: return null
            // 不是这把密钥加密的，解不开（换 alias 的情形）
            if (marker != myKey) return null
            return plain
        }

        override fun hasKey(): Boolean = keys.containsKey(alias)

        override fun wipe() {
            keys.remove(alias)
        }

        companion object {
            const val PREFIX = "enc:v1:"

            /** 全局密钥表：alias -> 密钥标记。真实 Keystore 也是全局共享的。 */
            val keys = mutableMapOf<String, String>()

            private var counter = 0

            /** 每个用例开始前清干净，避免上一个用例留下的密钥影响下一个 */
            fun reset() {
                keys.clear()
                counter = 0
            }
        }
    }

    private lateinit var store: BaiduAuthStore

    @Before
    fun setUp() {
        FakeCipher.reset()
        store = newStore()
    }

    private fun newStore(): BaiduAuthStore = BaiduAuthStore(
        ApplicationProvider.getApplicationContext(),
        cipherFactory = { alias -> FakeCipher(alias) },
    )

    @Test
    fun `保存后能原样读回 SecretKey`() {
        store.appKey = "the-app-key"
        store.appSecret = "the-secret-key"

        assertEquals("the-app-key", store.appKey)
        assertEquals("the-secret-key", store.appSecret)
    }

    @Test
    fun `清掉 token 之后凭据还在`() {
        store.appKey = "the-app-key"
        store.appSecret = "the-secret-key"
        store.saveToken(
            TokenInfo(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 3600,
                scope = "basic",
            ),
        )

        store.clearToken()

        assertEquals(
            "退出授权不该把用户填的凭据一起弄丢",
            "the-secret-key",
            store.appSecret,
        )
        assertEquals("the-app-key", store.appKey)
        assertTrue("token 应当被清掉", store.accessToken.isEmpty())
    }

    @Test
    fun `改了凭据之后旧 token 不再保留`() {
        store.saveToken(
            TokenInfo(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 3600,
                scope = "basic",
            ),
        )

        store.appKey = "new-key"
        store.appSecret = "new-secret"

        assertTrue(store.accessToken.isEmpty())
        assertTrue(store.refreshToken.isEmpty())
    }

    @Test
    fun `没填凭据时读出来是空串`() {
        assertEquals("", store.appKey)
        assertEquals("", store.appSecret)
    }

    @Test
    fun `写入时会去掉首尾空白`() {
        store.appSecret = "  spaced-secret  "

        assertEquals("spaced-secret", store.appSecret)
    }

    @Test
    fun `旧版本一把钥匙加密的密文能自动搬到新钥匙下`() {
        // 模拟老版本：所有敏感字段都用 legacy 别名加密
        val legacy = FakeCipher("dinner_secret_key_v1")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("baidu_auth", Application.MODE_PRIVATE)
        prefs.edit()
            .putString("app_secret", legacy.encrypt("legacy-secret"))
            .putString("access_token", legacy.encrypt("legacy-access"))
            .commit()

        val migrated = newStore()

        assertEquals("legacy-secret", migrated.appSecret)
        assertEquals("legacy-access", migrated.accessToken)
        // 搬完之后旧钥匙应当作废
        assertFalse("旧密钥迁移后应当删除", legacy.hasKey())
    }
}
