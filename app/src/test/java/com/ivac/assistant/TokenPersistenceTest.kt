package com.ivac.assistant

import com.ivac.assistant.core.data.model.CompanionPairCompleteResponse
import com.ivac.assistant.core.data.model.CompanionPairResponse
import com.ivac.assistant.core.data.model.PairVerifyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPersistenceTest {

    private val sampleIvacToken = "ivac_cmp_eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.signature"

    @Test
    fun testCompanionPairResponseEffectiveTokenExtraction() {
        // Standard deviceToken field
        val respWithDeviceToken = CompanionPairResponse(
            ok = true,
            pairingId = "pair_12345",
            deviceToken = sampleIvacToken
        )
        assertEquals(sampleIvacToken, respWithDeviceToken.effectiveToken)

        // Alternate token field fallback
        val respWithTokenField = CompanionPairResponse(
            ok = true,
            pairingId = "pair_12345",
            token = sampleIvacToken
        )
        assertEquals(sampleIvacToken, respWithTokenField.effectiveToken)

        // Empty token
        val respEmpty = CompanionPairResponse(ok = true, deviceToken = "", token = null)
        assertNull(respEmpty.effectiveToken)
    }

    @Test
    fun testCompanionPairCompleteResponseEffectiveTokenExtraction() {
        val completeResp = CompanionPairCompleteResponse(
            ok = true,
            deviceToken = sampleIvacToken
        )
        assertEquals(sampleIvacToken, completeResp.effectiveToken)

        val completeFallback = CompanionPairCompleteResponse(
            ok = true,
            token = sampleIvacToken
        )
        assertEquals(sampleIvacToken, completeFallback.effectiveToken)
    }

    @Test
    fun testPairVerifyResponseEffectiveTokenExtraction() {
        val verifyWithToken = PairVerifyResponse(
            ok = true,
            paired = true,
            token = sampleIvacToken
        )
        assertEquals(sampleIvacToken, verifyWithToken.effectiveToken)

        val verifyWithDeviceToken = PairVerifyResponse(
            ok = true,
            paired = true,
            deviceToken = sampleIvacToken
        )
        assertEquals(sampleIvacToken, verifyWithDeviceToken.effectiveToken)
    }

    @Test
    fun testAuthorizationHeaderFormat() {
        val rawToken = sampleIvacToken
        val authHeader = "Bearer $rawToken"

        assertTrue("Header must start with Bearer ", authHeader.startsWith("Bearer "))
        assertEquals(7 + rawToken.length, authHeader.length)
        assertEquals(rawToken, authHeader.substring(7).trim())
        assertTrue("Companion token must start with ivac_cmp_", rawToken.startsWith("ivac_cmp_"))
    }

    @Test
    fun testPairKeyValidationAndFormatting() {
        fun isValidKeySyntax(key: String): Boolean {
            val clean = key.trim().replace("-", "").uppercase()
            if (clean.length < 8 || clean.length > 32) return false
            val regex = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{8,32}$")
            return regex.matches(clean)
        }

        fun formatPairKey(raw: String): String {
            val clean = raw.trim().replace("-", "").uppercase()
            return if (clean.length in 8..32) {
                clean.chunked(4).joinToString("-")
            } else {
                raw.trim().uppercase()
            }
        }

        // Hyphenated 8-char base32
        val key1 = "6988-P97K"
        assertTrue(isValidKeySyntax(key1))

        // Non-hyphenated 8-char base32
        val key2 = "6988P97K"
        assertTrue(isValidKeySyntax(key2))

        // 32-character permanent key (160 bits entropy)
        val key3 = "EMRW-6M45-8K9N-2P7V-X3QY-9R4T-5W8Z-K2LM"
        assertTrue(isValidKeySyntax(key3))

        // Formatting logic
        assertEquals("6988-P97K", formatPairKey(key1))
        assertEquals("6988-P97K", formatPairKey(key2))
        assertEquals(key3, formatPairKey(key3))

        // Invalid keys
        assertFalse(isValidKeySyntax("1234-567")) // 7 chars (too short)
        assertFalse(isValidKeySyntax("6988-P970")) // '0' is not in base32 charset
        assertFalse(isValidKeySyntax("6988-P971")) // '1' is not in base32 charset
    }

    @Test
    fun testRealBackendPairRequestJsonResponseDeserialization() {
        val gson = com.google.gson.Gson()
        val json = """
            {
              "ok": true,
              "message": "Companion pairing request approved",
              "pairingId": "pair_cmw8umpq974599fef02800851",
              "deviceToken": "$sampleIvacToken",
              "status": "paired"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, CompanionPairResponse::class.java)
        assertTrue(parsed.ok)
        assertEquals("Companion pairing request approved", parsed.message)
        assertEquals("pair_cmw8umpq974599fef02800851", parsed.effectivePairingId)
        assertEquals(sampleIvacToken, parsed.effectiveToken)
        assertEquals("paired", parsed.status)
    }

    @Test
    fun testRealBackendPairCompleteJsonResponseDeserialization() {
        val gson = com.google.gson.Gson()
        val json = """
            {
              "ok": true,
              "message": "Pairing completed successfully",
              "deviceToken": "$sampleIvacToken"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, CompanionPairCompleteResponse::class.java)
        assertTrue(parsed.ok)
        assertEquals("Pairing completed successfully", parsed.message)
        assertEquals(sampleIvacToken, parsed.effectiveToken)
    }

    @Test
    fun testRealBackendPairVerifyJsonResponseDeserialization() {
        val gson = com.google.gson.Gson()
        val json = """
            {
              "ok": true,
              "message": "Pairing verified successfully",
              "paired": true,
              "token": "$sampleIvacToken"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, PairVerifyResponse::class.java)
        assertTrue(parsed.ok)
        assertTrue(parsed.paired)
        assertEquals("Pairing verified successfully", parsed.message)
        assertEquals(sampleIvacToken, parsed.effectiveToken)
    }

    @Test
    fun testNestedDataTokenDeserialization() {
        val gson = com.google.gson.Gson()
        val json = """
            {
              "ok": true,
              "data": {
                "deviceToken": "$sampleIvacToken",
                "pairingId": "pair_nested_999"
              }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, CompanionPairResponse::class.java)
        assertTrue(parsed.ok)
        assertEquals("pair_nested_999", parsed.effectivePairingId)
        assertEquals(sampleIvacToken, parsed.effectiveToken)
    }
}

