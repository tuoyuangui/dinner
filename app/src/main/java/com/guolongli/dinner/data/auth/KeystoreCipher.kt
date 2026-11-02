package com.guolongli.dinner.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的加解密器。
 *
 * 抽成接口有两个用处：
 * - 单元测试里换掉它——Android Keystore 在 JVM 上根本不存在，
 *   不抽出来就没法验证「清 token 会不会误伤凭据」这类逻辑；
 * - 将来要换算法（或者加硬件备份开关）时，只改实现不改调用方。
 */
interface SecretCipher {
    /** 加密。失败时按约定退回明文。 */
    fun encrypt(plain: String): String

    /** 解密。解不开返回 null——调用方应把它当成「没有这个凭据」。 */
    fun decrypt(stored: String): String?

    /** 这个别名下是否存在密钥 */
    fun hasKey(): Boolean

    /** 删除这个别名下的密钥，本地残留的密文就彻底作废 */
    fun wipe()
}

/**
 * 用 Android Keystore 加密落盘的敏感字段（client_secret、access_token、
 * refresh_token）。
 *
 * 密钥材料由 Keystore 托管，留在 TEE / StrongBox 里不出来，应用这边只存密文。
 * 意义在于：`/data/data` 被 root 拷走、或者设备做过 adb backup 时，
 * 拿到的只是一串解密不了的字节。refresh_token 尤其要紧——它等于
 * 「长期访问这个用户网盘里我们那个应用目录」的钥匙。
 *
 * 算法是 AES/GCM/NoPadding，每次加密随机 IV，IV 与密文一起存成 base64(IV||密文)。
 *
 * **一个实例只管一个别名（alias）**。这一点很要紧：
 * 如果「应用凭据」和「访问 token」共用一把密钥，那么清 token 时顺手把密钥删掉，
 * 刚加密写入的 SecretKey 也就永远解不开了——用户会看到「保存了凭据，转头又提示要填」。
 * 所以凭据与 token 各用各的别名，互不影响（见 [BaiduAuthStore]）。
 *
 * 三条兜底：
 * - **Keystore 不可用**（个别机型、系统被改过）：[encrypt] 原样返回明文，
 *   行为退化成改造之前，但功能不坏；
 * - **解不开**（密钥被用户清过、系统升级丢了）：返回 null，调用方当作「没授权」，
 *   引导重新授权，而不是把一串乱码当 token 发出去；
 * - **老版本存的明文**：能解密失败的密文才是密文，明文解不开就按明文用，
 *   下一次写入时自动升级成密文。
 */
class KeystoreCipher(private val alias: String) : SecretCipher {

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 加密。Keystore 不可用时返回明文（见类注释的兜底约定）。 */
    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + body.size)
            iv.copyInto(combined, 0, 0, iv.size)
            body.copyInto(combined, iv.size, 0, body.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (error: Throwable) {
            Log.w(TAG, "加密失败，按明文保存：${error::class.java.simpleName}")
            plain
        }
    }

    /** 解密。解不开返回 null——调用方应把它当成「没有这个凭据」。 */
    override fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        // 没有前缀就是加密改造之前的明文，直接可用
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(combined.size > IV_LENGTH) { "密文长度不足" }
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val body = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (error: Throwable) {
            Log.w(TAG, "解密失败，视为未授权：${error::class.java.simpleName}")
            null
        }
    }

    /** 这个别名下是否存在密钥 */
    override fun hasKey(): Boolean = runCatching {
        keyStore().containsAlias(alias)
    }.getOrDefault(false)

    /** 删除这个别名下的密钥，本地残留的密文就彻底作废 */
    override fun wipe() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private companion object {
        const val TAG = "KeystoreCipher"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12

        /**
         * 密文标记。别用「能不能 base64 解码」来区分明文和密文——
         * 有些 token 本身就长得像 base64，判错了会解出一堆乱码。
         */
        const val PREFIX = "enc:v1:"
    }
}
