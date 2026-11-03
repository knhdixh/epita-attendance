package com.knhdixh.attendance80

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.knhdixh.attendance80.auth.AndroidKeystoreCipher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** The real Android Keystore cipher on a device or emulator (the JVM tests use a software cipher). */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCipherTest {
    private val alias = "a80_test_cipher"
    private val other = "a80_test_cipher_other"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanUp() { listOf(alias, other).forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) } }

    @Test fun roundTripsAndNeverRepeatsACiphertext() {
        val cipher = AndroidKeystoreCipher(alias)
        val token = "a80s_0123456789abcdef"
        val first = cipher.encrypt(token)
        assertNotEquals(token, first)
        assertFalse("the token is not readable in the stored text", first.contains("a80s_"))
        assertEquals(token, cipher.decrypt(first))
        assertNotEquals("a fresh IV each time", first, cipher.encrypt(token))
    }

    @Test fun theKeyStaysInTheKeystoreAndSurvivesANewCipherObject() {
        val text = AndroidKeystoreCipher(alias).encrypt("secret")
        val key = keyStore.getKey(alias, null)
        assertNull("not extractable", key.encoded)
        assertEquals("secret", AndroidKeystoreCipher(alias).decrypt(text))
    }

    @Test fun aTamperedOrForeignCiphertextIsRefused() {
        val cipher = AndroidKeystoreCipher(alias)
        val text = cipher.encrypt("secret")
        val bytes = java.util.Base64.getDecoder().decode(text)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)) }
        assertThrows(Exception::class.java) { AndroidKeystoreCipher(other).decrypt(text) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(ByteArray(5))) }
    }
}
