package com.dsh.notes.data

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
 * API Key 的本地加密。
 *
 * 用 Android Keystore 里的一把 AES 密钥做 AES/GCM 加密，密钥本身不出安全硬件，
 * 所以就算有人把 App 的数据目录整个拷走，也解不出明文 Key。
 *
 * 说清边界：App 自己读的时候必须能解（否则没法用），所以它防的是「数据被拷走」，
 * 而不是「手机解锁后有人点开你的 App」—— 后者只能靠锁屏密码。
 */
object CryptoUtils {

    private const val TAG = "CryptoUtils"
    private const val KEY_ALIAS = "notecraft_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12
    private const val PREFIX = "enc:v1:"
    private const val PLAIN_PREFIX = "plain:"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            val key = existing ?: generateKey()
            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密。失败时降级为明文并打标记，保证功能不会因为 Keystore 异常而中断 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrElse { error ->
            Log.w(TAG, "加密失败，降级为明文存储", error)
            PLAIN_PREFIX + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
        }
    }

    /** 解密。任何异常都返回空串，让界面提示重新填写，而不是崩溃 */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            when {
                stored.startsWith(PREFIX) -> {
                    val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
                    require(packed.size > IV_LENGTH) { "密文长度异常" }
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        secretKey(),
                        GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_LENGTH)
                    )
                    String(
                        cipher.doFinal(packed, IV_LENGTH, packed.size - IV_LENGTH),
                        Charsets.UTF_8
                    )
                }

                stored.startsWith(PLAIN_PREFIX) ->
                    String(
                        Base64.decode(stored.removePrefix(PLAIN_PREFIX), Base64.NO_WRAP),
                        Charsets.UTF_8
                    )

                // 兼容早期直接存明文的版本
                else -> stored
            }
        }.getOrElse { error ->
            Log.w(TAG, "解密失败，需要重新填写 API Key", error)
            ""
        }
    }
}
