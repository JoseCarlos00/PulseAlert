package com.aguirre.pulsealert.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aguirre.pulsealert.core.RepositoryProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla de configuración.
 * Es una data class para que Compose detecte cambios eficientemente.
 *
 * Los campos son los valores que el usuario ve y edita en el formulario.
 * isSaved se usa para mostrar un mensaje de confirmación breve ("Guardado ✓").
 */
data class SettingsUiState(
    val serverUrl: String = "",
    val statusUrl: String = "",
    val updateUrl: String = "",
    val deviceAlias: String = "",
    val isSaved: Boolean = false,
    val isLoading: Boolean = true
)

/**
 * ViewModel de SettingsScreen.
 *
 * Flujo:
 *  1. Al crearse, carga los valores guardados en DataStore → uiState
 *  2. El usuario edita los campos → onServerUrlChange, etc.
 *  3. El usuario pulsa Guardar → saveSettings() escribe en DataStore
 *  4. El usuario pulsa Restablecer → resetSettings() borra y recarga
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RepositoryProvider.get(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Carga los tres valores desde DataStore en paralelo usando combine.
     * combine espera a tener un valor de cada Flow antes de emitir.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                repository.serverUrl,
                repository.statusUrl,
                repository.updateUrl,
                repository.deviceAlias
            ) { serverUrl, statusUrl, updateUrl, deviceAlias ->
                SettingsUiState(
                    serverUrl   = serverUrl,
                    statusUrl   = statusUrl,
                    updateUrl   = updateUrl,
                    deviceAlias = deviceAlias,
                    isLoading   = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // ——— Handlers de cambio en el formulario ———
    // Cada campo del formulario llama a su función correspondiente.
    // update { } crea una copia del estado cambiando solo ese campo.

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, isSaved = false) }
    }

    fun onStatusUrlChange(value: String) {
        _uiState.update { it.copy(statusUrl = value, isSaved = false) }
    }

    fun onUpdateUrlChange(value: String) {
        _uiState.update { it.copy(updateUrl = value, isSaved = false) }
    }

    fun onDeviceAliasChange(value: String) {
        _uiState.update { it.copy(deviceAlias = value, isSaved = false) }
    }

    // ── Acciones ─────────────────────────────────────────────────────

    /**
     * Guarda los tres valores en DataStore.
     * Tras guardar, activa isSaved para que la UI muestre confirmación.
     */
    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            repository.saveServerUrl(state.serverUrl)
            repository.saveStatusUrl(state.statusUrl)
            repository.saveUpdateUrl(state.updateUrl)
            repository.saveDeviceAlias(state.deviceAlias)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    /**
     * Restablece DataStore a los defaults de AppConfig.
     * DataStore emitirá los nuevos valores y loadSettings() actualizará
     * el estado automáticamente gracias al Flow activo.
     */
    fun resetSettings() {
        viewModelScope.launch {
            repository.resetPrefsToDefaults()
            _uiState.update { it.copy(isSaved = false) }
        }
    }
}