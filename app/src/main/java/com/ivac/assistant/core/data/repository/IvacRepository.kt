package com.ivac.assistant.core.data.repository

import android.content.Context
import android.os.Build
import com.ivac.assistant.BuildConfig
import com.ivac.assistant.core.data.api.IvacApiService
import com.ivac.assistant.core.data.model.CompanionOtpPushRequest
import com.ivac.assistant.core.data.model.CompanionPairCompleteRequest
import com.ivac.assistant.core.data.model.CompanionPairRequest
import com.ivac.assistant.core.data.model.OtpPollRequest
import com.ivac.assistant.core.data.model.OtpPushRequest
import com.ivac.assistant.core.data.model.PairVerifyRequest
import com.ivac.assistant.core.data.network.NetworkClient
import com.ivac.assistant.core.data.preferences.PreferencesManager
import com.ivac.assistant.core.data.security.SecureStorage
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

    suspend fun getApiService(): IvacApiService {
        val currentBaseUrl = preferencesManager.baseUrl.first()
        return NetworkClient.createApiService(currentBaseUrl)
    }

    suspend fun getBaseUrl(): String {
        return preferencesManager.baseUrl.first()
    }

    /**
     * Converts network exceptions into clear, actionable user messages.
     */
    fun formatNetworkError(e: Throwable, baseUrl: String = ""): String {
        return when (e) {
            is ConnectException ->
                "Connection refused: Cannot reach backend server at $baseUrl. Verify your phone and laptop are on the same Wi-Fi network and port 5000 is open in firewall."
            is SocketTimeoutException ->
                "Connection timed out: Backend server took too long to respond. Check network signal and server load."
            is UnknownHostException ->
                "Cannot resolve host: Unable to reach $baseUrl. Check the IP address and your Wi-Fi connection."
            is IOException -> {
                if (e.message?.contains("Cleartext", ignoreCase = true) == true) {
                    "Cleartext HTTP blocked: Android blocked unencrypted HTTP traffic. Install the debug APK with cleartext enabled."
                } else {
                    "Network error: ${e.localizedMessage ?: "Failed to communicate with backend."}"
                }
            }
            else -> "Unexpected error: ${e.localizedMessage ?: "An unknown error occurred."}"
        }
    }

    /**
     * Formats HTTP error response codes into clear messages.
     */
    fun formatHttpError(code: Int, errorBody: String?): String {
        return when (code) {
            400 -> "Bad Request (400): ${errorBody?.take(120) ?: "Invalid parameters sent to server."}"
            401 -> "Unauthorized (401): Invalid or expired device authentication token. Please re-pair your device."
            403 -> "Forbidden (403): Access denied. Device is not authorized for this action."
            404 -> "Endpoint not found (404): The requested route does not exist on this backend."
            500 -> "Server internal error (500): Check backend server logs."
            502, 503 -> "Server unavailable ($code): Backend server is temporarily unreachable or restarting."
            else -> "Server error ($code): ${errorBody?.take(120) ?: "Unexpected response from server."}"
        }
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

        val baseUrl = getBaseUrl()

        try {
            val api = getApiService()
            val deviceId = secureStorage.getDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val appVersion = BuildConfig.VERSION_NAME

            // 1. Primary companion handshake: POST /v1/companion/pair/request
            val pairReq = CompanionPairRequest(
                clientKey = pairCode,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion
            )

            try {
                val pairResp = api.requestPairing(pairReq)
                if (pairResp.isSuccessful && pairResp.body()?.ok == true) {
                    val token = pairResp.body()?.deviceToken
                    if (!token.isNullOrBlank()) {
                        secureStorage.savePairing(pairCode, cleanPhone, token)
                        preferencesManager.recordForwardingEvent("Device paired successfully via companion API", false)
                        return Resource.Success(true, pairResp.body()?.message ?: "Paired successfully")
                    } else if (pairResp.body()?.pairingId != null) {
                        // Complete pairing handshake if required
                        val completeReq = CompanionPairCompleteRequest(
                            clientKey = pairCode,
                            deviceId = deviceId,
                            pairingId = pairResp.body()?.pairingId,
                            phone = cleanPhone
                        )
                        val completeResp = api.completePairing(completeReq)
                        if (completeResp.isSuccessful && completeResp.body()?.ok == true) {
                            secureStorage.savePairing(pairCode, cleanPhone, completeResp.body()?.deviceToken)
                            preferencesManager.recordForwardingEvent("Device paired successfully", false)
                            return Resource.Success(true, completeResp.body()?.message ?: "Paired successfully")
                        }
                    }
                } else if (pairResp.code() != 404) {
                    val err = formatHttpError(pairResp.code(), pairResp.errorBody()?.string())
                    return Resource.Error(err, pairResp.code())
                }
            } catch (e: Exception) {
                // If endpoint doesn't exist (e.g. 404) or network glitch, fall back below
                if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    return Resource.Error(formatNetworkError(e, baseUrl))
                }
            }

            // 2. Compatibility probe: POST /v1/companion/pair (single-step)
            try {
                val legacyPairReq = PairVerifyRequest(
                    pairCode = pairCode,
                    phone = cleanPhone,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    appVersion = appVersion
                )
                val legacyResp = api.verifyPairing(legacyPairReq)
                if (legacyResp.isSuccessful && legacyResp.body()?.ok == true) {
                    secureStorage.savePairing(pairCode, cleanPhone, legacyResp.body()?.token)
                    preferencesManager.recordForwardingEvent("Device paired successfully", false)
                    return Resource.Success(true, legacyResp.body()?.message ?: "Paired successfully")
                } else if (legacyResp.code() != 404) {
                    val err = formatHttpError(legacyResp.code(), legacyResp.errorBody()?.string())
                    return Resource.Error(err, legacyResp.code())
                }
            } catch (e: Exception) {
                if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    return Resource.Error(formatNetworkError(e, baseUrl))
                }
            }

            // 3. Fallback verification against existing backend relay:
            // Test /v1/otp/poll probe with pairCode & phone
            val pollResp = api.pollOtp(OtpPollRequest(phone = cleanPhone, pairCode = pairCode, since = System.currentTimeMillis()))
            if (pollResp.isSuccessful && pollResp.body()?.ok == true) {
                secureStorage.savePairing(pairCode, cleanPhone, null)
                preferencesManager.recordForwardingEvent("Paired with existing backend relay", false)
                return Resource.Success(true, "Paired successfully with relay server.")
            } else {
                val errorMsg = formatHttpError(pollResp.code(), pollResp.errorBody()?.string())
                return Resource.Error("Server verification failed: $errorMsg", pollResp.code())
            }

        } catch (e: Exception) {
            return Resource.Error(formatNetworkError(e, baseUrl))
        }
    }

    /**
     * Forwards an extracted OTP to the paired Chrome Extension via the backend relay.
     * Sanitizes inputs, verifies pairing and deviceToken authentication,
     * protects against duplicate delivery, and records event status.
     *
     * SECURITY:
     * - Never sends an unauthenticated OTP request.
     * - Never logs OTP digits.
     * - Dispatches to POST /v1/companion/otp/push with Authorization: Bearer <deviceToken>.
     * - Falls back to POST /v1/otp/push if companion endpoint is not active.
     */
    suspend fun forwardOtp(
        extractedOtp: String,
        sender: String = "IVAC_BD",
        receivedAt: Long = System.currentTimeMillis()
    ): Resource<String> {
        if (!secureStorage.isPaired()) {
            preferencesManager.recordForwardingEvent("Ignored: Device not paired", false)
            return Resource.Error("Device is not paired with any Chrome Extension.")
        }

        val deviceToken = secureStorage.getDeviceToken()
        if (deviceToken.isNullOrBlank()) {
            preferencesManager.recordForwardingEvent("Failed: Missing deviceToken authentication", false)
            return Resource.Error("Missing authentication token. Please pair your device first.")
        }

        val pairCode = secureStorage.getPairCode() ?: return Resource.Error("Missing pairing code.")
        val phone = secureStorage.getPhone() ?: return Resource.Error("Missing paired phone number.")

        val cleanOtp = extractedOtp.replace(Regex("[^0-9]"), "")
        if (cleanOtp.length != 6) {
            preferencesManager.recordForwardingEvent("Invalid SMS ignored: OTP must be exactly 6 digits", false)
            return Resource.Error("Invalid OTP format. Must be exactly 6 digits.")
        }

        // Duplicate delivery protection (per sender + OTP):
        // Prevents pushing the exact same OTP within the deduplication window
        val deduplicationKey = "$sender:$cleanOtp"
        val now = System.currentTimeMillis()
        val lastSeen = processedOtpCache[deduplicationKey]

        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            preferencesManager.recordForwardingEvent("Ignored: Duplicate IVAC SMS within 2 minutes", false)
            return Resource.Success("Duplicate OTP detected — already forwarded", "Duplicate skipped")
        }

        // Evict expired entries from deduplication cache
        processedOtpCache.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }

        val baseUrl = getBaseUrl()

        try {
            val api = getApiService()

            // 1. Primary: Authenticated companion push endpoint
            // POST /v1/companion/otp/push with Authorization: Bearer <deviceToken>
            val companionRequest = CompanionOtpPushRequest(
                otp = cleanOtp,
                phone = phone,
                sender = sender,
                receivedAt = receivedAt
            )

            try {
                val companionResp = api.pushCompanionOtp("Bearer $deviceToken", companionRequest)
                if (companionResp.isSuccessful && companionResp.body()?.ok == true) {
                    processedOtpCache[deduplicationKey] = now
                    preferencesManager.recordForwardingEvent("OTP forwarded successfully", true)
                    return Resource.Success("OTP forwarded successfully", companionResp.body()?.message)
                } else if (companionResp.code() in listOf(401, 403)) {
                    val errMsg = formatHttpError(companionResp.code(), companionResp.errorBody()?.string())
                    preferencesManager.recordForwardingEvent("Failed: $errMsg", false)
                    return Resource.Error(errMsg, companionResp.code())
                } else if (companionResp.code() != 404) {
                    val errMsg = formatHttpError(companionResp.code(), companionResp.errorBody()?.string())
                    preferencesManager.recordForwardingEvent("Failed: $errMsg", false)
                    return Resource.Error(errMsg, companionResp.code())
                }
            } catch (e: Exception) {
                if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    val netErr = formatNetworkError(e, baseUrl)
                    preferencesManager.recordForwardingEvent("Failed: $netErr", false)
                    return Resource.Error(netErr)
                }
            }

            // 2. Fallback: Existing backend endpoint POST /v1/otp/push
            val request = OtpPushRequest(
                phone = phone,
                pairCode = pairCode,
                otp = cleanOtp
            )

            val response = api.pushOtp(request)
            if (response.isSuccessful && response.body()?.ok == true) {
                processedOtpCache[deduplicationKey] = now
                preferencesManager.recordForwardingEvent("OTP forwarded successfully", true)
                return Resource.Success("OTP forwarded successfully", response.body()?.message)
            } else {
                val errMsg = formatHttpError(response.code(), response.errorBody()?.string())
                preferencesManager.recordForwardingEvent("Failed: $errMsg", false)
                return Resource.Error(errMsg, response.code())
            }
        } catch (e: Exception) {
            val errorMsg = formatNetworkError(e, baseUrl)
            preferencesManager.recordForwardingEvent("Network error: $errorMsg", false)
            return Resource.Error(errorMsg)
        }
    }

    /**
     * Checks backend reachability via health endpoint or poll endpoint.
     */
    suspend fun checkConnection(): Resource<Boolean> {
        val baseUrl = getBaseUrl()
        return try {
            val api = getApiService()

            // 1. Try root health check: GET /health
            try {
                val healthResp = api.checkHealth()
                if (healthResp.isSuccessful && healthResp.body()?.isHealthy == true) {
                    val dbStatus = healthResp.body()?.database ?: "connected"
                    return Resource.Success(true, "Backend reachable (HTTP 200, DB: $dbStatus)")
                }
            } catch (_: Exception) {
                // If root /health is unavailable, try /v1/health
            }

            // 2. Try v1 health check: GET /v1/health
            try {
                val v1HealthResp = api.checkV1Health()
                if (v1HealthResp.isSuccessful && v1HealthResp.body()?.isHealthy == true) {
                    val dbStatus = v1HealthResp.body()?.database ?: "connected"
                    return Resource.Success(true, "Backend v1 reachable (HTTP 200, DB: $dbStatus)")
                }
            } catch (_: Exception) {}

            // 3. Fallback: probe poll endpoint
            val phone = secureStorage.getPhone() ?: "01700000000"
            val pairCode = secureStorage.getPairCode() ?: "0000-0000"
            val resp = api.pollOtp(OtpPollRequest(phone = phone, pairCode = pairCode))
            if (resp.isSuccessful) {
                Resource.Success(true, "Backend relay reachable (HTTP ${resp.code()})")
            } else {
                Resource.Error(formatHttpError(resp.code(), resp.errorBody()?.string()), resp.code())
            }
        } catch (e: Exception) {
            Resource.Error(formatNetworkError(e, baseUrl))
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
