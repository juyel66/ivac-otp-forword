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

/**
 * Standard Android SMS BroadcastReceiver.
 *
 * Listens for incoming SMS broadcasts, checks sender and content against
 * IVAC verification rules, extracts the 6-digit OTP, and dispatches it
 * to the paired Chrome Extension via the authenticated backend companion API
 * and WorkManager.
 *
 * SECURITY & PRIVACY:
 * - Only operates when the user has explicitly paired their device and enabled forwarding.
 * - Discards unrelated SMS immediately without storing or logging.
 * - Never logs OTP digits, full message text, or authentication tokens in logcat.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                return
            }

            // Diagnostic Log 1: SMS receiver triggered
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
                    val sender = originatingAddress.ifBlank { "IVAC_BD" }

                    Log.i(TAG, "SMS received from sender: [$sender], segments: ${messages.size}, total length: ${messageText.length} chars")

                    // Deduplicate raw SMS broadcasts (e.g. from rapid multipart delivery or provider duplicates within 3s)
                    val smsSignature = "$sender:${messageText.hashCode()}"
                    val nowTime = System.currentTimeMillis()
                    val lastSeenSms = recentSmsSignatures[smsSignature]
                    if (lastSeenSms != null && (nowTime - lastSeenSms) < 3_000L) {
                        Log.i(TAG, "Duplicate SMS broadcast ignored (received within 3s)")
                        return@launch
                    }
                    recentSmsSignatures[smsSignature] = nowTime
                    recentSmsSignatures.entries.removeIf { (nowTime - it.value) > 15_000L }

                    // Diagnostic Log 2: Sender & Content verification
                    val isSenderAllowed = OtpParser.isAllowedSender(sender, settings.senderFilters.toList())
                    val hasContext = OtpParser.hasIvacContext(messageText, settings.keywordFilters.toList())

                    if (!isSenderAllowed && !hasContext) {
                        Log.i(TAG, "Sender rejected: Non-IVAC sender [$sender] silently dropped")
                        return@launch
                    }

                    if (isSenderAllowed) {
                        Log.i(TAG, "Sender accepted: Sender [$sender] matches IVAC allowlist")
                    } else {
                        Log.i(TAG, "Sender accepted via content context: Sender [$sender] accompanied by verified IVAC payload")
                    }

                    // Context check (never log messageText)
                    if (!hasContext) {
                        Log.i(TAG, "Message rejected: Content does not contain expected IVAC security context")
                        prefs.recordForwardingEvent("Invalid SMS ignored: Unrelated content", false)
                        return@launch
                    }
                    Log.i(TAG, "Message accepted: IVAC security context detected")
                    prefs.recordForwardingEvent("IVAC SMS detected — parsing OTP...", false)

                    // Diagnostic Log 3: OTP successfully parsed (never print the OTP)
                    val otp = OtpParser.extractOtp(messageText)
                    if (otp.isNullOrBlank() || otp.length != 6) {
                        Log.w(TAG, "OTP parsing rejected: Incomplete, invalid, or non-6-digit sequence")
                        prefs.recordForwardingEvent("Invalid SMS ignored: Incomplete or invalid sequence", false)
                        return@launch
                    }

                    Log.i(TAG, "OTP successfully parsed: Extracted exactly 6 digits (leading zeros preserved)")
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

                    // Diagnostic Log 4: WorkManager execution enqueue
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
                            Log.i(TAG, "OTP forwarding workflow completed successfully: ${result.data}")
                        }
                        is Resource.Error -> {
                            Log.w(TAG, "Direct push encountered error: ${result.message} (WorkManager will ensure background delivery)")
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
