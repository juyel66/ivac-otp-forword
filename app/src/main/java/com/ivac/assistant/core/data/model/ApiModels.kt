package com.ivac.assistant.core.data.model

import com.google.gson.annotations.SerializedName

/**
 * Standard payload for pushing an extracted OTP to the backend relay.
 * Matches existing backend POST /v1/otp/push contract.
 */
data class OtpPushRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("pairCode") val pairCode: String,
    @SerializedName("otp") val otp: String
)

/**
 * Secure payload for pushing an extracted OTP to companion endpoint:
 * POST /v1/companion/otp/push
 */
data class CompanionOtpPushRequest(
    @SerializedName("otp") val otp: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("sender") val sender: String,
    @SerializedName("receivedAt") val receivedAt: Long
)

/**
 * Response returned by backend POST /v1/otp/push.
 */
data class OtpPushResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("id") val id: String? = null
)

/**
 * Request payload for polling OTP (used by extension and optional test verification).
 */
data class OtpPollRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("pairCode") val pairCode: String,
    @SerializedName("since") val since: Long? = null
)

/**
 * Response returned by backend POST /v1/otp/poll.
 */
data class OtpPollResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("otp") val otp: String?,
    @SerializedName("message") val message: String? = null
)

/**
 * Companion pairing initialization request: POST /v1/companion/pair/request
 */
data class CompanionPairRequest(
    @SerializedName("clientKey") val clientKey: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("appVersion") val appVersion: String
)

/**
 * Companion pairing initialization response: POST /v1/companion/pair/request
 */
data class CompanionPairResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("pairingId") val pairingId: String? = null,
    @SerializedName("challenge") val challenge: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("deviceToken") val deviceToken: String? = null
)

/**
 * Companion pairing completion request: POST /v1/companion/pair/complete
 */
data class CompanionPairCompleteRequest(
    @SerializedName("clientKey") val clientKey: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("pairingId") val pairingId: String? = null,
    @SerializedName("phone") val phone: String? = null
)

/**
 * Companion pairing completion response: POST /v1/companion/pair/complete
 */
data class CompanionPairCompleteResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("deviceToken") val deviceToken: String? = null
)

/**
 * Proposed secure pairing verification request.
 * Allows companion app to verify extension pairing with device identity.
 */
data class PairVerifyRequest(
    @SerializedName("pairCode") val pairCode: String,
    @SerializedName("phone") val phone: String?,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("appVersion") val appVersion: String
)

/**
 * Proposed pairing verification response.
 */
data class PairVerifyResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("paired") val paired: Boolean = false,
    @SerializedName("token") val token: String? = null
)

/**
 * Health check / ping response for verifying relay connectivity.
 * Compatible with both root GET /health (status: "ok") and GET /v1/health (ok: true).
 */
data class HealthCheckResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("database") val database: String? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("service") val service: String? = null,
    @SerializedName("message") val message: String? = null
) {
    val isHealthy: Boolean
        get() = ok || status.equals("ok", ignoreCase = true)
}

/**
 * UI State representation of the last forwarding activity.
 */
enum class ForwardingStatus {
    IDLE,
    FORWARDING,
    SUCCESS,
    FAILED,
    IGNORED_DUPLICATE,
    IGNORED_UNMATCHED
}

