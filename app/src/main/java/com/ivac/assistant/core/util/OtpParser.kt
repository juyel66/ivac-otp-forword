package com.ivac.assistant.core.util

import java.util.Locale
import java.util.regex.Pattern

/**
 * Robust parser for extracting 6-digit OTPs from IVAC SMS notifications.
 *
 * Specializes in:
 * 1. English spoken number-words to numeric conversion:
 *    Zero=0, One=1, Two=2, Three=3, Four=4, Five=5, Six=6, Seven=7, Eight=8, Nine=9
 *    (e.g. "Two-Nine-Two-Four-Zero-Nine." -> "292409")
 * 2. Strict IVAC sender & security phrase filtering (e.g. IVAC_BD, IVACBD)
 * 3. Fallback support for standard 6-digit numeric codes and Bengali digits.
 *
 * SECURITY & PRIVACY RULES:
 * - Never log extracted OTP digits.
 * - Discard unrelated SMS (bKash, Nagad, Gmail, Banks, etc.) immediately.
 * - Preserve leading zeros.
 * - Reject incomplete, ambiguous, or invalid sequences.
 */
object OtpParser {

    val DEFAULT_SENDER_PATTERNS = listOf(
        "IVAC_BD",
        "IVACBD",
        "IVAC",
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
        "FOR SECURITY, TYPE THE FOLLOWING SEQUENCE WHEN PROMPTED",
        "TYPE THE FOLLOWING SEQUENCE WHEN PROMPTED",
        "TYPE THE FOLLOWING SEQUENCE",
        "FOLLOWING SEQUENCE",
        "IVACBD",
        "IVAC BD",
        "IVAC",
        "INDIAN VISA",
        "VISA APPLICATION",
        "APPOINTMENT",
        "VERIFICATION CODE",
        "ONE TIME PASSWORD",
        "OTP"
    )

    private val BLOCKED_SENDERS = listOf(
        "BKASH",
        "16247",
        "NAGAD",
        "GOOGLE",
        "GMAIL",
        "CITYBANK",
        "EBL",
        "BRACBANK",
        "ISLAMIBANK",
        "UCB",
        "UPAY",
        "ROCKET",
        "DARAZ",
        "PATHAO",
        "UBER"
    )

    private val BLOCKED_KEYWORDS = listOf(
        "BKASH",
        "NAGAD",
        "GOOGLE VERIFICATION",
        "GMAIL",
        "DEBIT CARD",
        "CREDIT CARD",
        "ACCOUNT BALANCE",
        "TRANSACTION OF BDT",
        "ATM WITHDRAWAL"
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

    private val NUMBER_WORD_REGEX = Regex("(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine)\\b")
    private val ALLOWED_SEPARATOR_REGEX = Regex("^[-\\s,]+$")

    /**
     * Converts an individual English number word to a numeric character (0-9).
     * Returns null if not a valid single number word.
     */
    fun convertWordToDigit(word: String): Char? {
        val clean = word.trim().lowercase(Locale.ROOT)
        return WORD_TO_DIGIT[clean]
    }

    /**
     * Checks if the sender matches any known IVAC sender patterns.
     * Explicitly rejects known payment, banking, or non-IVAC senders.
     */
    fun isAllowedSender(sender: String?, allowedPatterns: List<String> = DEFAULT_SENDER_PATTERNS): Boolean {
        if (sender.isNullOrBlank()) return false
        val cleanSender = sender.trim().uppercase(Locale.ROOT)

        // 1. Check blocked senders list
        if (BLOCKED_SENDERS.any { cleanSender.contains(it) }) {
            return false
        }

        val normalizedSender = cleanSender.replace("_", "").replace("-", "")

        // 2. Check allowlist
        return allowedPatterns.any { pattern ->
            val cleanPattern = pattern.trim().uppercase(Locale.ROOT)
            val normalizedPattern = cleanPattern.replace("_", "").replace("-", "")

            cleanSender == cleanPattern ||
                    cleanSender.contains(cleanPattern) ||
                    normalizedSender.contains(normalizedPattern)
        }
    }

    /**
     * Checks if the message content contains IVAC authentication indicators.
     * Rejects payment or banking OTP messages.
     */
    fun hasIvacContext(body: String?, requiredKeywords: List<String> = DEFAULT_KEYWORD_PATTERNS): Boolean {
        if (body.isNullOrBlank()) return false
        val upperBody = body.uppercase(Locale.ROOT)

        // 1. Reject unrelated banking / payment / google SMS
        if (BLOCKED_KEYWORDS.any { upperBody.contains(it) }) {
            return false
        }

        // 2. Check for required IVAC keywords or security phrases
        return requiredKeywords.any { keyword ->
            upperBody.contains(keyword.uppercase(Locale.ROOT))
        }
    }

    /**
     * Extracts a 6-digit OTP from message text.
     *
     * Prioritizes contiguous English number-word sequences (e.g. "Two-Nine-Two-Four-Zero-Nine").
     * Falls back to standard 6-digit numeric codes and Bengali numerals.
     *
     * Returns null if no valid, unambiguous 6-digit OTP is found.
     */
    fun extractOtp(body: String?): String? {
        if (body.isNullOrBlank()) return null

        // 1. Primary: Word-form extraction (Two-Nine-Two-Four-Zero-Nine -> 292409)
        val wordFormOtp = extractWordFormOtp(body)
        if (wordFormOtp != null && wordFormOtp.length == 6) {
            return wordFormOtp
        }

        // 2. Fallback: Normalize Bengali digits to English digits if present
        val normalizedBody = normalizeBengaliDigits(body)

        // 3. Fallback: Numeric 6-digit regex
        val patterns = listOf(
            Pattern.compile("(?i)(?:otp|code|verification|sequence)[^0-9a-z]{0,10}([0-9]{6})\\b"),
            Pattern.compile("(?i)(?:is|are)[^0-9a-z]{0,5}([0-9]{6})\\b"),
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
     * Extracts spoken word-form numbers: "Two-Nine-Two-Four-Zero-Nine", "Three-Zero-Zero-Seven-Seven-Two", etc.
     *
     * Requirements satisfied:
     * - Case-insensitive parsing.
     * - Supports hyphens, spaces, line breaks, commas, and trailing punctuation.
     * - Preserves leading zeros (e.g. Zero-One-Two-... -> 012...).
     * - Returns exactly six digits.
     * - Rejects incomplete sequences (e.g. 5 words -> null).
     * - Rejects sequences longer than 6 words (e.g. 7 words -> null).
     * - Rejects sequences interrupted by arbitrary words.
     */
    fun extractWordFormOtp(text: String): String? {
        if (text.isBlank()) return null

        val matches = NUMBER_WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return null

        // Group contiguous number-word matches into chains
        val chains = mutableListOf<MutableList<MatchResult>>()
        var currentChain = mutableListOf<MatchResult>()

        for (i in matches.indices) {
            val currentMatch = matches[i]
            if (currentChain.isEmpty()) {
                currentChain.add(currentMatch)
            } else {
                val previousMatch = currentChain.last()
                val textBetween = text.substring(previousMatch.range.last + 1, currentMatch.range.first)
                if (ALLOWED_SEPARATOR_REGEX.matches(textBetween)) {
                    currentChain.add(currentMatch)
                } else {
                    chains.add(currentChain)
                    currentChain = mutableListOf(currentMatch)
                }
            }
        }
        if (currentChain.isNotEmpty()) {
            chains.add(currentChain)
        }

        // Only chains with exactly 6 consecutive number words qualify
        val validSixChains = chains.filter { it.size == 6 }

        // Reject if there is not exactly one unambiguous 6-word chain
        if (validSixChains.size != 1) {
            return null
        }

        val sixChain = validSixChains.first()
        val digits = StringBuilder(6)

        for (m in sixChain) {
            val digit = convertWordToDigit(m.value) ?: return null
            digits.append(digit)
        }

        val result = digits.toString()
        return if (result.length == 6 && result.all { it.isDigit() }) result else null
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
