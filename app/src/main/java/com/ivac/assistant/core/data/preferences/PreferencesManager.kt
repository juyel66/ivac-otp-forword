package com.ivac.assistant.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ivac.assistant.BuildConfig
import com.ivac.assistant.core.util.OtpParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ivac_assistant_settings")

/**
 * Reactive settings manager powered by Jetpack DataStore Preferences.
 */
class PreferencesManager(private val context: Context) {

    val isSmsForwardingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SMS_FORWARDING_ENABLED] ?: true
    }

    val baseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BASE_URL] ?: BuildConfig.DEFAULT_BASE_URL
    }

    val lastForwardedTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_FORWARDED_TIME] ?: 0L
    }

    val lastForwardedStatus: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_FORWARDED_STATUS] ?: "Idle — waiting for IVAC OTP"
    }

    val forwardedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_FORWARDED_COUNT] ?: 0
    }

    val senderFilters: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SENDER_FILTERS] ?: OtpParser.DEFAULT_SENDER_PATTERNS.toSet()
    }

    val keywordFilters: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEYWORD_FILTERS] ?: OtpParser.DEFAULT_KEYWORD_PATTERNS.toSet()
    }

    val isBatteryGuideDismissed: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BATTERY_GUIDE_DISMISSED] ?: false
    }

    suspend fun setSmsForwardingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SMS_FORWARDING_ENABLED] = enabled
        }
    }

    suspend fun setBaseUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = clean
        }
    }

    suspend fun recordForwardingEvent(status: String, isSuccess: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_FORWARDED_TIME] = System.currentTimeMillis()
            prefs[KEY_LAST_FORWARDED_STATUS] = status
            if (isSuccess) {
                val current = prefs[KEY_FORWARDED_COUNT] ?: 0
                prefs[KEY_FORWARDED_COUNT] = current + 1
            }
        }
    }

    suspend fun setSenderFilters(senders: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SENDER_FILTERS] = senders
        }
    }

    suspend fun setKeywordFilters(keywords: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEYWORD_FILTERS] = keywords
        }
    }

    suspend fun setBatteryGuideDismissed(dismissed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BATTERY_GUIDE_DISMISSED] = dismissed
        }
    }

    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    companion object {
        private val KEY_SMS_FORWARDING_ENABLED = booleanPreferencesKey("pref_sms_forwarding_enabled")
        private val KEY_BASE_URL = stringPreferencesKey("pref_base_url")
        private val KEY_LAST_FORWARDED_TIME = longPreferencesKey("pref_last_forwarded_time")
        private val KEY_LAST_FORWARDED_STATUS = stringPreferencesKey("pref_last_forwarded_status")
        private val KEY_FORWARDED_COUNT = intPreferencesKey("pref_forwarded_count")
        private val KEY_SENDER_FILTERS = stringSetPreferencesKey("pref_sender_filters")
        private val KEY_KEYWORD_FILTERS = stringSetPreferencesKey("pref_keyword_filters")
        private val KEY_BATTERY_GUIDE_DISMISSED = booleanPreferencesKey("pref_battery_guide_dismissed")
    }
}

