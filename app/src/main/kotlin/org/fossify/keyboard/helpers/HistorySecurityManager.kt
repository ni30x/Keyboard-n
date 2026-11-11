package org.fossify.keyboard.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

object HistorySecurityManager {

    private const val PREFS_FILE = "input_history_secure_prefs"
    private const val KEY_PIN_HASH = "history_pin_hash"
    private const val KEY_SALT = "FossifyInputHistorySecuritySalt2026"

    @Volatile
    var isUnlocked: Boolean = false

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isPinSet(context: Context): Boolean {
        return try {
            val prefs = getSecurePrefs(context)
            !prefs.getString(KEY_PIN_HASH, null).isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun setPin(context: Context, pin: String) {
        val hash = hashPin(pin)
        getSecurePrefs(context).edit()
            .putString(KEY_PIN_HASH, hash)
            .apply()
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        return try {
            val savedHash = getSecurePrefs(context).getString(KEY_PIN_HASH, null) ?: return false
            val inputHash = hashPin(inputPin)
            savedHash == inputHash
        } catch (e: Exception) {
            false
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val combined = KEY_SALT + pin
        val bytes = digest.digest(combined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
