package com.aguirre.pulsealert.core

/**
 * Configuración global de la app.
 * La URL y API Key aquí son los valores por defecto.
 * El usuario puede sobreescribirlos desde SettingsScreen,
 * y se guardan en DataStore (AppPreferences).
 *
 * NUNCA leer AppConfig directamente en un ViewModel.
 * Siempre leer desde AppPreferences, que usa AppConfig como fallback.
 */
object AppConfig {

    // URL del servidor Socket.IO
    // Cambiar este valor antes de compilar para producción.
    const val DEFAULT_SERVER_URL = "http://192.168.15.189:9001"

    // URL del endpoint /status (puede ser un servicio independiente en la nube
    const val DEFAULT_STATUS_URL = "http://192.168.15.189:9001/status"

    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/JoseCarlos00/PulseAlert/main/release/release.json"

    // API Key que el servidor espera en el handshake (query param "apiKey")
    const val DEFAULT_API_KEY = "6e1d18835ed50aa6b50d345af9c73093bd61d52c5349ea"

    // Alias por defecto del dispositivo (se muestra en notificaciones del servidor)
    const val DEFAULT_DEVICE_ALIAS = "Dispositivo Android"

    const val DEFAULT_UNLOCK_PIN = "4507"
}