package com.ivac.assistant

import com.ivac.assistant.core.util.OtpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpParserTest {

    @Test
    fun testNumericOtpExtraction() {
        val message = "Your IVAC Bangladesh appointment login verification code is 420318. Valid for 5 minutes."
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }

    @Test
    fun testHyphenatedWordFormOtpExtraction() {
        // As documented in Chrome Extension background.js ("Four-Two-…")
        val message = "Dear Applicant, your IVAC portal verification code is Four-Two-Zero-Three-One-Eight. Do not share with anyone."
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }

    @Test
    fun testSpaceSeparatedWordFormOtpExtraction() {
        val message = "IVAC: code is four two zero three one eight"
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }

    @Test
    fun testMixedCaseWordFormOtp() {
        val message = "Your IVAC OTP is FOUR Two ZERO three One EIGHT"
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }

    @Test
    fun testBengaliDigitsExtraction() {
        val message = "IVAC ভেরিফিকেশন কোড ৪২০৩১৮"
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }

    @Test
    fun testUnrelatedMessageWithoutOtpReturnsNull() {
        val message = "Welcome to Robi! Dial *123# to check your balance."
        val otp = OtpParser.extractOtp(message)
        assertNull(otp)
    }

    @Test
    fun testSenderMatching() {
        assertTrue(OtpParser.isAllowedSender("IVACBD"))
        assertTrue(OtpParser.isAllowedSender("AD-IVAC"))
        assertTrue(OtpParser.isAllowedSender("BTCL"))
        assertTrue(OtpParser.isAllowedSender("InfoSMS"))

        assertFalse(OtpParser.isAllowedSender("BKASH"))
        assertFalse(OtpParser.isAllowedSender("NAGAD"))
        assertFalse(OtpParser.isAllowedSender("FACEBOOK"))
        assertFalse(OtpParser.isAllowedSender(null))
    }

    @Test
    fun testKeywordMatching() {
        assertTrue(OtpParser.hasIvacContext("Your IVAC appointment OTP is 420318"))
        assertTrue(OtpParser.hasIvacContext("Indian Visa Application Center code"))
        assertTrue(OtpParser.hasIvacContext("Appointment verification code: 420318"))

        assertFalse(OtpParser.hasIvacContext("Your pizza delivery is on the way"))
        assertFalse(OtpParser.hasIvacContext("Bank account alert"))
        assertFalse(OtpParser.hasIvacContext(null))
    }
}

