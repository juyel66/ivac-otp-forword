package com.ivac.assistant.core.data.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Manages encrypted storage of pairing credentials using Android Keystore
 * and EncryptedSharedPreferences (AES-256 GCM).
 */
class SecureStorage(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for older emulators/test environments if KeyStore provider throws
        context.getSharedPreferences(PREFS_FILENAME + "_fallback", Context.MODE_PRIVATE)
    }

    init {
        // Ensure device has a unique persistent hardware/install identifier
        if (!prefs.contains(KEY_DEVICE_ID)) {
            val generatedId = "and_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, generatedId).apply()
        }
    }

    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, null) ?: "and_default_dev"
    }

    fun savePairing(pairCode: String, phone: String, token: String? = null) {
        prefs.edit()
            .putString(KEY_PAIR_CODE, pairCode.trim())
            .putString(KEY_PHONE, phone.trim())
            .putString(KEY_AUTH_TOKEN, token ?: "")
            .putBoolean(KEY_IS_PAIRED, true)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_PAIR_CODE)
            .remove(KEY_PHONE)
            .remove(KEY_AUTH_TOKEN)
            .putBoolean(KEY_IS_PAIRED, false)
            .remove(KEY_PAIRED_AT)
            .apply()
    }

    fun getPairCode(): String? = prefs.getString(KEY_PAIR_CODE, null)

    fun getPhone(): String? = prefs.getString(KEY_PHONE, null)

    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    fun isPaired(): Boolean = prefs.getBoolean(KEY_IS_PAIRED, false) && !getPairCode().isNullOrBlank()

    fun getPairedAt(): Long = prefs.getLong(KEY_PAIRED_AT, 0L)

    companion object {
        private const val PREFS_FILENAME = "ivac_secure_prefs"
        private const val KEY_DEVICE_ID = "sec_device_id"
        private const val KEY_PAIR_CODE = "sec_pair_code"
        private const val KEY_PHONE = "sec_phone"
        private const val KEY_AUTH_TOKEN = "sec_auth_token"
        private const val KEY_IS_PAIRED = "sec_is_paired"
        private const val KEY_PAIRED_AT = "sec_paired_at"
    }
}

