package com.ivac.assistant.core.data.api

import com.ivac.assistant.core.data.model.CompanionOtpPushRequest
import com.ivac.assistant.core.data.model.CompanionPairCompleteRequest
import com.ivac.assistant.core.data.model.CompanionPairCompleteResponse
import com.ivac.assistant.core.data.model.CompanionPairRequest
import com.ivac.assistant.core.data.model.CompanionPairResponse
import com.ivac.assistant.core.data.model.HealthCheckResponse
import com.ivac.assistant.core.data.model.OtpPollRequest
import com.ivac.assistant.core.data.model.OtpPollResponse
import com.ivac.assistant.core.data.model.OtpPushRequest
import com.ivac.assistant.core.data.model.OtpPushResponse
import com.ivac.assistant.core.data.model.PairVerifyRequest
import com.ivac.assistant.core.data.model.PairVerifyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface IvacApiService {

    /**
     * Root backend health probe endpoint (e.g. GET /health).
     * With baseUrl ending in /v1/, a leading slash "/health" resolves to the host root /health.
     */
    @GET("/health")
    suspend fun checkHealth(): Response<HealthCheckResponse>

    /**
     * API v1 health probe endpoint (e.g. GET /v1/health).
     */
    @GET("health")
    suspend fun checkV1Health(): Response<HealthCheckResponse>

    /**
     * Step 1: Initiate pairing with Chrome Extension using Client Key.
     * POST /v1/companion/pair/request
     */
    @POST("companion/pair/request")
    suspend fun requestPairing(
        @Body request: CompanionPairRequest
    ): Response<CompanionPairResponse>

    /**
     * Step 2: Complete pairing after extension approval and retrieve deviceToken.
     * POST /v1/companion/pair/complete
     */
    @POST("companion/pair/complete")
    suspend fun completePairing(
        @Body request: CompanionPairCompleteRequest
    ): Response<CompanionPairCompleteResponse>

    /**
     * Authenticated companion endpoint to push received OTP to relay store.
     * POST /v1/companion/otp/push
     * Requires: Authorization: Bearer <deviceToken>
     */
    @POST("companion/otp/push")
    suspend fun pushCompanionOtp(
        @Header("Authorization") authorization: String,
        @Body request: CompanionOtpPushRequest
    ): Response<OtpPushResponse>

    /**
     * Existing backend fallback endpoint to push received OTP to relay store.
     * POST /v1/otp/push
     */
    @POST("otp/push")
    suspend fun pushOtp(
        @Body request: OtpPushRequest
    ): Response<OtpPushResponse>

    /**
     * Existing backend fallback endpoint to poll OTP.
     * POST /v1/otp/poll
     */
    @POST("otp/poll")
    suspend fun pollOtp(
        @Body request: OtpPollRequest
    ): Response<OtpPollResponse>

    /**
     * Single-step pairing verification endpoint (for compatibility).
     * POST /v1/companion/pair
     */
    @POST("companion/pair")
    suspend fun verifyPairing(
        @Body request: PairVerifyRequest
    ): Response<PairVerifyResponse>
}
