package com.ivac.assistant

import com.ivac.assistant.core.util.OtpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpParserTest {

    // 1. All Zero-Nine word conversions
    @Test
    fun testAllZeroToNineWordConversions() {
        assertEquals('0', OtpParser.convertWordToDigit("Zero"))
        assertEquals('1', OtpParser.convertWordToDigit("One"))
        assertEquals('2', OtpParser.convertWordToDigit("Two"))
        assertEquals('3', OtpParser.convertWordToDigit("Three"))
        assertEquals('4', OtpParser.convertWordToDigit("Four"))
        assertEquals('5', OtpParser.convertWordToDigit("Five"))
        assertEquals('6', OtpParser.convertWordToDigit("Six"))
        assertEquals('7', OtpParser.convertWordToDigit("Seven"))
        assertEquals('8', OtpParser.convertWordToDigit("Eight"))
        assertEquals('9', OtpParser.convertWordToDigit("Nine"))

        // Case-insensitivity check
        assertEquals('0', OtpParser.convertWordToDigit("zero"))
        assertEquals('0', OtpParser.convertWordToDigit("ZERO"))
        assertEquals('5', OtpParser.convertWordToDigit("fIvE"))
        assertEquals('9', OtpParser.convertWordToDigit("NINE"))

        // Invalid word check
        assertNull(OtpParser.convertWordToDigit("Ten"))
        assertNull(OtpParser.convertWordToDigit("Twelve"))
        assertNull(OtpParser.convertWordToDigit("Apple"))
    }

    // 2. Real IVAC SMS formats & specific prompt examples
    @Test
    fun testRealIvacSmsFormatFromPrompt() {
        val message = "(IVACBD) For security, type the following sequence when prompted\nTwo-Nine-Two-Four-Zero-Nine."
        val otp = OtpParser.extractOtp(message)
        assertEquals("292409", otp)
    }

    @Test
    fun testPromptExamples() {
        // Three-Seven-Two-Zero-One-Eight → 372018
        assertEquals("372018", OtpParser.extractOtp("Sequence: Three-Seven-Two-Zero-One-Eight."))

        // Three-Zero-Zero-Seven-Seven-Two → 300772
        assertEquals("300772", OtpParser.extractOtp("Your code is Three-Zero-Zero-Seven-Seven-Two"))

        // Six-One-Eight-Two-Five-Zero → 618250
        assertEquals("618250", OtpParser.extractOtp("IVAC: Six-One-Eight-Two-Five-Zero"))
    }

    // 3. Leading zero preservation
    @Test
    fun testLeadingZeroPreservation() {
        val message1 = "Security sequence: Zero-One-Two-Three-Four-Five."
        val otp1 = OtpParser.extractOtp(message1)
        assertEquals("012345", otp1)

        val message2 = "Sequence: Zero-Zero-Zero-Seven-Eight-Nine"
        val otp2 = OtpParser.extractOtp(message2)
        assertEquals("000789", otp2)
    }

    // 4. Hyphen-separated words
    @Test
    fun testHyphenSeparatedWords() {
        val message = "Two-Nine-Two-Four-Zero-Nine."
        assertEquals("292409", OtpParser.extractOtp(message))
    }

    // 5. Spaces and line breaks
    @Test
    fun testSpacesAndLineBreaks() {
        // Space separated
        val spaceMsg = "(IVACBD) For security, type the following sequence when prompted\nTwo Nine Two Four Zero Nine"
        assertEquals("292409", OtpParser.extractOtp(spaceMsg))

        // Multiple line breaks and mixed whitespace
        val multilineMsg = "Two   Nine\nTwo\r\nFour   Zero   Nine."
        assertEquals("292409", OtpParser.extractOtp(multilineMsg))

        // Commas and spaces
        val commaMsg = "Two, Nine, Two, Four, Zero, Nine"
        assertEquals("292409", OtpParser.extractOtp(commaMsg))
    }

    // 6. Mixed uppercase and lowercase
    @Test
    fun testMixedCaseWordFormOtp() {
        val message1 = "tWo-nInE-TWO-four-ZERO-nine"
        assertEquals("292409", OtpParser.extractOtp(message1))

        val message2 = "TWO-NINE-TWO-FOUR-ZERO-NINE"
        assertEquals("292409", OtpParser.extractOtp(message2))

        val message3 = "two-nine-two-four-zero-nine"
        assertEquals("292409", OtpParser.extractOtp(message3))
    }

    // 7. Invalid words in sequence
    @Test
    fun testInvalidWordsInSequence() {
        // Non-number word inserted
        val message1 = "Two-Nine-Apple-Four-Zero-Nine"
        assertNull(OtpParser.extractOtp(message1))

        // Number word greater than nine (Twelve)
        val message2 = "Two-Nine-Twelve-Four-Zero-Nine"
        assertNull(OtpParser.extractOtp(message2))

        // Arbitrary sentence with isolated numbers
        val message3 = "I have two apples and one orange on four plates."
        assertNull(OtpParser.extractOtp(message3))
    }

    // 8. Incomplete sequences (must be rejected)
    @Test
    fun testIncompleteSequences() {
        // 5 words
        assertNull(OtpParser.extractOtp("Two-Nine-Two-Four-Zero"))
        // 4 words
        assertNull(OtpParser.extractOtp("One-Two-Three-Four"))
        // 1 word
        assertNull(OtpParser.extractOtp("Nine"))
    }

    // 9. Overlong sequences (must be rejected - exactly six required)
    @Test
    fun testOverlongSequences() {
        // 7 words
        assertNull(OtpParser.extractOtp("Two-Nine-Two-Four-Zero-Nine-One"))
        // 8 words
        assertNull(OtpParser.extractOtp("One-Two-Three-Four-Five-Six-Seven-Eight"))
    }

    // 10. Unrelated bKash, Nagad, Gmail, and Bank SMS (must be ignored)
    @Test
    fun testUnrelatedSmsIgnored() {
        // bKash OTP
        val bkashMsg = "Your bKash verification code is 123456. Never share this with anyone."
        assertFalse(OtpParser.hasIvacContext(bkashMsg))
        assertFalse(OtpParser.isAllowedSender("bKash"))
        assertFalse(OtpParser.isAllowedSender("16247"))

        // Nagad OTP
        val nagadMsg = "Your Nagad verification code is 889900. Valid for 3 mins."
        assertFalse(OtpParser.hasIvacContext(nagadMsg))
        assertFalse(OtpParser.isAllowedSender("Nagad"))

        // Gmail / Google OTP
        val googleMsg = "G-654321 is your Google verification code."
        assertFalse(OtpParser.hasIvacContext(googleMsg))
        assertFalse(OtpParser.isAllowedSender("Google"))
        assertFalse(OtpParser.isAllowedSender("Gmail"))

        // Bank OTP
        val bankMsg = "Your City Bank debit card OTP for transaction of BDT 5000 is 112233."
        assertFalse(OtpParser.hasIvacContext(bankMsg))
        assertFalse(OtpParser.isAllowedSender("CITYBANK"))
    }

    // 11. Sender ID validation & allowlist
    @Test
    fun testSenderMatching() {
        // Target format
        assertTrue(OtpParser.isAllowedSender("IVAC_BD"))
        assertTrue(OtpParser.isAllowedSender("ivac_bd"))
        assertTrue(OtpParser.isAllowedSender("IVACBD"))
        assertTrue(OtpParser.isAllowedSender("IVAC"))
        assertTrue(OtpParser.isAllowedSender("AD-IVAC"))
        assertTrue(OtpParser.isAllowedSender("BTCL"))
        assertTrue(OtpParser.isAllowedSender("InfoSMS"))

        // Disallowed / blocked senders
        assertFalse(OtpParser.isAllowedSender("bKash"))
        assertFalse(OtpParser.isAllowedSender("16247"))
        assertFalse(OtpParser.isAllowedSender("Nagad"))
        assertFalse(OtpParser.isAllowedSender("Google"))
        assertFalse(OtpParser.isAllowedSender("CITYBANK"))
        assertFalse(OtpParser.isAllowedSender("FACEBOOK"))
        assertFalse(OtpParser.isAllowedSender(null))
        assertFalse(OtpParser.isAllowedSender(""))
    }

    // 12. Security phrase & keyword matching
    @Test
    fun testSecurityPhraseAndKeywordMatching() {
        assertTrue(OtpParser.hasIvacContext("(IVACBD) For security, type the following sequence when prompted\nTwo-Nine-Two-Four-Zero-Nine."))
        assertTrue(OtpParser.hasIvacContext("For security, type the following sequence when prompted"))
        assertTrue(OtpParser.hasIvacContext("Type the following sequence when prompted"))
        assertTrue(OtpParser.hasIvacContext("IVAC Bangladesh appointment verification code"))

        assertFalse(OtpParser.hasIvacContext("Welcome to our service. Dial *123# to recharge."))
        assertFalse(OtpParser.hasIvacContext("Your food delivery order is confirmed."))
        assertFalse(OtpParser.hasIvacContext(null))
    }

    // 13. Multiple IVAC SMS arriving consecutively
    @Test
    fun testMultipleConsecutiveIvacSms() {
        val sms1 = "(IVACBD) For security, type the following sequence when prompted\nTwo-Nine-Two-Four-Zero-Nine."
        val sms2 = "(IVACBD) For security, type the following sequence when prompted\nThree-Seven-Two-Zero-One-Eight."

        val otp1 = OtpParser.extractOtp(sms1)
        val otp2 = OtpParser.extractOtp(sms2)

        assertEquals("292409", otp1)
        assertEquals("372018", otp2)

        // Neither interferes with or mixes digits from the other
        assertEquals(6, otp1?.length)
        assertEquals(6, otp2?.length)
    }

    // 14. Bengali digits fallback extraction
    @Test
    fun testBengaliDigitsExtraction() {
        val message = "IVAC ভেরিফিকেশন কোড ৪২০৩১৮"
        val otp = OtpParser.extractOtp(message)
        assertEquals("420318", otp)
    }
}
