package com.ivac.assistant.core.data.repository

import android.content.Context
import android.os.Build
import com.ivac.assistant.BuildConfig
import com.ivac.assistant.core.data.api.IvacApiService
import com.ivac.assistant.core.data.model.ForwardingStatus
import com.ivac.assistant.core.data.model.OtpPollRequest
import com.ivac.assistant.core.data.model.OtpPushRequest
import com.ivac.assistant.core.data.model.PairVerifyRequest
import com.ivac.assistant.core.data.network.NetworkClient
import com.ivac.assistant.core.data.preferences.PreferencesManager
import com.ivac.assistant.core.data.security.SecureStorage
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Result wrapper for repository operations.
 */
sealed class Resource<out T> {
    data class Success<out T>(val data: T, val message: String? = null) : Resource<T>()
    data class Error(val message: String, val statusCode: Int? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

class IvacRepository(
    private val context: Context,
    private val secureStorage: SecureStorage,
    private val preferencesManager: PreferencesManager
) {

    // In-memory de-duplication cache: key -> timestamp (millis)
    // Prevents duplicate OTP submissions within 2 minutes
    private val processedOtpCache = ConcurrentHashMap<String, Long>()
    private val DEDUPLICATION_WINDOW_MS = 2 * 60 * 1000L

    private suspend fun getApiService(): IvacApiService {
        val currentBaseUrl = preferencesManager.baseUrl.first()
        return NetworkClient.createApiService(currentBaseUrl)
    }

    /**
     * Syntactic validation of Extension Client Key.
     * Must be 8 characters matching format XXXX-XXXX (e.g. 6988-P97K)
     * using base32 charset (ABCDEFGHJKLMNPQRSTUVWXYZ23456789).
     */
    fun isValidKeySyntax(key: String): Boolean {
        val clean = key.trim().uppercase()
        val regex = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{4}-?[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{4}$")
        return regex.matches(clean)
    }

    fun formatPairKey(raw: String): String {
        val clean = raw.trim().replace("-", "").uppercase()
        return if (clean.length == 8) {
            "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
        } else {
            raw.trim().uppercase()
        }
    }

    /**
     * Verifies pairing key against backend.
     * Follows rule: Do not consider a device paired just because input is syntactically valid.
     */
    suspend fun pairWithExtension(rawKey: String, phone: String): Resource<Boolean> {
        val pairCode = formatPairKey(rawKey)
        if (!isValidKeySyntax(pairCode)) {
            return Resource.Error("Invalid key format. Expected 8 characters like '6988-P97K'.")
        }

        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.length < 10) {
            return Resource.Error("Please enter a valid mobile number (at least 10 digits).")
        }

        try {
            val api = getApiService()
            val deviceId = secureStorage.getDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val appVersion = BuildConfig.VERSION_NAME

            // First attempt: try dedicated companion pairing endpoint
            val pairReq = PairVerifyRequest(
                pairCode = pairCode,
                phone = cleanPhone,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion
            )

            try {
                val pairResp = api.verifyPairing(pairReq)
                if (pairResp.isSuccessful && pairResp.body()?.ok == true) {
                    secureStorage.savePairing(pairCode, cleanPhone, pairResp.body()?.token)
                    preferencesManager.recordForwardingEvent("Device paired successfully", false)
                    return Resource.Success(true, pairResp.body()?.message ?: "Paired successfully")
                }
            } catch (_: Exception) {
                // If /v1/companion/pair is not yet deployed on legacy backend,
                // fallback to validating via existing /v1/otp/poll probe
            }

            // Fallback probe against existing backend: test poll endpoint with pairCode & phone
            val pollResp = api.pollOtp(OtpPollRequest(phone = cleanPhone, pairCode = pairCode, since = System.currentTimeMillis()))
            if (pollResp.isSuccessful && pollResp.body()?.ok == true) {
                secureStorage.savePairing(pairCode, cleanPhone, null)
                preferencesManager.recordForwardingEvent("Paired with existing backend relay", false)
                return Resource.Success(true, "Paired successfully with relay server.")
            } else {
                val errorMsg = pollResp.errorBody()?.string() ?: "Relay server rejected pairing key."
                return Resource.Error("Server verification failed: $errorMsg")
            }

        } catch (e: Exception) {
            return Resource.Error("Connection failed: ${e.localizedMessage ?: "Relay server unreachable"}")
        }
    }

    /**
     * Forwards an extracted OTP to the paired Chrome Extension via the backend relay.
     * Sanitizes inputs, verifies pairing, protects against duplicate delivery,
     * and records event status.
     */
    suspend fun forwardOtp(extractedOtp: String): Resource<String> {
        if (!secureStorage.isPaired()) {
            preferencesManager.recordForwardingEvent("Ignored: Device not paired", false)
            return Resource.Error("Device is not paired with any Chrome Extension.")
        }

        val pairCode = secureStorage.getPairCode() ?: return Resource.Error("Missing pairing code.")
        val phone = secureStorage.getPhone() ?: return Resource.Error("Missing paired phone number.")

        val cleanOtp = extractedOtp.replace(Regex("[^0-9]"), "")
        if (cleanOtp.length < 4 || cleanOtp.length > 8) {
            preferencesManager.recordForwardingEvent("Ignored: Invalid OTP length", false)
            return Resource.Error("Invalid OTP format.")
        }

        // Duplicate delivery protection:
        // Hash (pairCode + cleanOtp) to check if we already pushed this within the window
        val deduplicationKey = "$pairCode:$cleanOtp"
        val now = System.currentTimeMillis()
        val lastSeen = processedOtpCache[deduplicationKey]

        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            preferencesManager.recordForwardingEvent("Ignored: Duplicate OTP within 2 minutes", false)
            return Resource.Success("Duplicate OTP detected — already forwarded", "Duplicate skipped")
        }

        // Evict expired entries from deduplication cache
        processedOtpCache.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }

        try {
            val api = getApiService()
            val request = OtpPushRequest(
                phone = phone,
                pairCode = pairCode,
                otp = cleanOtp
            )

            val response = api.pushOtp(request)
            if (response.isSuccessful && response.body()?.ok == true) {
                // Record in duplicate prevention cache
                processedOtpCache[deduplicationKey] = now
                preferencesManager.recordForwardingEvent("OTP forwarded successfully to Extension", true)
                return Resource.Success("OTP forwarded successfully", response.body()?.message)
            } else {
                val errMsg = response.errorBody()?.string() ?: "Relay server rejected OTP push"
                preferencesManager.recordForwardingEvent("Failed: $errMsg", false)
                return Resource.Error("Failed to push OTP: $errMsg", response.code())
            }
        } catch (e: Exception) {
            preferencesManager.recordForwardingEvent("Failed: Network error (${e.message})", false)
            return Resource.Error("Network error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Checks backend reachability.
     */
    suspend fun checkConnection(): Resource<Boolean> {
        return try {
            val api = getApiService()
            val phone = secureStorage.getPhone() ?: "01700000000"
            val pairCode = secureStorage.getPairCode() ?: "0000-0000"
            val resp = api.pollOtp(OtpPollRequest(phone = phone, pairCode = pairCode))
            if (resp.isSuccessful) {
                Resource.Success(true, "Backend relay reachable (HTTP ${resp.code()})")
            } else {
                Resource.Error("Backend responded with error ${resp.code()}")
            }
        } catch (e: Exception) {
            Resource.Error("Connection test failed: ${e.localizedMessage ?: "Relay unreachable"}")
        }
    }

    fun disconnect() {
        secureStorage.clearPairing()
    }

    fun isPaired(): Boolean = secureStorage.isPaired()

    fun getMaskedPairKey(): String {
        val key = secureStorage.getPairCode() ?: return "Not Paired"
        return if (key.length >= 8) {
            "${key.take(4)}-****"
        } else {
            "****"
        }
    }

    fun getPairedPhone(): String = secureStorage.getPhone() ?: ""
}

