package com.ivac.assistant.core.util

import java.util.Locale
import java.util.regex.Pattern

/**
 * Robust parser for extracting 6-digit OTPs from IVAC SMS notifications.
 *
 * Specializes in:
 * 1. English spoken number-words to numeric conversion:
 *    Zero=0, One=1, Two=2, Three=3, Four=4, Five=5, Six=6, Seven=7, Eight=8, Nine=9
 *    Supports both NEW format (Underscore: Six_Two_Eight_One_Five_Four)
 *    and OLD format (Hyphen: One-Zero-Zero-Seven-Eight-Two), spaces, mixed separators.
 * 2. Strict IVAC sender & security phrase filtering (e.g. IVAC_BD, IVACBD)
 * 3. Fallback support for standard 6-digit numeric codes and Bengali digits in IVAC context.
 *
 * SECURITY & PRIVACY RULES:
 * - Never log extracted OTP digits.
 * - Discard unrelated SMS (bKash, Nagad, Gmail, Banks, etc.) immediately.
 * - Preserve leading zeros.
 * - Reject incomplete, ambiguous, or invalid sequences.
 * - Never convert OTPs to integers. Always preserve them as strings.
 */
object OtpParser {

    val DEFAULT_SENDER_PATTERNS = listOf(
        "IVAC_BD",
        "IVACBD",
        "IVAC",
        "IVAC-BD",
        "AD-IVAC",
        "AD-IVACBD",
        "MD-IVAC",
        "MD-IVACBD",
        "VK-IVAC",
        "VK-IVACBD",
        "BL-IVAC",
        "BL-IVACBD",
        "GP-IVAC",
        "GP-IVACBD",
        "ROBI-IVAC",
        "ROBI-IVACBD",
        "AIRTEL-IVAC",
        "AIRTEL-IVACBD",
        "TELETALK",
        "BTCL",
        "INFOSMS",
        "INFO_SMS",
        "BD-IVAC",
        "BDI-IVAC",
        "BDI-IVACBD"
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

    // Regex matches digit words bounded by non-letters (allowing underscores, hyphens, spaces, punctuation)
    private val NUMBER_WORD_REGEX = Regex("(?i)(?<![a-zA-Z])(zero|one|two|three|four|five|six|seven|eight|nine)(?![a-zA-Z])")

    // Allows underscores, hyphens, spaces, dots, commas between consecutive digit words
    private val ALLOWED_SEPARATOR_REGEX = Regex("^[\\s_\\-,\\.]+$")

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
        val patterns = if (allowedPatterns.isEmpty()) DEFAULT_SENDER_PATTERNS else allowedPatterns

        // 2. Check allowlist
        return patterns.any { pattern ->
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

        val keywords = if (requiredKeywords.isEmpty()) DEFAULT_KEYWORD_PATTERNS else requiredKeywords

        // 2. Check for required IVAC keywords or security phrases
        return keywords.any { keyword ->
            upperBody.contains(keyword.uppercase(Locale.ROOT))
        }
    }

    /**
     * Extracts a 6-digit OTP from message text.
     *
     * Prioritizes contiguous English number-word sequences:
     * - Underscore-separated: "Six_Two_Eight_One_Five_Four" -> "628154"
     * - Hyphen-separated: "One-Zero-Zero-Seven-Eight-Two" -> "100782"
     * - Spaces, mixed separators, optional whitespace, trailing punctuation.
     * - Preserves leading zeros.
     *
     * Falls back to standard 6-digit numeric codes and Bengali numerals ONLY in verified IVAC context.
     *
     * Returns null if no valid, unambiguous 6-digit OTP is found, or if the message is unrelated/unauthenticated.
     */
    fun extractOtp(body: String?): String? {
        if (body.isNullOrBlank()) return null

        val upperBody = body.uppercase(Locale.ROOT)

        // 1. Immediately reject messages containing blocked financial / banking keywords
        if (BLOCKED_KEYWORDS.any { upperBody.contains(it) }) {
            return null
        }

        // 2. Primary: Word-form extraction (Six_Two_Eight_One_Five_Four -> 628154, Two-Nine-Two-Four-Zero-Nine -> 292409)
        val wordFormOtp = extractWordFormOtp(body)
        if (wordFormOtp != null && wordFormOtp.length == 6) {
            return wordFormOtp
        }

        // 3. Fallback: Numeric / Bengali digits extraction requires verified IVAC context
        if (!hasIvacContext(body)) {
            return null
        }

        // 4. Normalize Bengali digits to English digits if present
        val normalizedBody = normalizeBengaliDigits(body)

        // 5. Strict numeric 6-digit regex within IVAC context
        // Bound by non-digits to avoid extracting parts of phone numbers, timestamps, or longer sequences
        val patterns = listOf(
            Pattern.compile("(?i)(?:otp|code|verification|sequence)[^0-9a-z]{0,10}(?<!\\d)([0-9]{6})(?!\\d)"),
            Pattern.compile("(?i)(?:is|are)[^0-9a-z]{0,5}(?<!\\d)([0-9]{6})(?!\\d)"),
            Pattern.compile("(?<!\\d)([0-9]{6})(?!\\d)")
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
     * Extracts spoken word-form numbers: "Six_Two_Eight_One_Five_Four", "One-Zero-Zero-Seven-Eight-Two", etc.
     *
     * Requirements satisfied:
     * - Supports both NEW underscore separator ("Six_Two_Eight_One_Five_Four") and OLD hyphen separator ("One-Zero-Zero-Seven-Eight-Two").
     * - Case-insensitive parsing.
     * - Supports hyphens, underscores, spaces, line breaks, commas, dots, and trailing punctuation.
     * - Preserves leading zeros (e.g. Zero_Zero_One_Two_Three_Four -> 001234).
     * - Returns exactly six digits as a string (never converted to integer).
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
