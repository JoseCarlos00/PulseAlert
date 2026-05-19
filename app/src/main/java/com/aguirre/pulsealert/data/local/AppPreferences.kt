package com.aguirre.pulsealert.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aguirre.pulsealert.core.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Extensión que crea una única instancia de DataStore por Context.
 * Debe declararse a nivel de archivo, fuera de cualquier clase.
 * "app_preferences" es el nombre del archivo en disco.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences"
)

/**
 * Fuente de verdad para la configuración del usuario.
 *
 * DataStore es la alternativa moderna a SharedPreferences:
 *  - Es asíncrono (usa coroutines y Flow, nunca bloquea el hilo principal)
 *  - Es type-safe (las claves tienen tipo, no son strings sueltos)
 *  - Es seguro ante corrupción de datos
 *
 * Patrón de uso:
 *  - Leer → observar un Flow desde el ViewModel (reactivo)
 *  - Escribir → llamar a una función suspend desde el ViewModel
 *
 * Si el usuario nunca guardó un valor, se usa AppConfig como fallback.
 */
class AppPreferences(private val context: Context) {

    companion object {
        // Claves tipadas para cada preferencia.
        // stringPreferencesKey garantiza que no mezcles tipos por error.
        private val KEY_SERVER_URL   = stringPreferencesKey("server_url")
        private val KEY_STATUS_URL = stringPreferencesKey("status_url")
        private val KEY_UPDATE_URL = stringPreferencesKey("update_url")
        private val KEY_API_KEY      = stringPreferencesKey("api_key")
        private val KEY_DEVICE_ALIAS = stringPreferencesKey("device_alias")
        private val KEY_MAINTENANCE_MODE      = booleanPreferencesKey("maintenance_mode")
        private val KEY_MAINTENANCE_UNTIL_MS  = longPreferencesKey("maintenance_until_ms")
    }

    // ── Flows de lectura ──────────────────────────────────────────────

    /**
     * URL del servidor Socket.IO.
     * Emite el valor guardado o AppConfig.DEFAULT_SERVER_URL si no hay nada.
     */
    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: AppConfig.DEFAULT_SERVER_URL
    }

    val statusUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_STATUS_URL] ?: AppConfig.DEFAULT_STATUS_URL
    }

    val updateUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_UPDATE_URL] ?: AppConfig.DEFAULT_UPDATE_URL
    }

    /**
     * API Key para el handshake del socket.
     * Emite el valor guardado o AppConfig.DEFAULT_API_KEY si no hay nada.
     */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: AppConfig.DEFAULT_API_KEY
    }

    /**
     * Alias del dispositivo. Se envía al servidor en REGISTER_DEVICE
     * y aparece en las notificaciones del panel web.
     */
    val deviceAlias: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ALIAS] ?: AppConfig.DEFAULT_DEVICE_ALIAS
    }

    val isMaintenanceMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAINTENANCE_MODE] ?: false
    }

    val maintenanceUntilMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAINTENANCE_UNTIL_MS] ?: 0L
    }

    // ── Funciones de escritura ────────────────────────────────────────

    /**
     * Guarda la URL del servidor.
     * suspend → debe llamarse desde una coroutine (ViewModel o Service).
     */
    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url
        }
    }

    suspend fun saveStatusUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STATUS_URL] = url
        }
    }

    suspend fun saveUpdateUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_UPDATE_URL] = url
        }
    }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = key
        }
    }

    suspend fun saveDeviceAlias(alias: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ALIAS] = alias
        }
    }

    suspend fun setMaintenanceMode(active: Boolean, untilMs: Long = 0L) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAINTENANCE_MODE]     = active
            prefs[KEY_MAINTENANCE_UNTIL_MS] = untilMs
        }
    }

    suspend fun getMaintenanceUntilMs(): Long =
        context.dataStore.data.map { it[KEY_MAINTENANCE_UNTIL_MS] ?: 0L }.first()


    /**
     * Restablece todos los valores a los defaults de AppConfig.
     * Útil para el botón "Restablecer" en SettingsScreen.
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL]   = AppConfig.DEFAULT_SERVER_URL
            prefs[KEY_STATUS_URL] = AppConfig.DEFAULT_STATUS_URL
            prefs[KEY_UPDATE_URL] = AppConfig.DEFAULT_UPDATE_URL
            prefs[KEY_API_KEY]      = AppConfig.DEFAULT_API_KEY
            prefs[KEY_DEVICE_ALIAS] = AppConfig.DEFAULT_DEVICE_ALIAS
            prefs[KEY_MAINTENANCE_MODE]     = false
            prefs[KEY_MAINTENANCE_UNTIL_MS] = 0L
        }
    }
}