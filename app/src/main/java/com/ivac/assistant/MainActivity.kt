package com.ivac.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ivac.assistant.ui.screens.ConnectExtensionScreen
import com.ivac.assistant.ui.screens.DashboardScreen
import com.ivac.assistant.ui.screens.SettingsScreen
import com.ivac.assistant.ui.screens.SmsPermissionScreen
import com.ivac.assistant.ui.screens.WelcomeScreen
import com.ivac.assistant.ui.theme.IVACAssistantTheme
import com.ivac.assistant.ui.viewmodel.AppScreen
import com.ivac.assistant.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            IVACAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkBatteryOptimization()
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val isSmsForwardingEnabled by viewModel.isSmsForwardingEnabled.collectAsState()
    val lastForwardedTime by viewModel.lastForwardedTime.collectAsState()
    val lastForwardedStatus by viewModel.lastForwardedStatus.collectAsState()
    val forwardedCount by viewModel.forwardedCount.collectAsState()
    val isBatteryGuideDismissed by viewModel.isBatteryGuideDismissed.collectAsState()
    val currentBaseUrl by viewModel.baseUrl.collectAsState()
    val senderFilters by viewModel.senderFilters.collectAsState()
    val keywordFilters by viewModel.keywordFilters.collectAsState()

    when (uiState.currentScreen) {
        AppScreen.WELCOME -> {
            WelcomeScreen(
                onContinue = {
                    if (uiState.isPaired) {
                        viewModel.navigateTo(AppScreen.DASHBOARD)
                    } else {
                        viewModel.navigateTo(AppScreen.CONNECT_EXTENSION)
                    }
                }
            )
        }

        AppScreen.CONNECT_EXTENSION -> {
            ConnectExtensionScreen(
                uiState = uiState,
                onBack = { viewModel.navigateTo(AppScreen.WELCOME) },
                onPair = { key, phone ->
                    viewModel.pairWithExtension(key, phone)
                },
                onProceedToPermission = {
                    viewModel.navigateTo(AppScreen.SMS_PERMISSION)
                }
            )
        }

        AppScreen.SMS_PERMISSION -> {
            SmsPermissionScreen(
                onContinueToDashboard = {
                    viewModel.navigateTo(AppScreen.DASHBOARD)
                }
            )
        }

        AppScreen.DASHBOARD -> {
            DashboardScreen(
                uiState = uiState,
                isOnline = isOnline,
                isSmsForwardingEnabled = isSmsForwardingEnabled,
                lastForwardedTime = lastForwardedTime,
                lastForwardedStatus = lastForwardedStatus,
                forwardedCount = forwardedCount,
                isBatteryGuideDismissed = isBatteryGuideDismissed,
                onToggleForwarding = { viewModel.toggleSmsForwarding(it) },
                onTestConnection = { viewModel.testConnection() },
                onDisconnect = { viewModel.disconnect() },
                onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                onDismissBatteryGuide = { viewModel.dismissBatteryGuide() }
            )
        }

        AppScreen.SETTINGS -> {
            SettingsScreen(
                currentBaseUrl = currentBaseUrl,
                senderFilters = senderFilters,
                keywordFilters = keywordFilters,
                onBack = {
                    viewModel.navigateTo(if (uiState.isPaired) AppScreen.DASHBOARD else AppScreen.WELCOME)
                },
                onSaveBaseUrl = { viewModel.updateBaseUrl(it) },
                onSaveSenders = { viewModel.updateSenderFilters(it) },
                onSaveKeywords = { viewModel.updateKeywordFilters(it) }
            )
        }
    }
}

