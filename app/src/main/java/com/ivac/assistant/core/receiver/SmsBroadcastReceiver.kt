package com.ivac.assistant.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ivac.assistant.IvacApplication
import com.ivac.assistant.core.util.OtpParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Standard Android SMS BroadcastReceiver.
 *
 * Listens exclusively for incoming SMS, checks sender and body against
 * IVAC verification rules, extracts the 6-digit OTP, and dispatches it
 * to the paired Chrome Extension via the repository.
 *
 * SECURITY & PRIVACY:
 * - Only runs when the user has explicitly paired their device and enabled forwarding.
 * - Discards unrelated SMS immediately without storing or logging.
 * - Never uploads message body or SMS inbox.
 * - Never logs OTP digits in logcat.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val app = context.applicationContext as? IvacApplication ?: return
        val repository = app.repository
        val prefs = app.preferencesManager

        // Device must be paired to process anything
        if (!repository.isPaired()) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Use goAsync to allow asynchronous repository call in background
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isForwardingEnabled = prefs.isSmsForwardingEnabled.first()
                if (!isForwardingEnabled) {
                    prefs.recordForwardingEvent("SMS received, but forwarding is toggled OFF", false)
                    return@launch
                }

                val senderFilters = prefs.senderFilters.first().toList()
                val keywordFilters = prefs.keywordFilters.first().toList()

                // Concatenate multipart SMS segments if applicable
                val fullBody = StringBuilder()
                var originatingAddress = ""

                for (sms in messages) {
                    originatingAddress = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
                    fullBody.append(sms.displayMessageBody ?: sms.messageBody ?: "")
                }

                val messageText = fullBody.toString()
                val sender = originatingAddress.ifBlank { "IVAC_BD" }

                // 1. Verify Sender pattern against allowlist
                if (!OtpParser.isAllowedSender(sender, senderFilters)) {
                    // Not an allowed IVAC sender (bKash, Nagad, Gmail, Bank, etc.) -> silently drop
                    return@launch
                }

                // 2. Verify IVAC security context & phrases
                if (!OtpParser.hasIvacContext(messageText, keywordFilters)) {
                    prefs.recordForwardingEvent("Invalid SMS ignored: Unrelated SMS content", false)
                    return@launch
                }

                // Record intermediate UI state: IVAC SMS detected
                prefs.recordForwardingEvent("IVAC SMS detected", false)

                // 3. Extract 6-digit OTP (converts word-form numbers e.g. "Two-Nine-Two-Four-Zero-Nine" to "292409")
                val otp = OtpParser.extractOtp(messageText)
                if (otp.isNullOrBlank() || otp.length != 6) {
                    prefs.recordForwardingEvent("Invalid SMS ignored: Incomplete or invalid sequence", false)
                    return@launch
                }

                // Record intermediate UI state: OTP extracted successfully
                prefs.recordForwardingEvent("OTP extracted successfully", false)

                // 4. Forward authenticated numeric OTP to backend relay
                // (Note: forwardOtp never logs digits and enforces duplicate prevention)
                repository.forwardOtp(
                    extractedOtp = otp,
                    sender = sender,
                    receivedAt = System.currentTimeMillis()
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming SMS broadcast: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "IvacSmsReceiver"
    }
}

