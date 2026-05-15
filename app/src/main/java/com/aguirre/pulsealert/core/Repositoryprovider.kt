package com.aguirre.pulsealert.core

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.aguirre.pulsealert.data.local.AppPreferences
import com.aguirre.pulsealert.data.remote.SocketDataSource
import com.aguirre.pulsealert.data.repository.DeviceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Singleton manual que construye y provee DeviceRepository.
 *
 * Reemplaza a Hilt para una app de uso interno sin servicios de Google.
 * Se inicializa una sola vez desde SocketForegroundService.onCreate()
 * y se puede acceder desde cualquier ViewModel con:
 *
 *   val repository = RepositoryProvider.get(context)
 *
 * Por qué aquí y no en Application:
 *  - El socket necesita la URL guardada en DataStore para conectar.
 *  - DataStore es asíncrono, así que leemos el valor una sola vez
 *    con runBlocking al inicializar (solo ocurre una vez en toda la
 *    vida de la app, desde el ForegroundService).
 *
 * NOTA: runBlocking está justificado aquí porque:
 *  1. Solo se ejecuta una vez al arrancar el servicio.
 *  2. El ForegroundService ya corre en un contexto que lo permite.
 *  3. DataStore responde en microsegundos desde la caché en disco.
 */
object RepositoryProvider {

    @Volatile
    private var INSTANCE: DeviceRepository? = null

    /**
     * Devuelve la instancia única de DeviceRepository.
     * La crea la primera vez leyendo la configuración de DataStore.
     *
     * @param context Cualquier Context — se usa applicationContext internamente.
     */
    fun get(context: Context): DeviceRepository {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }
    }

    private fun build(context: Context): DeviceRepository {
        val prefs = AppPreferences(context)

        // Leemos la configuración guardada de forma síncrona.
        // Si el usuario nunca configuró nada, AppPreferences devuelve
        // los valores por defecto de AppConfig.
        val (serverUrl, apiKey, deviceAlias) = runBlocking {
            Triple(
                prefs.serverUrl.first(),
                prefs.apiKey.first(),
                prefs.deviceAlias.first()
            )
        }

        // androidId es el identificador único del dispositivo.
        // Es estable mientras no se haga factory reset.
        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        // IP local del dispositivo en la red WiFi.
        val ipAddress = getLocalIpAddress(context)

        // Versión de la app desde BuildConfig (generado automáticamente).
        val appVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        val socketDataSource = SocketDataSource(
            serverUrl   = serverUrl,
            apiKey      = apiKey,
            androidId   = androidId,
            deviceAlias = deviceAlias,
            appVersion  = appVersion,
            ipAddress   = ipAddress
        )

        val messageDao = AppDatabase.getInstance(context).messageDao()

        return DeviceRepository(
            prefs            = prefs,
            messageDao       = messageDao,
            socketDataSource = socketDataSource
        )
    }

    /**
     * Obtiene la IP local del dispositivo en la red WiFi.
     * Devuelve "0.0.0.0" si no hay conexión o no se puede obtener.
     */
    private fun getLocalIpAddress(context: Context): String {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo.ipAddress
            // Convierte el entero a formato "192.168.x.x"
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Reinicia el singleton. Útil cuando el usuario guarda una nueva URL
     * en SettingsScreen — el ForegroundService debe reconectarse con la
     * nueva configuración.
     *
     * Flujo de uso:
     *  1. Usuario guarda nueva URL en SettingsScreen.
     *  2. ForegroundService detecta el cambio (observando serverUrl Flow).
     *  3. Llama reset() y luego get(context) para reconstruir con la nueva URL.
     */
    fun reset() {
        synchronized(this) {
            INSTANCE?.disconnectSocket()
            INSTANCE = null
        }
    }
}
