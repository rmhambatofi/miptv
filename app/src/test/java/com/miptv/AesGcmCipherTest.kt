package com.miptv

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AES-GCM layer with a JVM key; the Keystore only supplies the key on a device. */
class AesGcmCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val cipher = AesGcmCipher { key }

    @Test fun roundTripsWithAFreshNonceEachTime() {
        val first = cipher.encrypt("p-test é €")
        val second = cipher.encrypt("p-test é €")
        assertTrue(first.startsWith(AesGcmCipher.PREFIX))
        assertNotEquals(first, second)
        assertFalse(first.contains("p-test"))
        assertEquals("p-test é €", cipher.decrypt(first))
        assertEquals("p-test é €", cipher.decrypt(second))
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test fun tamperedForeignOrMalformedTextDecryptsToNull() {
        val sealed = cipher.encrypt("secret")
        val body = sealed.substring(AesGcmCipher.PREFIX.length)
        val flipped = body.substring(0, 20) + (if (body[20] == 'A') 'B' else 'A') + body.substring(21)
        assertNull(cipher.decrypt(AesGcmCipher.PREFIX + flipped))
        assertNull(AesGcmCipher { newKey() }.decrypt(sealed))
        assertNull(cipher.decrypt("secret"))
        assertNull(cipher.decrypt("v1:!!!not base64"))
        assertNull(cipher.decrypt("v1:AAAA"))
        assertNull(cipher.decrypt(""))
    }

    @Test fun aMissingKeyFailsCleanly() {
        val lostAfterEncrypt = AesGcmCipher { create -> if (create) key else null }
        val sealed = lostAfterEncrypt.encrypt("secret")
        assertNull(lostAfterEncrypt.decrypt(sealed))
        val none = AesGcmCipher { null }
        val error = assertThrows(SecretStoreException::class.java) { none.encrypt("secret") }
        assertEquals("Stockage sécurisé indisponible sur cet appareil.", error.message)
        assertThrows(SecretStoreException::class.java) { AesGcmCipher { throw IllegalStateException("keystore down") }.encrypt("secret") }
        assertNull(AesGcmCipher { throw IllegalStateException("keystore down") }.decrypt(sealed))
    }
}
