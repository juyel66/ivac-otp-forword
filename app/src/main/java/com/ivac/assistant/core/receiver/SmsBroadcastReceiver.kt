package com.ivac.assistant.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ivac.assistant.IvacApplication
import com.ivac.assistant.core.data.repository.Resource
import com.ivac.assistant.core.service.OtpPushWorker
import com.ivac.assistant.core.util.OtpParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Standard Android SMS BroadcastReceiver.
 *
 * Listens for incoming SMS broadcasts, checks for the mandatory (IVACBD) marker in the SMS body,
 * extracts and normalizes the 6-digit OTP, and dispatches it
 * to the paired Chrome Extension via the authenticated backend companion API
 * and WorkManager.
 *
 * SENDER SUPPORT:
 * - Universally accepts IVAC_BD, ordinary mobile numbers (+880XXXXXXXXXX, 01XXXXXXXXX),
 *   unknown sender IDs, and any sender carrying the (IVACBD) marker in the SMS body.
 *
 * SECURITY & PRIVACY:
 * - Strictly requires (IVACBD) marker in the SMS body; rejects messages without it.
 * - Immediately rejects known financial/banking senders (bKash, Nagad, Banks).
 * - Never logs OTP digits, device tokens, authorization headers, or personal data.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                return
            }

            Log.i(TAG, "SMS receiver triggered: android.provider.Telephony.SMS_RECEIVED broadcast intercepted")

            val app = (context.applicationContext as? IvacApplication) ?: IvacApplication.instance
            val repository = app.repository
            val prefs = app.preferencesManager

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) {
                Log.w(TAG, "SMS receiver: Intent messages array is empty or null")
                return
            }

            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = prefs.getForwardingSettings()

                    // Concatenate multipart SMS segments if applicable
                    val fullBody = StringBuilder()
                    var originatingAddress = ""

                    for (sms in messages) {
                        originatingAddress = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
                        fullBody.append(sms.displayMessageBody ?: sms.messageBody ?: "")
                    }

                    val messageText = fullBody.toString()
                    val sender = originatingAddress.ifBlank { "UNKNOWN" }

                    // Log: SMS received
                    Log.i(TAG, "SMS received from sender: [$sender], segments: ${messages.size}, total length: ${messageText.length} chars")

                    // Deduplicate raw SMS broadcasts (within 3s)
                    val smsSignature = "$sender:${messageText.hashCode()}"
                    val nowTime = System.currentTimeMillis()
                    val lastSeenSms = recentSmsSignatures[smsSignature]
                    if (lastSeenSms != null && (nowTime - lastSeenSms) < 3_000L) {
                        Log.i(TAG, "Duplicate SMS broadcast ignored (received within 3s)")
                        return@launch
                    }
                    recentSmsSignatures[smsSignature] = nowTime
                    recentSmsSignatures.entries.removeIf { (nowTime - it.value) > 15_000L }

                    // 1. Mandatory Marker Check: SMS body MUST contain (IVACBD)
                    val hasMarker = OtpParser.hasIvacMarker(messageText)
                    if (!hasMarker) {
                        Log.i(TAG, "IVACBD marker not found: Message rejected from forwarding pipeline")
                        return@launch
                    }
                    Log.i(TAG, "IVACBD marker found in SMS body")

                    // 2. Safeguards: Reject blocked financial/banking senders or keywords
                    if (OtpParser.isBlockedSender(sender)) {
                        Log.i(TAG, "Sender rejected: Sender [$sender] is on blocked financial list")
                        return@launch
                    }
                    if (OtpParser.containsBlockedKeyword(messageText)) {
                        Log.i(TAG, "Message rejected: Content contains blocked financial keyword")
                        return@launch
                    }

                    // 3. Sender Category Log
                    val senderCategory = OtpParser.getSenderCategory(sender)
                    Log.i(TAG, "Sender category: $senderCategory")

                    // 4. OTP Extraction & Normalization
                    val otp = OtpParser.extractOtp(messageText)
                    if (otp.isNullOrBlank() || otp.length != 6 || !otp.all { it.isDigit() }) {
                        Log.w(TAG, "OTP extraction failure: No valid 6-digit sequence found")
                        prefs.recordForwardingEvent("Invalid SMS ignored: OTP extraction failed", false)
                        return@launch
                    }

                    val format = OtpParser.detectFormat(messageText)
                    Log.i(TAG, "OTP extraction success")
                    Log.i(TAG, "Recognized format: ${format.name.lowercase(Locale.ROOT)}")
                    Log.i(TAG, "Normalization success: Exactly 6 numeric characters, leading zeros preserved")
                    prefs.recordForwardingEvent("OTP parsed successfully — preparing dispatch...", false)

                    // Forwarding Toggle Check
                    if (!settings.isEnabled) {
                        Log.i(TAG, "SMS forwarding ignored: Forwarding toggle is currently OFF")
                        prefs.recordForwardingEvent("SMS received, but forwarding is toggled OFF", false)
                        return@launch
                    }

                    // Device Pairing Check
                    if (!repository.isPaired()) {
                        Log.w(TAG, "OTP forwarding skipped: Device is not currently paired")
                        prefs.recordForwardingEvent("IVAC OTP detected, but device not paired", false)
                        return@launch
                    }

                    val receivedAt = System.currentTimeMillis()

                    // Scheduling WorkManager for background delivery guarantee
                    Log.i(TAG, "Scheduling WorkManager for background delivery guarantee...")
                    OtpPushWorker.enqueue(context, otp, sender, receivedAt)

                    // Immediate direct dispatch via goAsync() for low latency (< 500ms)
                    prefs.recordForwardingEvent("Dispatching OTP to backend relay...", false)
                    val result = repository.forwardOtp(
                        extractedOtp = otp,
                        sender = sender,
                        receivedAt = receivedAt
                    )

                    when (result) {
                        is Resource.Success -> {
                            Log.i(TAG, "Backend forwarding success: ${result.data}")
                        }
                        is Resource.Error -> {
                            Log.w(TAG, "Backend forwarding failure: ${result.message} (WorkManager will ensure background delivery)")
                        }
                        Resource.Loading -> {}
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in SMS broadcast processing: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error intercepting SMS broadcast in onReceive: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "IvacSmsReceiver"
        private val recentSmsSignatures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
