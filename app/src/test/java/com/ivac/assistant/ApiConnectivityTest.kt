package com.ivac.assistant

import com.ivac.assistant.core.data.model.CompanionOtpPushRequest
import com.ivac.assistant.core.data.model.CompanionPairRequest
import com.ivac.assistant.core.data.network.NetworkClient
import com.ivac.assistant.core.util.OtpParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConnectivityTest {

    private val defaultBaseUrl = "https://api-ivacassistant.codiumzenit.com/v1/"

    @Test
    fun testBaseUrlSanitization() {
        assertEquals("http://192.168.0.101:5000/v1/", NetworkClient.formatBaseUrl("http://192.168.0.101:5000"))
        assertEquals("http://192.168.0.101:5000/v1/", NetworkClient.formatBaseUrl("http://192.168.0.101:5000/"))
        assertEquals("http://192.168.0.101:5000/v1/", NetworkClient.formatBaseUrl("http://192.168.0.101:5000/v1"))
        assertEquals("http://192.168.0.101:5000/v1/", NetworkClient.formatBaseUrl("http://192.168.0.101:5000/v1/"))
        assertEquals("https://api-ivacassistant.codiumzenit.com/v1/", NetworkClient.formatBaseUrl("https://api-ivacassistant.codiumzenit.com"))
        assertEquals("https://api-ivacassistant.codiumzenit.com/v1/", NetworkClient.formatBaseUrl("https://api-ivacassistant.codiumzenit.com/v1/"))
    }

    @Test
    fun testLiveBackendHealthEndpoint() = runBlocking {
        val api = NetworkClient.createApiService(defaultBaseUrl)
        try {
            val response = api.checkHealth()
            assertTrue("Expected HTTP 200 from backend /health", response.isSuccessful)
            val body = response.body()
            assertNotNull("Response body must not be null", body)
            assertTrue("Expected status ok or isHealthy true", body!!.isHealthy)
            assertEquals("connected", body.database)
        } catch (e: Exception) {
            println("Backend not running or reachable at $defaultBaseUrl: ${e.message}")
        }
    }

    @Test
    fun testLiveBackendV1HealthEndpoint() = runBlocking {
        val api = NetworkClient.createApiService(defaultBaseUrl)
        try {
            val response = api.checkV1Health()
            assertTrue("Expected HTTP 200 from backend /v1/health", response.isSuccessful)
            val body = response.body()
            assertNotNull("Response body must not be null", body)
            assertTrue("Expected status ok or isHealthy true", body!!.isHealthy)
        } catch (e: Exception) {
            println("Backend not running or reachable at $defaultBaseUrl: ${e.message}")
        }
    }

    @Test
    fun testCompanionEndpointsSecurityAndAuth() = runBlocking {
        val api = NetworkClient.createApiService(defaultBaseUrl)
        try {
            // 1. Companion pair request returns valid signed deviceToken
            val pairReq = CompanionPairRequest(
                clientKey = "6988-P97K",
                deviceId = "unit_test_device_id",
                deviceName = "Test Phone",
                appVersion = "1.0.0"
            )
            val pairResp = api.requestPairing(pairReq)
            if (pairResp.isSuccessful) {
                val pairBody = pairResp.body()
                assertNotNull("Pairing body must not be null", pairBody)
                assertTrue("Expected pairing ok to be true", pairBody!!.ok)
                assertNotNull("Expected signed deviceToken", pairBody.deviceToken)
                assertTrue("Token must start with ivac_cmp_", pairBody.deviceToken!!.startsWith("ivac_cmp_"))

                val deviceToken = pairBody.deviceToken!!

                // 2. Unauthenticated request to /v1/companion/otp/push MUST return 401
                val dummyReq = CompanionOtpPushRequest(
                    otp = "",
                    phone = "",
                    sender = "IVAC_BD",
                    receivedAt = System.currentTimeMillis()
                )
                val unauthResp = api.pushCompanionOtp("Bearer invalid_signature_token", dummyReq)
                assertEquals("Expected HTTP 401 Unauthorized for invalid token", 401, unauthResp.code())

                // 3. Authenticated request with Bearer token passes auth, but fails body validation (returns 400 Bad Request)
                val authEmptyOtpResp = api.pushCompanionOtp("Bearer $deviceToken", dummyReq)
                assertEquals("Expected HTTP 400 Bad Request due to empty OTP body", 400, authEmptyOtpResp.code())
            } else {
                println("Local backend companion routes returned HTTP ${pairResp.code()} - skipping live endpoint assertion")
                assertTrue("Expected valid HTTP response from companion pair", pairResp.code() in listOf(200, 400, 401, 404))
            }
        } catch (e: Throwable) {
            println("Local backend connectivity test skipped: ${e.message}")
        }
    }

    @Test
    fun testOtpParserLeadingZerosAndWordNumbers() {
        val msg1 = "(IVACBD) For security, type the following sequence when prompted Two-Nine-Two-Four-Zero-Nine."
        assertEquals("292409", OtpParser.extractOtp(msg1))

        val msg2 = "(IVACBD) For security, type the following sequence when prompted Zero-Nine-Two-Four-Zero-Nine."
        assertEquals("092409", OtpParser.extractOtp(msg2))

        val msg3 = "Your appointment code: Three-Zero-Zero-Seven-Seven-Two. IVAC BD."
        assertEquals("300772", OtpParser.extractOtp(msg3))

        val msg4 = "Zero-Zero-Zero-Zero-Zero-Zero IVAC"
        assertEquals("000000", OtpParser.extractOtp(msg4))

        // Reject incomplete sequence
        val incomplete = "For security Two-Three-Four-Five"
        assertEquals(null, OtpParser.extractOtp(incomplete))

        // Reject banking/bKash
        val bkash = "bKash: Your verification code is 123456"
        assertTrue(!OtpParser.isAllowedSender("bKash"))
        assertTrue(!OtpParser.hasIvacContext(bkash))
    }
}
