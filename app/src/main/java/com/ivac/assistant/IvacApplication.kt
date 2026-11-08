package com.ivac.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ivac.assistant.core.data.preferences.PreferencesManager
import com.ivac.assistant.core.data.repository.IvacRepository
import com.ivac.assistant.core.data.security.SecureStorage
import com.ivac.assistant.core.util.NetworkMonitor

class IvacApplication : Application() {

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var repository: IvacRepository
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        secureStorage = SecureStorage(this)
        preferencesManager = PreferencesManager(this)
        repository = IvacRepository(this, secureStorage, preferencesManager)
        networkMonitor = NetworkMonitor(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "IVAC Relay Status"
            val descriptionText = "Notifications about IVAC OTP forwarding and connection status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "ivac_relay_channel"
        lateinit var instance: IvacApplication
            private set
    }
}

