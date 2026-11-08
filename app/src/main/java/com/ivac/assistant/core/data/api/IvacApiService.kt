package com.ivac.assistant.core.data.api

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
import retrofit2.http.POST

interface IvacApiService {

    /**
     * Existing backend endpoint to push received OTP to relay store.
     */
    @POST("v1/otp/push")
    suspend fun pushOtp(
        @Body request: OtpPushRequest
    ): Response<OtpPushResponse>

    /**
     * Existing backend endpoint to poll OTP (used for verification/testing).
     */
    @POST("v1/otp/poll")
    suspend fun pollOtp(
        @Body request: OtpPollRequest
    ): Response<OtpPollResponse>

    /**
     * Proposed endpoint for verifying pairing between mobile app and extension client key.
     */
    @POST("v1/companion/pair")
    suspend fun verifyPairing(
        @Body request: PairVerifyRequest
    ): Response<PairVerifyResponse>

    /**
     * Backend health probe endpoint to ensure relay connectivity.
     */
    @GET("v1/health")
    suspend fun checkHealth(): Response<HealthCheckResponse>
}

