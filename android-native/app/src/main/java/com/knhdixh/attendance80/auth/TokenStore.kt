package com.knhdixh.attendance80.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

interface TokenStore {
    val token: StateFlow<String?>
    fun currentToken(): String? = token.value
    suspend fun setToken(token: String)
    suspend fun clearToken()
}

interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

class AndroidKeystoreCipher(
    private val keyAlias: String = "a80_session",
) : TokenCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(ciphertext: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        if (combined.size < 12) throw IllegalArgumentException("Ciphertext too short")
        val iv = ByteArray(12)
        val encrypted = ByteArray(combined.size - 12)
        System.arraycopy(combined, 0, iv, 0, 12)
        System.arraycopy(combined, 12, encrypted, 0, encrypted.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}

class EncryptedTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : TokenStore {
    private val keySessionToken = stringPreferencesKey("session_token_encrypted")
    private val _token = MutableStateFlow<String?>(null)
    override val token: StateFlow<String?> = _token.asStateFlow()

    init {
        scope.launch {
            val encrypted = dataStore.data.map { it[keySessionToken] }.firstOrNull()
            if (!encrypted.isNullOrBlank()) {
                try {
                    _token.value = cipher.decrypt(encrypted)
                } catch (_: Exception) {
                    _token.value = null
                    clearToken()
                }
            }
        }
    }

    override suspend fun setToken(token: String) {
        val encrypted = cipher.encrypt(token)
        dataStore.edit { prefs ->
            prefs[keySessionToken] = encrypted
        }
        _token.value = token
    }

    override suspend fun clearToken() {
        dataStore.edit { prefs ->
            prefs.remove(keySessionToken)
        }
        _token.value = null
    }
}
