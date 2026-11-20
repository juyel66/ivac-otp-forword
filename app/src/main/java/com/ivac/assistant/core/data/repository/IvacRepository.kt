package com.ivac.assistant.core.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.ivac.assistant.BuildConfig
import com.ivac.assistant.core.data.api.IvacApiService
import com.ivac.assistant.core.data.model.CompanionOtpPushRequest
import com.ivac.assistant.core.data.model.CompanionPairCompleteRequest
import com.ivac.assistant.core.data.model.CompanionPairRequest
import com.ivac.assistant.core.data.model.OtpPollRequest
import com.ivac.assistant.core.data.model.PairVerifyRequest
import com.ivac.assistant.core.data.network.NetworkClient
import com.ivac.assistant.core.data.preferences.PreferencesManager
import com.ivac.assistant.core.data.security.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    // Prevents duplicate raw radio broadcast spikes (within 3 seconds) while allowing rapid sequential OTPs
    private val processedOtpCache = ConcurrentHashMap<String, Long>()
    private val DEDUPLICATION_WINDOW_MS = 3 * 1000L

    suspend fun getApiService(): IvacApiService {
        val currentBaseUrl = preferencesManager.baseUrl.first()
        return NetworkClient.createApiService(currentBaseUrl)
    }

    suspend fun getBaseUrl(): String {
        return preferencesManager.baseUrl.first()
    }

    /**
     * Retrieves the stored device token or requests an updated one from the pairing API if needed.
     * Ensures all companion requests use genuine, backend-signed credentials.
     * Never logs actual secrets or tokens.
     */
    suspend fun getOrFetchDeviceToken(): String? {
        // 1. Primary: EncryptedSharedPreferences
        var storedToken = secureStorage.getAuthToken()

        // 2. Secondary backup: DataStore Preferences
        if (storedToken.isNullOrBlank()) {
            storedToken = preferencesManager.getBackupDeviceToken()
            if (!storedToken.isNullOrBlank()) {
                secureStorage.saveDeviceToken(storedToken)
                Log.i(TAG, "Device token recovered from persistent backup store [tokenPresent: true]")
            }
        }

        if (!storedToken.isNullOrBlank()) {
            Log.i(TAG, "Token retrieval status: [available: true]")
            return storedToken
        }

        // If paired but token is missing, automatically re-synchronize with backend
        val pairCode = secureStorage.getPairCode()
        if (pairCode.isNullOrBlank()) {
            Log.w(TAG, "Token retrieval status: [available: false, reason: not_paired]")
            return null
        }
        val cleanPhone = secureStorage.getPhone() ?: ""
        val deviceId = secureStorage.getDeviceId()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appVersion = BuildConfig.VERSION_NAME

        Log.w(TAG, "Token retrieval status: [available: false, reason: missing_in_storage] — attempting auto-sync")

        try {
            val api = getApiService()
            val req = CompanionPairRequest(
                clientKey = pairCode,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion
            )
            val resp = api.requestPairing(req)
            if (resp.isSuccessful && resp.body()?.ok == true) {
                val newToken = resp.body()?.effectiveToken
                if (!newToken.isNullOrBlank()) {
                    secureStorage.savePairing(pairCode, cleanPhone, newToken)
                    preferencesManager.saveBackupPairing(pairCode, cleanPhone, newToken)
                    Log.i(TAG, "Device authentication token successfully re-synchronized with backend [available: true]")
                    return newToken
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device token synchronization skipped: ${e.message}")
        }

        Log.e(TAG, "Token retrieval status: [available: false, reason: sync_failed]")
        return null
    }

    /**
     * Converts network exceptions into clear, actionable user messages.
     */
    fun formatNetworkError(e: Throwable, baseUrl: String = ""): String {
        return when (e) {
            is ConnectException ->
                "Connection refused: Cannot reach backend server at $baseUrl. Verify phone and laptop are on the same Wi-Fi network and port 5000 is open in firewall."
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

    private fun parseServerErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(errorBody).asJsonObject
            when {
                json.has("message") && !json.get("message").isJsonNull -> json.get("message").asString
                json.has("error") && !json.get("error").isJsonNull -> json.get("error").asString
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Formats HTTP error response codes into clear messages.
     */
    fun formatHttpError(code: Int, errorBody: String?): String {
        val serverMessage = parseServerErrorMessage(errorBody)
        return when (code) {
            400 -> {
                if (!serverMessage.isNullOrBlank()) {
                    "Bad Request (400): $serverMessage"
                } else {
                    "Bad Request (400): Invalid parameters sent to server. Please verify your Client Key."
                }
            }
            401 -> "Unauthorized (401): Invalid or expired device authentication token. Please re-pair your device."
            403 -> "Forbidden (403): Access denied. Device is not authorized for this action."
            404 -> {
                if (!serverMessage.isNullOrBlank()) {
                    "Endpoint not found (404): $serverMessage"
                } else {
                    "Endpoint not found (404): The requested route does not exist on this backend."
                }
            }
            500 -> {
                if (!serverMessage.isNullOrBlank()) {
                    "Server error (500): $serverMessage"
                } else {
                    "Server internal error (500): Check backend server logs."
                }
            }
            502, 503 -> "Server unavailable ($code): Backend server is temporarily unreachable or restarting."
            else -> {
                if (!serverMessage.isNullOrBlank()) {
                    "Server error ($code): $serverMessage"
                } else {
                    "Server error ($code): ${errorBody?.take(120) ?: "Unexpected response from server."}"
                }
            }
        }
    }

    /**
     * Syntactic validation of Extension Client Key.
     * Expects 8-character Base32 key (e.g. 6988-P97K).
     * Accepts 8 to 32 characters Base32 (ABCDEFGHJKLMNPQRSTUVWXYZ23456789).
     */
    fun isValidKeySyntax(key: String): Boolean {
        val clean = key.trim().replace("-", "").uppercase()
        if (clean.length < 8 || clean.length > 32) return false
        val regex = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{8,32}$")
        return regex.matches(clean)
    }

    fun formatPairKey(raw: String): String {
        val clean = raw.trim().replace("-", "").uppercase()
        return if (clean.length == 8) {
            "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
        } else if (clean.length in 8..32) {
            clean.chunked(4).joinToString("-")
        } else {
            raw.trim().uppercase()
        }
    }

    /**
     * Verifies pairing key against backend and acquires cryptographically signed companion deviceToken.
     * Follows rule: Do not consider a device paired unless a genuine deviceToken is returned and saved.
     * Phone is optional metadata; Client Key is the primary identity.
     * Never logs actual secrets or tokens.
     */
    suspend fun pairWithExtension(rawKey: String, phone: String = ""): Resource<Boolean> {
        val pairCode = formatPairKey(rawKey)
        if (!isValidKeySyntax(pairCode)) {
            Log.w(TAG, "Pairing rejected: Invalid key format syntax")
            return Resource.Error("Invalid key format. Expected 8-character code (e.g. 6988-P97K).")
        }

        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.isNotEmpty() && cleanPhone.length < 8) {
            Log.w(TAG, "Pairing rejected: Phone number must be at least 8 digits if provided")
            return Resource.Error("Please enter a valid mobile number or leave blank.")
        }

        val baseUrl = getBaseUrl()
        val maskedKey = if (pairCode.length >= 4) "${pairCode.take(4)}-****" else "****"

        try {
            val api = getApiService()
            val deviceId = secureStorage.getDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val appVersion = BuildConfig.VERSION_NAME

            Log.i(TAG, "Initiating companion pairing flow for key [$maskedKey] on deviceId [$deviceId]...")

            var acquiredToken: String? = null
            var successMessage = "Paired successfully with Chrome Extension"

            // 1. Primary companion handshake: POST /v1/companion/pair/request
            val pairReq = CompanionPairRequest(
                clientKey = pairCode,
                deviceId = deviceId,
                deviceName = deviceName,
                appVersion = appVersion
            )

            var pairRequestHttpCode: Int? = null

            try {
                val pairResp = api.requestPairing(pairReq)
                pairRequestHttpCode = pairResp.code()
                if (pairResp.isSuccessful && pairResp.body()?.ok == true) {
                    val pairBody = pairResp.body()!!
                    val initialToken = pairBody.effectiveToken
                    val pairingId = pairBody.effectivePairingId

                    Log.i(TAG, "Pairing request SUCCESS: HTTP ${pairResp.code()} [pairingId: ${pairingId ?: "none"}, tokenPresent: ${!initialToken.isNullOrBlank()}]")

                    if (!initialToken.isNullOrBlank()) {
                        acquiredToken = initialToken
                        successMessage = pairBody.message ?: successMessage
                    }

                    // Complete pairing handshake with phone number binding if pairingId is returned or present
                    val completeReq = CompanionPairCompleteRequest(
                        clientKey = pairCode,
                        deviceId = deviceId,
                        pairingId = pairingId,
                        phone = cleanPhone
                    )
                    try {
                        val completeResp = api.completePairing(completeReq)
                        if (completeResp.isSuccessful && completeResp.body()?.ok == true) {
                            val completeToken = completeResp.body()?.effectiveToken
                            Log.i(TAG, "Pairing completion SUCCESS: HTTP ${completeResp.code()} [tokenPresent: ${!completeToken.isNullOrBlank()}]")
                            if (!completeToken.isNullOrBlank()) {
                                acquiredToken = completeToken
                            }
                            successMessage = completeResp.body()?.message ?: successMessage
                        } else {
                            Log.w(TAG, "Pairing completion non-200: HTTP ${completeResp.code()} [using initial token if available]")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pairing completion network notice: ${e.message} [initial token present: ${!acquiredToken.isNullOrBlank()}]")
                    }

                } else if (pairResp.code() != 404) {
                    val err = formatHttpError(pairResp.code(), pairResp.errorBody()?.string())
                    Log.e(TAG, "Pairing request FAILED: HTTP ${pairResp.code()} - $err")
                    return Resource.Error(err, pairResp.code())
                }
            } catch (e: Exception) {
                if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                    val netErr = formatNetworkError(e, baseUrl)
                    Log.e(TAG, "Pairing request connection error: $netErr")
                    return Resource.Error(netErr)
                }
                Log.w(TAG, "Pairing request probe exception: ${e.message}")
            }

            // 2. Compatibility probe: POST /v1/companion/pair/verify (or /v1/companion/pair)
            if (acquiredToken.isNullOrBlank()) {
                Log.i(TAG, "Probing companion pair verification endpoint...")
                try {
                    val legacyPairReq = PairVerifyRequest(
                        pairCode = pairCode,
                        phone = cleanPhone,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        appVersion = appVersion
                    )
                    var legacyResp = api.verifyPairing(legacyPairReq)
                    if (legacyResp.code() == 404) {
                        legacyResp = api.verifyPairingAlias(legacyPairReq)
                    }

                    if (legacyResp.isSuccessful && legacyResp.body()?.ok == true) {
                        val verifyToken = legacyResp.body()?.effectiveToken
                        Log.i(TAG, "Pairing verification SUCCESS: HTTP ${legacyResp.code()} [tokenPresent: ${!verifyToken.isNullOrBlank()}]")
                        if (!verifyToken.isNullOrBlank()) {
                            acquiredToken = verifyToken
                            successMessage = legacyResp.body()?.message ?: successMessage
                        }
                    } else if (legacyResp.code() != 404) {
                        val err = formatHttpError(legacyResp.code(), legacyResp.errorBody()?.string())
                        Log.e(TAG, "Pairing verification FAILED: HTTP ${legacyResp.code()} - $err")
                        return Resource.Error(err, legacyResp.code())
                    } else if (pairRequestHttpCode == 404) {
                        // BOTH requestPairing AND verifyPairing returned 404!
                        val err = "Endpoint not found (404): Companion pairing routes are not available on this server ($baseUrl). Please check the Server URL in Settings and ensure the backend server with companion support is running."
                        Log.e(TAG, err)
                        return Resource.Error(err, 404)
                    }
                } catch (e: Exception) {
                    if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException) {
                        val netErr = formatNetworkError(e, baseUrl)
                        Log.e(TAG, "Pairing verification connection error: $netErr")
                        return Resource.Error(netErr)
                    }
                    Log.w(TAG, "Pairing verification probe exception: ${e.message}")
                }
            }

            // 3. Token Storage & Verification
            if (!acquiredToken.isNullOrBlank()) {
                val secSaved = secureStorage.savePairing(pairCode, cleanPhone, acquiredToken)
                preferencesManager.saveBackupPairing(pairCode, cleanPhone, acquiredToken)
                preferencesManager.recordForwardingEvent("✓ Device paired successfully with companion token", false)

                val verifiedStoredToken = secureStorage.getAuthToken()
                val isVerified = !verifiedStoredToken.isNullOrBlank() && verifiedStoredToken == acquiredToken
                if (secSaved && isVerified) {
                    Log.i(TAG, "Token storage SUCCESS: Device token securely stored and verified in storage [tokenPresent: true]")
                    return Resource.Success(true, successMessage)
                } else {
                    Log.e(TAG, "Token storage FAILED: SecureStorage commit failed or verification mismatch [tokenPresent: false]")
                    return Resource.Error("Failed to commit device authentication token to secure storage. Please retry.")
                }
            }

            // Strict security: Reject pairing if no genuine deviceToken was provided by the backend
            Log.e(TAG, "Token storage FAILED: No valid companion deviceToken returned by backend pairing routes")
            return Resource.Error("Server did not issue a valid companion authentication token. Please verify your Client Key and connection.")

        } catch (e: Exception) {
            val netErr = formatNetworkError(e, baseUrl)
            Log.e(TAG, "Pairing flow exception: $netErr")
            return Resource.Error(netErr)
        }
    }

    /**
     * Forwards an extracted OTP to the backend relay via the authenticated companion endpoint:
     * POST /v1/companion/otp/push with Authorization: Bearer <deviceToken>.
     *
     * SECURITY & PRIVACY:
     * - Strictly authenticated with the paired device's signed deviceToken.
     * - NO unauthenticated legacy fallback.
     * - Never logs OTP values, deviceToken, or personal information.
     * - Enforces in-memory deduplication (2-minute window).
     */
    suspend fun forwardOtp(
        extractedOtp: String,
        sender: String = "IVAC_BD",
        receivedAt: Long = System.currentTimeMillis()
    ): Resource<String> {
        if (!secureStorage.isPaired()) {
            Log.w(TAG, "OTP forwarding skipped: Device is not paired")
            preferencesManager.recordForwardingEvent("Ignored: Device not paired", false)
            return Resource.Error("Device is not paired with any Chrome Extension.")
        }

        val deviceToken = getOrFetchDeviceToken()
        if (deviceToken.isNullOrBlank()) {
            Log.e(TAG, "Backend OTP push aborted: Missing deviceToken authentication")
            preferencesManager.recordForwardingEvent("Failed: Missing deviceToken authentication", false)
            return Resource.Error("Missing authentication token. Please re-pair your device.")
        }

        if (secureStorage.getPairCode().isNullOrBlank()) {
            return Resource.Error("Missing pairing code.")
        }
        val phone = secureStorage.getPhone() ?: ""

        val cleanOtp = extractedOtp.replace(Regex("[^0-9]"), "")
        if (cleanOtp.length != 6) {
            Log.w(TAG, "OTP forwarding rejected: Expected 6 digits but got ${cleanOtp.length}")
            preferencesManager.recordForwardingEvent("Invalid SMS ignored: OTP must be exactly 6 digits", false)
            return Resource.Error("Invalid OTP format. Must be exactly 6 digits.")
        }

        // Duplicate delivery protection (per sender + OTP within 2-minute window)
        val deduplicationKey = "$sender:$cleanOtp"
        val now = System.currentTimeMillis()
        val lastSeen = processedOtpCache[deduplicationKey]

        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.i(TAG, "Duplicate IVAC OTP skipped: Already forwarded within 2 minutes")
            preferencesManager.recordForwardingEvent("Ignored: Duplicate IVAC SMS within 2 minutes", false)
            return Resource.Success("Duplicate OTP detected — already forwarded", "Duplicate skipped")
        }

        // Evict expired entries from deduplication cache
        processedOtpCache.entries.removeIf { (now - it.value) > DEDUPLICATION_WINDOW_MS }

        val baseUrl = getBaseUrl()

        try {
            val api = getApiService()

            // Payload matches backend API contract: { otp, phone, sender, receivedAt }
            val companionRequest = CompanionOtpPushRequest(
                otp = cleanOtp,
                phone = phone,
                sender = sender,
                receivedAt = receivedAt
            )

            // Diagnostic Log 4: Backend request started (safe: no OTP or token logged)
            Log.i(TAG, "Backend request started -> POST companion/otp/push [Auth: Bearer token present, sender: $sender, digits: 6]")

            val companionResp = api.pushCompanionOtp("Bearer $deviceToken", companionRequest)

            // Diagnostic Log 5: HTTP response status
            Log.i(TAG, "Backend HTTP response status: ${companionResp.code()} [Success: ${companionResp.isSuccessful}]")

            if (companionResp.isSuccessful && companionResp.body()?.ok == true) {
                val relayId = companionResp.body()?.id ?: "confirmed"
                processedOtpCache[deduplicationKey] = now
                // Diagnostic Log 6: Backend push success
                Log.i(TAG, "Backend push SUCCESS: OTP accepted by relay store [ID: $relayId]")
                preferencesManager.recordForwardingEvent("✓ OTP accepted by backend relay (HTTP 200, ID: $relayId)", true)
                return Resource.Success("OTP forwarded successfully to backend", companionResp.body()?.message)
            } else {
                val errDetail = companionResp.errorBody()?.string()
                val errMsg = formatHttpError(companionResp.code(), errDetail)
                // Diagnostic Log 6: Backend push failure
                Log.e(TAG, "Backend push FAILED: HTTP ${companionResp.code()} - $errMsg")
                preferencesManager.recordForwardingEvent("✕ Backend relay rejected OTP: $errMsg", false)
                return Resource.Error(errMsg, companionResp.code())
            }

        } catch (e: Exception) {
            val errorMsg = formatNetworkError(e, baseUrl)
            Log.e(TAG, "Backend OTP push network error: $errorMsg")
            preferencesManager.recordForwardingEvent("✕ Network error: $errorMsg", false)
            return Resource.Error(errorMsg)
        }
    }

    /**
     * Checks backend reachability via health endpoint or poll probe.
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
            } catch (_: Exception) {}

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                preferencesManager.clearBackupPairing()
                preferencesManager.recordForwardingEvent("Device disconnected", false)
            } catch (_: Exception) {}
        }
        Log.i(TAG, "Device pairing cleared from secure storage and backup preferences")
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

    companion object {
        private const val TAG = "IvacRepository"
    }
}
