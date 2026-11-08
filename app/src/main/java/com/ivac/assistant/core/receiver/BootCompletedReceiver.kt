package com.ivac.assistant.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ivac.assistant.IvacApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles device reboot recovery (RECEIVE_BOOT_COMPLETED).
 * Verifies that the pairing and forwarding settings remain intact after restart.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val app = context.applicationContext as? IvacApplication ?: return
        val repository = app.repository
        val prefs = app.preferencesManager

        if (!repository.isPaired()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isForwarding = prefs.isSmsForwardingEnabled.first()
                if (isForwarding) {
                    prefs.recordForwardingEvent("Reboot recovery: Companion active", false)
                    Log.d(TAG, "IVAC Assistant restored after device reboot.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reboot verification error: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "IvacBootReceiver"
    }
}

