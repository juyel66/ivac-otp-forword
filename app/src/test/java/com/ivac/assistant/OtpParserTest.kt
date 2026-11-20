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
        assertNull(OtpParser.extractOtp(bkashMsg))

        // Nagad OTP
        val nagadMsg = "Your Nagad verification code is 889900. Valid for 3 mins."
        assertFalse(OtpParser.hasIvacContext(nagadMsg))
        assertFalse(OtpParser.isAllowedSender("Nagad"))
        assertNull(OtpParser.extractOtp(nagadMsg))

        // Gmail / Google OTP
        val googleMsg = "G-654321 is your Google verification code."
        assertFalse(OtpParser.hasIvacContext(googleMsg))
        assertFalse(OtpParser.isAllowedSender("Google"))
        assertFalse(OtpParser.isAllowedSender("Gmail"))
        assertNull(OtpParser.extractOtp(googleMsg))

        // Bank OTP
        val bankMsg = "Your City Bank debit card OTP for transaction of BDT 5000 is 112233."
        assertFalse(OtpParser.hasIvacContext(bankMsg))
        assertFalse(OtpParser.isAllowedSender("CITYBANK"))
        assertNull(OtpParser.extractOtp(bankMsg))
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
        assertTrue(OtpParser.isAllowedSender("AD-IVACBD"))
        assertTrue(OtpParser.isAllowedSender("MD-IVACBD"))
        assertTrue(OtpParser.isAllowedSender("GP-IVACBD"))
        assertTrue(OtpParser.isAllowedSender("ROBI-IVAC"))
        assertTrue(OtpParser.isAllowedSender("BTCL"))
        assertTrue(OtpParser.isAllowedSender("InfoSMS"))
        assertTrue(OtpParser.isAllowedSender("TELETALK"))
        assertTrue(OtpParser.isAllowedSender("BD-IVAC"))

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

    // 15. Dot separated words extraction
    @Test
    fun testDotSeparatedWordsExtraction() {
        val message = "Sequence: Two.Nine.Two.Four.Zero.Nine."
        val otp = OtpParser.extractOtp(message)
        assertEquals("292409", otp)
    }

    // 16. Empty custom filter lists fallback to defaults
    @Test
    fun testEmptyFilterListsFallbackToDefaults() {
        assertTrue(OtpParser.isAllowedSender("IVAC_BD", emptyList()))
        assertTrue(OtpParser.isAllowedSender("AD-IVAC", emptyList()))
        assertFalse(OtpParser.isAllowedSender("bKash", emptyList()))

        assertTrue(OtpParser.hasIvacContext("For security, type the following sequence when prompted", emptyList()))
        assertFalse(OtpParser.hasIvacContext("Hello world", emptyList()))
    }

    // =========================================================================
    // SPECIFIC REQUIREMENT COVERAGE (Sections A through L)
    // =========================================================================

    // Requirement A: New underscore format (Six_Two_Eight_One_Five_Four → 628154)
    @Test
    fun testRequirementA_NewUnderscoreFormat() {
        val ivacSms = "(IVACBD) For security, type the following sequence when prompted Six_Two_Eight_One_Five_Four ."
        assertEquals("628154", OtpParser.extractOtp(ivacSms))
        assertTrue(OtpParser.hasIvacContext(ivacSms))

        // Standalone underscore sequence
        assertEquals("628154", OtpParser.extractOtp("Six_Two_Eight_One_Five_Four"))
    }

    // Requirement B: Old hyphen format (One-Zero-Zero-Seven-Eight-Two → 100782)
    @Test
    fun testRequirementB_OldHyphenFormat() {
        val ivacSms = "(IVACBD) For security, type the following sequence when prompted One-Zero-Zero-Seven-Eight-Two."
        assertEquals("100782", OtpParser.extractOtp(ivacSms))
        assertTrue(OtpParser.hasIvacContext(ivacSms))

        // Standalone hyphen sequence
        assertEquals("100782", OtpParser.extractOtp("One-Zero-Zero-Seven-Eight-Two"))
    }

    // Requirement C: Leading zeros (Zero_Zero_One_Two_Three_Four → 001234)
    @Test
    fun testRequirementC_LeadingZeros() {
        val msg1 = "Sequence: Zero_Zero_One_Two_Three_Four"
        assertEquals("001234", OtpParser.extractOtp(msg1))

        val msg2 = "(IVACBD) For security, type the following sequence when prompted Zero_Zero_Zero_Zero_Zero_Zero."
        assertEquals("000000", OtpParser.extractOtp(msg2))

        val msg3 = "Zero_Nine_Eight_Seven_Six_Five"
        assertEquals("098765", OtpParser.extractOtp(msg3))
    }

    // Requirement D: All ten digit words (Zero, One, Two, Three, Four, Five, Six, Seven, Eight, Nine)
    @Test
    fun testRequirementD_AllTenDigitWords() {
        // Individual word conversions
        val expectedDigits = listOf(
            "Zero" to '0', "One" to '1', "Two" to '2', "Three" to '3', "Four" to '4',
            "Five" to '5', "Six" to '6', "Seven" to '7', "Eight" to '8', "Nine" to '9'
        )
        for ((word, digit) in expectedDigits) {
            assertEquals(digit, OtpParser.convertWordToDigit(word))
        }

        // Sequences exercising all digits 0-9
        assertEquals("012345", OtpParser.extractOtp("Zero_One_Two_Three_Four_Five"))
        assertEquals("456789", OtpParser.extractOtp("Four_Five_Six_Seven_Eight_Nine"))
        assertEquals("987650", OtpParser.extractOtp("Nine-Eight-Seven-Six-Five-Zero"))
    }

    // Requirement E: Mixed separators (underscore, hyphen, space, dot, comma)
    @Test
    fun testRequirementE_MixedSeparators() {
        assertEquals("628154", OtpParser.extractOtp("Six_Two-Eight_One-Five_Four"))
        assertEquals("100782", OtpParser.extractOtp("One-Zero_Zero-Seven_Eight-Two"))
        assertEquals("300777", OtpParser.extractOtp("Three_Zero-Zero.Seven,Seven Seven"))
        assertEquals("628154", OtpParser.extractOtp("Six _Two - Eight_ One - Five _Four"))
    }

    // Requirement F: Uppercase and lowercase words
    @Test
    fun testRequirementF_CaseInsensitivity() {
        assertEquals("628154", OtpParser.extractOtp("six_two_eight_one_five_four"))
        assertEquals("628154", OtpParser.extractOtp("SIX_TWO_EIGHT_ONE_FIVE_FOUR"))
        assertEquals("628154", OtpParser.extractOtp("sIx_TwO_eIgHt_OnE_fIvE_fOuR"))
        assertEquals("100782", OtpParser.extractOtp("ONE-zero-ZERO-seven-EIGHT-two"))
    }

    // Requirement G: Optional whitespace around separators
    @Test
    fun testRequirementG_OptionalWhitespace() {
        assertEquals("628154", OtpParser.extractOtp("Six _ Two _ Eight _ One _ Five _ Four"))
        assertEquals("628154", OtpParser.extractOtp("Six  _  Two  _  Eight  _  One  _  Five  _  Four"))
        assertEquals("100782", OtpParser.extractOtp("One  -  Zero  -  Zero  -  Seven  -  Eight  -  Two"))
        assertEquals("628154", OtpParser.extractOtp("Six _ Two - Eight _ One - Five _ Four"))
    }

    // Requirement H: Trailing punctuation (periods, commas, spaces)
    @Test
    fun testRequirementH_TrailingPunctuation() {
        assertEquals("628154", OtpParser.extractOtp("Six_Two_Eight_One_Five_Four ."))
        assertEquals("628154", OtpParser.extractOtp("Six_Two_Eight_One_Five_Four."))
        assertEquals("628154", OtpParser.extractOtp("Six_Two_Eight_One_Five_Four,"))
        assertEquals("628154", OtpParser.extractOtp("Six_Two_Eight_One_Five_Four!"))
        assertEquals("100782", OtpParser.extractOtp("One-Zero-Zero-Seven-Eight-Two."))
        assertEquals("100782", OtpParser.extractOtp("One-Zero-Zero-Seven-Eight-Two ..."))
    }

    // Requirement I: Existing numeric OTP format in IVAC context
    @Test
    fun testRequirementI_ExistingNumericOtpFormat() {
        val numMsg1 = "(IVACBD) Your OTP verification code is 628154"
        assertEquals("628154", OtpParser.extractOtp(numMsg1))

        val numMsg2 = "IVAC appointment verification code: 100782"
        assertEquals("100782", OtpParser.extractOtp(numMsg2))

        val numMsg3 = "IVAC ভেরিফিকেশন কোড ৪২০৩১৮"
        assertEquals("420318", OtpParser.extractOtp(numMsg3))

        val numMsg4 = "For security, type the following sequence when prompted: 902145"
        assertEquals("902145", OtpParser.extractOtp(numMsg4))
    }

    // Requirement J: Invalid or incomplete sequences (must return null)
    @Test
    fun testRequirementJ_InvalidOrIncompleteSequences() {
        assertNull(OtpParser.extractOtp("Six_Two_Eight_One_Five"))          // 5 words
        assertNull(OtpParser.extractOtp("One-Two-Three-Four"))               // 4 words
        assertNull(OtpParser.extractOtp("Six"))                              // 1 word
        assertNull(OtpParser.extractOtp("Six_Two_Eight_One_Five_Four_Seven")) // 7 words
        assertNull(OtpParser.extractOtp("Six_Two_Apple_One_Five_Four"))       // Non-digit word
        assertNull(OtpParser.extractOtp("Six_Two_Twelve_One_Five_Four"))      // Word > 9
        assertNull(OtpParser.extractOtp("Six_Two_Eight_One_Five_Sixty"))      // Word > 9
    }

    // Requirement K: Unrelated SMS messages (banking, telecom, social)
    @Test
    fun testRequirementK_UnrelatedSmsMessages() {
        val bkash = "Your bKash verification code is 123456. Never share this with anyone."
        assertNull(OtpParser.extractOtp(bkash))
        assertFalse(OtpParser.hasIvacContext(bkash))
        assertFalse(OtpParser.isAllowedSender("bKash"))

        val nagad = "Your Nagad verification code is 889900. Valid for 3 mins."
        assertNull(OtpParser.extractOtp(nagad))
        assertFalse(OtpParser.hasIvacContext(nagad))
        assertFalse(OtpParser.isAllowedSender("Nagad"))

        val google = "G-654321 is your Google verification code."
        assertNull(OtpParser.extractOtp(google))
        assertFalse(OtpParser.hasIvacContext(google))
        assertFalse(OtpParser.isAllowedSender("Google"))

        val bank = "Your City Bank debit card OTP for transaction of BDT 5000 is 112233."
        assertNull(OtpParser.extractOtp(bank))
        assertFalse(OtpParser.hasIvacContext(bank))
        assertFalse(OtpParser.isAllowedSender("CITYBANK"))
    }

    // Requirement L: Non-IVAC messages containing six-digit numbers
    @Test
    fun testRequirementL_NonIvacMessagesContainingSixDigitNumbers() {
        val amazon = "Your Amazon package 654321 has shipped."
        assertNull(OtpParser.extractOtp(amazon))
        assertFalse(OtpParser.hasIvacContext(amazon))

        val food = "Your food delivery PIN is 849201."
        assertNull(OtpParser.extractOtp(food))
        assertFalse(OtpParser.hasIvacContext(food))

        val bill = "Electricity bill token 982143. Pay immediately."
        assertNull(OtpParser.extractOtp(bill))
        assertFalse(OtpParser.hasIvacContext(bill))

        val address = "Meeting at 123456 Main Street."
        assertNull(OtpParser.extractOtp(address))
        assertFalse(OtpParser.hasIvacContext(address))

        val phone = "Call me at 01712-345678."
        assertNull(OtpParser.extractOtp(phone))
        assertFalse(OtpParser.hasIvacContext(phone))

        val order = "Order #987654 has been processed."
        assertNull(OtpParser.extractOtp(order))
        assertFalse(OtpParser.hasIvacContext(order))
    }
<<<<<<< HEAD
=======

    // =========================================================================
    // SECTION 8: MANDATORY COMPLIANCE TESTS (Items 1 through 16)
    // =========================================================================

    // 1. Exact hyphen SMS example → 100782
    @Test
    fun test1_ExactHyphenSmsExample() {
        val message = "(IVACBD) For security, type the following sequence when prompted One-Zero-Zero-Seven-Eight-Two."
        val otp = OtpParser.extractOtp(message)
        assertEquals("100782", otp)
        assertNotNull(otp)
        assertEquals(6, otp!!.length)
        assertTrue(otp.all { it.isDigit() })
    }

    // 2. Exact underscore SMS example → 628154
    @Test
    fun test2_ExactUnderscoreSmsExample() {
        val message = "(IVACBD) For security, type the following sequence when prompted Six_Two_Eight_One_Five_Four ."
        val otp = OtpParser.extractOtp(message)
        assertEquals("628154", otp)
        assertNotNull(otp)
        assertEquals(6, otp!!.length)
        assertTrue(otp.all { it.isDigit() })
    }

    // 3. Hyphen format from IVAC_BD sender
    @Test
    fun test3_HyphenFormatFromIvacBdSender() {
        val sender = "IVAC_BD"
        val body = "(IVACBD) For security, type the following sequence when prompted One-Zero-Zero-Seven-Eight-Two."

        val result = OtpParser.processIvacSms(sender, body)
        assertTrue("Message must be accepted", result.isAccepted)
        assertEquals("100782", result.otp)
        assertEquals(OtpParser.RecognizedFormat.HYPHEN, result.format)
        assertEquals("IVAC_OFFICIAL", result.senderCategory)
        assertEquals("100782", OtpParser.extractIvacOtp(sender, body))
    }

    // 4. Underscore format from ordinary phone-number sender
    @Test
    fun test4_UnderscoreFormatFromOrdinaryPhoneNumberSender() {
        val sender = "+8801712345678"
        val body = "(IVACBD) For security, type the following sequence when prompted Six_Two_Eight_One_Five_Four ."

        val result = OtpParser.processIvacSms(sender, body)
        assertTrue("Message must be accepted even from ordinary phone number", result.isAccepted)
        assertEquals("628154", result.otp)
        assertEquals(OtpParser.RecognizedFormat.UNDERSCORE, result.format)
        assertEquals("PHONE_NUMBER", result.senderCategory)
        assertEquals("628154", OtpParser.extractIvacOtp(sender, body))
    }

    // 5. Hyphen format from ordinary phone-number sender
    @Test
    fun test5_HyphenFormatFromOrdinaryPhoneNumberSender() {
        val sender = "01819876543"
        val body = "(IVACBD) For security, type the following sequence when prompted One-Zero-Zero-Seven-Eight-Two."

        val result = OtpParser.processIvacSms(sender, body)
        assertTrue("Message must be accepted from ordinary phone number", result.isAccepted)
        assertEquals("100782", result.otp)
        assertEquals(OtpParser.RecognizedFormat.HYPHEN, result.format)
        assertEquals("PHONE_NUMBER", result.senderCategory)
        assertEquals("100782", OtpParser.extractIvacOtp(sender, body))
    }

    // 6. Unknown sender with valid marker and OTP
    @Test
    fun test6_UnknownSenderWithValidMarkerAndOtp() {
        val body = "(IVACBD) For security, type the following sequence when prompted Six_Two_Eight_One_Five_Four ."

        val resultUnknown = OtpParser.processIvacSms("UNKNOWN", body)
        assertTrue("Must accept unknown sender ID with valid marker and OTP", resultUnknown.isAccepted)
        assertEquals("628154", resultUnknown.otp)

        val resultNull = OtpParser.processIvacSms(null, body)
        assertTrue("Must accept null sender with valid marker and OTP", resultNull.isAccepted)
        assertEquals("628154", resultNull.otp)
    }

    // 7. Numeric OTP with marker
    @Test
    fun test7_NumericOtpWithMarker() {
        val body = "(IVACBD) Your OTP verification code is 628154."
        val result = OtpParser.processIvacSms("IVAC_BD", body)
        assertTrue(result.isAccepted)
        assertEquals("628154", result.otp)
        assertEquals(OtpParser.RecognizedFormat.NUMERIC, result.format)
        assertEquals("628154", OtpParser.extractIvacOtp("IVAC_BD", body))
    }

    // 8. Leading-zero OTP
    @Test
    fun test8_LeadingZeroOtp() {
        val body1 = "(IVACBD) For security, type the following sequence when prompted Zero_Zero_One_Two_Three_Four ."
        val otp1 = OtpParser.extractOtp(body1)
        assertEquals("001234", otp1)
        assertTrue(otp1!!.startsWith("00"))
        assertEquals(6, otp1.length)

        val body2 = "(IVACBD) For security, type the following sequence when prompted Zero-Zero-Zero-Seven-Eight-Two."
        val otp2 = OtpParser.extractOtp(body2)
        assertEquals("000782", otp2)
        assertTrue(otp2!!.startsWith("000"))
    }

    // 9. Mixed separators
    @Test
    fun test9_MixedSeparators() {
        val body1 = "(IVACBD) Six_Two-Eight One_Five-Four"
        val otp1 = OtpParser.extractOtp(body1)
        assertEquals("628154", otp1)

        val body2 = "(IVACBD) One - Zero _ Zero - Seven _ Eight - Two ."
        val otp2 = OtpParser.extractOtp(body2)
        assertEquals("100782", otp2)

        assertEquals(OtpParser.RecognizedFormat.MIXED, OtpParser.detectFormat(body1))
    }

    // 10. All ten English digit words
    @Test
    fun test10_AllTenEnglishDigitWords() {
        // Range 0-5
        val body1 = "(IVACBD) Zero_One_Two_Three_Four_Five"
        assertEquals("012345", OtpParser.extractOtp(body1))

        // Range 4-9
        val body2 = "(IVACBD) Four_Five_Six_Seven_Eight_Nine"
        assertEquals("456789", OtpParser.extractOtp(body2))

        // Range 9-0
        val body3 = "(IVACBD) Nine-Eight-Seven-Six-Five-Zero"
        assertEquals("987650", OtpParser.extractOtp(body3))
    }

    // 11. Marker absent → rejected
    @Test
    fun test11_MarkerAbsent_Rejected() {
        // Message has valid OTP words, but lacks (IVACBD) marker
        val body = "For security, type the following sequence when prompted One-Zero-Zero-Seven-Eight-Two."
        assertFalse("Marker must not be found", OtpParser.hasIvacMarker(body))
        assertFalse("Must not accept message without (IVACBD) marker", OtpParser.shouldAcceptMessage("IVAC_BD", body))
        assertFalse("Must not accept message from phone number without marker", OtpParser.shouldAcceptMessage("+8801712345678", body))
        assertNull("extractIvacOtp must return null without marker", OtpParser.extractIvacOtp("IVAC_BD", body))

        val result = OtpParser.processIvacSms("IVAC_BD", body)
        assertFalse(result.isAccepted)
        assertEquals("Missing mandatory (IVACBD) marker", result.rejectionReason)
    }

    // 12. Marker present but OTP missing → rejected
    @Test
    fun test12_MarkerPresent_OtpMissing_Rejected() {
        val body = "(IVACBD) Welcome to the Indian Visa Application Center."
        assertTrue(OtpParser.hasIvacMarker(body))
        assertNull(OtpParser.extractOtp(body))
        assertNull(OtpParser.extractIvacOtp("IVAC_BD", body))

        val result = OtpParser.processIvacSms("IVAC_BD", body)
        assertFalse(result.isAccepted)
        assertEquals("OTP missing, incomplete, or ambiguous", result.rejectionReason)
    }

    // 13. Invalid or incomplete OTP → rejected
    @Test
    fun test13_InvalidOrIncompleteOtp_Rejected() {
        // 5 words (incomplete)
        assertNull(OtpParser.extractOtp("(IVACBD) Six_Two_Eight_One_Five"))

        // 7 words (overlong)
        assertNull(OtpParser.extractOtp("(IVACBD) Six_Two_Eight_One_Five_Four_Three"))

        // Invalid word inserted
        assertNull(OtpParser.extractOtp("(IVACBD) Six_Two_Apple_One_Five_Four"))

        // Number word greater than nine
        assertNull(OtpParser.extractOtp("(IVACBD) Six_Two_Ten_One_Five_Four"))
    }

    // 14. Ambiguous OTP → rejected
    @Test
    fun test14_AmbiguousOtp_Rejected() {
        // Two distinct 6-word sequences in same message
        val multiWordMsg = "(IVACBD) One-Two-Three-Four-Five-Six or Six-Five-Four-Three-Two-One"
        assertNull(OtpParser.extractOtp(multiWordMsg))

        // Two conflicting 6-digit numeric codes
        val multiNumMsg = "(IVACBD) Your OTP code is 123456 or 654321"
        assertNull(OtpParser.extractOtp(multiNumMsg))

        val result = OtpParser.processIvacSms("IVAC_BD", multiNumMsg)
        assertFalse(result.isAccepted)
    }

    // 15. Existing regression tests
    @Test
    fun test15_ExistingRegressionTests() {
        assertEquals("292409", OtpParser.extractOtp("(IVACBD) For security, type the following sequence when prompted\nTwo-Nine-Two-Four-Zero-Nine."))
        assertEquals("372018", OtpParser.extractOtp("Sequence: Three-Seven-Two-Zero-One-Eight."))
        assertEquals("300772", OtpParser.extractOtp("Your code is Three-Zero-Zero-Seven-Seven-Two"))
        assertEquals("618250", OtpParser.extractOtp("IVAC: Six-One-Eight-Two-Five-Zero"))
        assertEquals("420318", OtpParser.extractOtp("IVAC ভেরিফিকেশন কোড ৪২০৩১৮"))
    }

    // 16. Unrelated messages never reach forwarding
    @Test
    fun test16_UnrelatedMessagesNeverForwarded() {
        val bkash = "Your bKash verification code is 123456"
        assertFalse(OtpParser.shouldAcceptMessage("bKash", bkash))
        assertNull(OtpParser.extractIvacOtp("bKash", bkash))

        val nagad = "Your Nagad PIN is 889900"
        assertFalse(OtpParser.shouldAcceptMessage("Nagad", nagad))
        assertNull(OtpParser.extractIvacOtp("Nagad", nagad))

        val bank = "Your City Bank debit card OTP is 112233"
        assertFalse(OtpParser.shouldAcceptMessage("CITYBANK", bank))
        assertNull(OtpParser.extractIvacOtp("CITYBANK", bank))

        // Spoofed message with marker but blocked sender
        val spoofed = "(IVACBD) Your bKash verification code is 123456"
        assertFalse(OtpParser.shouldAcceptMessage("bKash", spoofed))
        assertNull(OtpParser.extractIvacOtp("bKash", spoofed))

        // Ordinary SMS from phone number without marker
        val normalSms = "Where are you? Call me."
        assertFalse(OtpParser.shouldAcceptMessage("+8801712345678", normalSms))
        assertNull(OtpParser.extractIvacOtp("+8801712345678", normalSms))
    }
>>>>>>> 58b7199b95975879d76cc5b1aefec406e723070c
}
