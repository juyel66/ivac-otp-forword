package com.ivac.assistant.core.util

import java.util.Locale
import java.util.regex.Pattern

/**
 * Robust parser for extracting 6-digit OTPs from IVAC SMS notifications.
 *
 * Supports both:
 * 1. Standard 6-digit numeric codes (e.g. "Your IVAC OTP is 420318")
 * 2. Word-form spoken numbers as used by IVAC SMS gateways
 *    (e.g. "Your verification code is Four-Two-Zero-Three-One-Eight")
 * 3. Bengali digit numbers (e.g. ৪২০৩১৮ -> 420318)
 *
 * IMPORTANT SECURITY RULES:
 * - Never log extracted OTP digits.
 * - Only process SMS matching IVAC sender / keyword rules.
 * - Discard unrelated SMS messages immediately.
 */
object OtpParser {

    val DEFAULT_SENDER_PATTERNS = listOf(
        "IVAC",
        "IVACBD",
        "BTCL",
        "INFOSMS",
        "AD-IVAC",
        "MD-IVAC",
        "VK-IVAC",
        "BL-IVAC",
        "GP-IVAC",
        "ROBI-IVAC",
        "AIRTEL-IVAC",
        "TELETALK"
    )

    val DEFAULT_KEYWORD_PATTERNS = listOf(
        "IVAC",
        "INDIAN VISA",
        "VISA APPLICATION",
        "APPOINTMENT",
        "VERIFICATION CODE",
        "ONE TIME PASSWORD",
        "OTP"
    )

    private val WORD_TO_DIGIT = mapOf(
        "zero" to '0',
        "one" to '1',
        "two" to '2',
        "three" to '3',
        "four" to '4',
        "five" to '5',
        "six" to '6',
        "seven" to '7',
        "eight" to '8',
        "nine" to '9'
    )

    private val BENGALI_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    /**
     * Checks if the sender matches any known IVAC sender patterns.
     */
    fun isAllowedSender(sender: String?, allowedPatterns: List<String> = DEFAULT_SENDER_PATTERNS): Boolean {
        if (sender.isNullOrBlank()) return false
        val cleanSender = sender.trim().uppercase(Locale.ROOT)
        return allowedPatterns.any { pattern ->
            cleanSender.contains(pattern.uppercase(Locale.ROOT))
        }
    }

    /**
     * Checks if the message content contains IVAC authentication indicators.
     */
    fun hasIvacContext(body: String?, requiredKeywords: List<String> = DEFAULT_KEYWORD_PATTERNS): Boolean {
        if (body.isNullOrBlank()) return false
        val upperBody = body.uppercase(Locale.ROOT)
        return requiredKeywords.any { keyword ->
            upperBody.contains(keyword.uppercase(Locale.ROOT))
        }
    }

    /**
     * Extracts a 6-digit OTP from message text.
     * Returns null if no valid 6-digit OTP matching IVAC patterns is found.
     */
    fun extractOtp(body: String?): String? {
        if (body.isNullOrBlank()) return null

        // 1. Try word-form extraction first (e.g. "Four-Two-Zero-Three-One-Eight" or "Four Two Zero...")
        val wordFormOtp = extractWordFormOtp(body)
        if (wordFormOtp != null && wordFormOtp.length == 6) {
            return wordFormOtp
        }

        // 2. Normalize Bengali digits to English digits if present
        val normalizedBody = normalizeBengaliDigits(body)

        // 3. Try regex patterns looking for 6 digits
        val patterns = listOf(
            // Explicit OTP keywords followed by 6 digits
            Pattern.compile("(?i)(?:otp|code|verification|pin)[^0-9a-z]{0,10}([0-9]{6})\\b"),
            // "is 123456"
            Pattern.compile("(?i)(?:is|are)[^0-9a-z]{0,5}([0-9]{6})\\b"),
            // Standalone 6-digit number in message
            Pattern.compile("\\b([0-9]{6})\\b")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(normalizedBody)
            if (matcher.find()) {
                val candidate = matcher.group(1)
                if (candidate != null && candidate.length == 6) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Extracts spoken word-form numbers: "Four-Two-Zero-Three-One-Eight", "four two...", etc.
     */
    private fun extractWordFormOtp(text: String): String? {
        // Tokenize words, splitting by hyphens, spaces, commas, periods
        val tokens = text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z]+"))
            .filter { it.isNotBlank() }

        val digitsFound = StringBuilder()

        for (token in tokens) {
            val digit = WORD_TO_DIGIT[token]
            if (digit != null) {
                digitsFound.append(digit)
                if (digitsFound.length == 6) {
                    return digitsFound.toString()
                }
            } else {
                // If a non-number word is encountered and we already started collecting,
                // reset unless we reached 6
                if (digitsFound.isNotEmpty() && digitsFound.length < 6) {
                    digitsFound.clear()
                }
            }
        }

        return if (digitsFound.length == 6) digitsFound.toString() else null
    }

    private fun normalizeBengaliDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val enDigit = BENGALI_DIGITS[ch]
            if (enDigit != null) {
                sb.append(enDigit)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}

