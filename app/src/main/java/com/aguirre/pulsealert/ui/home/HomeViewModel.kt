package com.aguirre.pulsealert.ui.home

import android.annotation.SuppressLint
import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.remote.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val ipAddress: String = "—",
    val deviceAlias: String = "—",
    val androidId: String = "—",
    val appVersion: String = "—",
    val isLoading: Boolean = true
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RepositoryProvider.get(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadStaticInfo()
        observeConnectionState()
        observeDeviceAlias()
    }

    private fun loadStaticInfo() {
        val context = getApplication<Application>()

        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val appVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "—"
        } catch (e: Exception) { "—" }

        val ipAddress = getLocalIpAddress()

        _uiState.update {
            it.copy(
                androidId  = androidId,
                appVersion = appVersion,
                ipAddress  = ipAddress,
                isLoading  = false
            )
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeDeviceAlias() {
        viewModelScope.launch {
            repository.deviceAlias.collect { alias ->
                _uiState.update { it.copy(deviceAlias = alias) }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wm = getApplication<Application>()
                .getSystemService(android.content.Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) { "—" }
    }

    fun reconnect() {
        viewModelScope.launch {
            repository.disconnectSocket()
            delay(500)
            repository.connectSocket()
        }
    }
}