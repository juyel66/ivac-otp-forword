package com.ivac.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivac.assistant.IvacApplication
import com.ivac.assistant.core.data.repository.Resource
import com.ivac.assistant.core.util.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    WELCOME,
    CONNECT_EXTENSION,
    SMS_PERMISSION,
    DASHBOARD,
    SETTINGS
}

data class UiState(
    val currentScreen: AppScreen = AppScreen.WELCOME,
    val isPaired: Boolean = false,
    val maskedPairKey: String = "",
    val pairedPhone: String = "",
    val isPairingLoading: Boolean = false,
    val pairingErrorMessage: String? = null,
    val pairingSuccessMessage: String? = null,
    val isTestingConnection: Boolean = false,
    val connectionTestResult: String? = null,
    val isIgnoringBatteryOptimizations: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IvacApplication
    private val repository = app.repository
    private val prefs = app.preferencesManager
    private val networkMonitor = app.networkMonitor

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), networkMonitor.checkCurrentConnectivity())

    val isSmsForwardingEnabled: StateFlow<Boolean> = prefs.isSmsForwardingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastForwardedTime: StateFlow<Long> = prefs.lastForwardedTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastForwardedStatus: StateFlow<String> = prefs.lastForwardedStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Idle — waiting for IVAC OTP")

    val forwardedCount: StateFlow<Int> = prefs.forwardedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val baseUrl: StateFlow<String> = prefs.baseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val senderFilters: StateFlow<Set<String>> = prefs.senderFilters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val keywordFilters: StateFlow<Set<String>> = prefs.keywordFilters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isBatteryGuideDismissed: StateFlow<Boolean> = prefs.isBatteryGuideDismissed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        refreshState()
    }

    fun refreshState() {
        val paired = repository.isPaired()
        val initialScreen = if (paired) AppScreen.DASHBOARD else AppScreen.WELCOME
        val ignoringBattery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app)

        _uiState.value = _uiState.value.copy(
            isPaired = paired,
            maskedPairKey = repository.getMaskedPairKey(),
            pairedPhone = repository.getPairedPhone(),
            currentScreen = initialScreen,
            isIgnoringBatteryOptimizations = ignoringBattery
        )
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.value = _uiState.value.copy(
            currentScreen = screen,
            pairingErrorMessage = null,
            pairingSuccessMessage = null,
            connectionTestResult = null
        )
    }

    fun pairWithExtension(rawKey: String, phone: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPairingLoading = true,
                pairingErrorMessage = null,
                pairingSuccessMessage = null
            )

            when (val result = repository.pairWithExtension(rawKey, phone)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isPairingLoading = false,
                        isPaired = true,
                        maskedPairKey = repository.getMaskedPairKey(),
                        pairedPhone = repository.getPairedPhone(),
                        pairingSuccessMessage = result.message ?: "Paired successfully!"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isPairingLoading = false,
                        pairingErrorMessage = result.message
                    )
                }
                Resource.Loading -> {}
            }
        }
    }

    fun disconnect() {
        repository.disconnect()
        _uiState.value = _uiState.value.copy(
            isPaired = false,
            maskedPairKey = "Not Paired",
            pairedPhone = "",
            currentScreen = AppScreen.CONNECT_EXTENSION
        )
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isTestingConnection = true,
                connectionTestResult = null
            )

            val result = repository.checkConnection()
            val message = when (result) {
                is Resource.Success -> "✓ Success: Backend relay is reachable and responsive."
                is Resource.Error -> "✕ Error: ${result.message}"
                Resource.Loading -> null
            }

            _uiState.value = _uiState.value.copy(
                isTestingConnection = false,
                connectionTestResult = message
            )
        }
    }

    fun toggleSmsForwarding(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSmsForwardingEnabled(enabled)
            val status = if (enabled) "SMS Forwarding enabled" else "SMS Forwarding paused"
            prefs.recordForwardingEvent(status, false)
        }
    }

    fun updateBaseUrl(newUrl: String) {
        viewModelScope.launch {
            prefs.setBaseUrl(newUrl)
        }
    }

    fun updateSenderFilters(senders: Set<String>) {
        viewModelScope.launch {
            prefs.setSenderFilters(senders)
        }
    }

    fun updateKeywordFilters(keywords: Set<String>) {
        viewModelScope.launch {
            prefs.setKeywordFilters(keywords)
        }
    }

    fun dismissBatteryGuide() {
        viewModelScope.launch {
            prefs.setBatteryGuideDismissed(true)
        }
    }

    fun checkBatteryOptimization() {
        _uiState.value = _uiState.value.copy(
            isIgnoringBatteryOptimizations = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(app)
        )
    }
}

