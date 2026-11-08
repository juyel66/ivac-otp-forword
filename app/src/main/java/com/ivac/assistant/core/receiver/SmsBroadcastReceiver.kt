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

                // 1. Verify Sender pattern
                val senderMatches = OtpParser.isAllowedSender(originatingAddress, senderFilters)

                // 2. Verify IVAC Content pattern
                val contentMatches = OtpParser.hasIvacContext(messageText, keywordFilters)

                if (!senderMatches && !contentMatches) {
                    // Not an IVAC message - silently drop without reading or saving
                    return@launch
                }

                // 3. Extract 6-digit OTP (handles both digits and spoken words)
                val otp = OtpParser.extractOtp(messageText)
                if (otp.isNullOrBlank()) {
                    prefs.recordForwardingEvent("IVAC SMS detected, but no valid 6-digit OTP found", false)
                    return@launch
                }

                // 4. Forward to backend relay
                // (Note: forwardOtp never logs digits and handles duplicate prevention)
                repository.forwardOtp(otp)

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

