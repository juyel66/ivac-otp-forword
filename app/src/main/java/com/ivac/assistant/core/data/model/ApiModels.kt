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
 */
data class HealthCheckResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String? = null,
    @SerializedName("timestamp") val timestamp: Long? = null
)

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

