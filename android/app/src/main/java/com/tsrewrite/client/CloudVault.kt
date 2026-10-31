package com.tsrewrite.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the myTeamSpeak sign-in in app storage, encrypted with a key that never leaves the phone's keystore. */
internal class CloudVault(context: Context) : MyTeamSpeakCloud.Vault {
    private val prefs = context.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    override fun load(): String? {
        val stored = prefs.getString(ENTRY, null) ?: return null
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV))
            String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun save(value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ENTRY, java.util.Base64.getEncoder().encodeToString(sealed)).apply()
    }

    override fun clear() {
        prefs.edit().remove(ENTRY).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "mytscloud"
        const val ENTRY = "signin"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
