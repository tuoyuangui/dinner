package com.guolongli.dinner.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.guolongli.dinner.core.error.DinnerException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * 用 Android Keystore 加密敏感字段（client_secret、refresh_token）。
 *
 * 密钥材料由 Keystore 托管，永不离开 TEE / StrongBox；应用只保存密文。
 * 采用 AES/GCM/NoPadding，每次加密随机 IV 并与密文一同保存。
 */
class KeystoreCipher {

    private val alias = "dinner_secret_key_v1"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

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

    /** 加密明文，返回 base64(IV || 密文)。 */
    fun encrypt(plain: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            iv.copyInto(combined, 0, 0, iv.size)
            encrypted.copyInto(combined, iv.size, 0, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (t: Throwable) {
            throw DinnerException.Local("敏感信息加密失败", t)
        }
    }

    /** 解密 base64(IV || 密文)。密文损坏或密钥被清除时抛错，调用方应引导重新录入。 */
    fun decrypt(encoded: String): String {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > IV_LENGTH) { "密文长度不足" }
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val body = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            throw DinnerException.Local("敏感信息解密失败，请重新录入", t)
        }
    }

    /** 清除 Keystore 密钥。用于解除授权时彻底作废本地机密。 */
    fun wipe() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12
    }
}
