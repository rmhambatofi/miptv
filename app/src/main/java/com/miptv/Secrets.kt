package com.miptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shown when a stored password can no longer be decrypted (key lost: restore on another device, wiped Keystore). */
const val CREDENTIALS_TO_REENTER = "Identifiants à ressaisir."

/** Thrown by [SecretCipher.encrypt] when no key can be obtained; the message is safe to show. */
class SecretStoreException(cause: Throwable?) : GeneralSecurityException("Stockage sécurisé indisponible sur cet appareil.", cause)

/** Encrypts provider passwords at rest. [decrypt] returns null, never garbage, when the text cannot be read any more. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encrypted: String): String?
}

/**
 * AES-256-GCM with a fresh random 96-bit nonce per value; output is `v1:` + base64(nonce ‖ ciphertext ‖ tag).
 * [key] is asked for the key, creating it only when encrypting: decrypting with a missing key fails cleanly.
 */
open class AesGcmCipher(private val key: (create: Boolean) -> SecretKey?) : SecretCipher {
    override fun encrypt(plain: String): String {
        val secret = try { key(true) } catch (error: Exception) { throw SecretStoreException(error) } ?: throw SecretStoreException(null)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret) // the provider draws the nonce; the Keystore refuses a caller-supplied one
        val nonce = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    override fun decrypt(encrypted: String): String? {
        if (!encrypted.startsWith(PREFIX)) return null
        val bytes = try { Base64.getDecoder().decode(encrypted.substring(PREFIX.length)) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size < NONCE_BYTES + TAG_BYTES) return null
        return try {
            val secret = key(false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, bytes, 0, NONCE_BYTES))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFIX = "v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

/**
 * Key held by the Android Keystore: generated on first use, never exportable, and absent after a restore on another
 * device, which turns the stored passwords into "Identifiants à ressaisir" instead of silently wrong values.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : AesGcmCipher({ create -> keystoreKey(alias, create) }) {
    companion object {
        const val DEFAULT_ALIAS = "miptv.sources"
        private const val STORE = "AndroidKeyStore"

        private fun keystoreKey(alias: String, create: Boolean): SecretKey? {
            val store = KeyStore.getInstance(STORE).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            if (!create) return null
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
