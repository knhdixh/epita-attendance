package com.knhdixh.attendance80.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCipherTest {
    // Software AES-GCM cipher matching the Keystore cipher's algorithm, IV handling and Base64 format
    class SoftwareAesGcmCipher(private val secretKey: SecretKey) : TokenCipher {
        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            return Base64.getEncoder().encodeToString(combined)
        }

        override fun decrypt(ciphertext: String): String {
            val combined = Base64.getDecoder().decode(ciphertext)
            val iv = ByteArray(12)
            val encrypted = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, encrypted, 0, encrypted.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, Charsets.UTF_8)
        }
    }

    // JVM unit test using software AES-GCM cipher (AndroidKeystoreCipher instrumented test to be added in Phase 8)
    @Test
    fun softwareAesGcmCipher_encryptDecryptRoundTrip() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        val key = keyGen.generateKey()
        val cipher = SoftwareAesGcmCipher(key)

        val token = "a80s_session_token_1234567890abcdef"
        val encrypted = cipher.encrypt(token)
        assertNotEquals(token, encrypted)

        val decrypted = cipher.decrypt(encrypted)
        assertEquals(token, decrypted)
    }
}
