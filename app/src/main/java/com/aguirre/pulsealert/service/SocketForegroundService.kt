package com.aguirre.pulsealert.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.remote.ConnectionState
import com.aguirre.pulsealert.data.repository.DeviceRepository
import com.aguirre.pulsealert.service.NotificationHelper.Companion.NOTIF_ID_FOREGROUND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "SocketForegroundService"

/**
 * Servicio que mantiene la conexión Socket.IO activa en background.
 */
class SocketForegroundService : Service() {

    private lateinit var repository: DeviceRepository
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var updateChecker: UpdateChecker

    // Scope propio del servicio. IMPORTANTE: Debe cancelarse en onDestroy.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // URL activa en el socket — usada para detectar cambios reales
    private var activeSocketUrl: String = ""

    private var currentDeviceAlias: String = ""

    // ── Ciclo de vida ─────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Inicializando servicio")

        repository         = RepositoryProvider.get(applicationContext)
        alarmPlayer        = AlarmPlayer(applicationContext)
        notificationHelper = NotificationHelper(applicationContext)
        updateChecker      = UpdateChecker(applicationContext)


        // Iniciamos los observadores una sola vez al nacer el servicio
        observeConnectionState()
        observeAlarmEvents()
        observeMessageEvents()
        observePingEvents()
        observeCheckUpdateEvents()
        observeMaintenanceEvents()
        observeServerUrlChanges()

        // Detectar mantenimiento cuando el socket falla 10 veces consecutivas
        repository.setOnMaintenanceDetectedListener { _ ->
            serviceScope.launch {
                // No tenemos timestamp del servidor — consultamos /status
                // El JobService arranca inmediatamente (delayMs = 0)
                Log.w(TAG, "10 fallos detectados. Lanzando StatusCheckJobService.")
                repository.setMaintenanceMode(true, 0L)
                // FIX PROBLEMA 2: deshabilitar reconexión ANTES de desconectar
                repository.disableSocketReconnection()
                repository.disconnectSocket()
                notificationHelper.updateMaintenanceNotification(0L)
                StatusCheckJobService.schedule(applicationContext, System.currentTimeMillis())
            }
        }

        repository.setOnDeviceAliasReceivedListener { alias ->
            serviceScope.launch {
                Log.d(TAG, "Alias recibido del servidor: $alias")
                currentDeviceAlias = alias
                repository.saveDeviceAlias(alias)

                notificationHelper.updateForegroundNotification(
                    isConnected = repository.isSocketConnected(),
                    deviceAlias = alias
                )
            }
        }

        // Verificar actualización al arrancar
        serviceScope.launch {
            updateChecker.checkAndNotify()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Recibido intent")

        // FIX PROBLEMA 1: usar el estado real del socket, no asumir false
        val alreadyConnected = repository.isSocketConnected()
        val notification = notificationHelper.buildForegroundNotification(
            isConnected = alreadyConnected,
            deviceAlias = currentDeviceAlias
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID_FOREGROUND, notification)
        }

        // Solo conectar si no está ya conectado
        if (!repository.isSocketConnected()) {
            // Verificar si seguimos en mantenimiento antes de conectar.
            // Esto cubre el caso donde Android mató y relanzó el servicio
            // mientras el mantenimiento aún estaba activo.
            serviceScope.launch {
                val untilMs = repository.getMaintenanceUntilMs()
                if (untilMs > System.currentTimeMillis()) {
                    Log.w(TAG, "Servicio relanzado durante mantenimiento. No conectando hasta: $untilMs")
                    notificationHelper.updateMaintenanceNotification(untilMs)
                    StatusCheckJobService.schedule(applicationContext, untilMs)
                } else {
                    // Mantenimiento expirado o no activo — limpiar y conectar normal
                    repository.setMaintenanceMode(false)
                    // Leer la URL actual y registrarla antes de conectar
                    activeSocketUrl = repository.serverUrl.first()
                    currentDeviceAlias = repository.deviceAlias.first()
                    repository.connectSocket()
                }
            }
        }
        // FIX PROBLEMA 1: si ya estaba conectado, no necesitamos hacer nada más
        // — el observer de connectionState ya mantendrá la notificación actualizada

        // Manejar descarga de actualización si viene de la notificación
        if (intent?.action == UpdateChecker.ACTION_DOWNLOAD_UPDATE) {
            val apkUrl = intent.getStringExtra(UpdateChecker.EXTRA_APK_URL)
            if (!apkUrl.isNullOrBlank()) {
                serviceScope.launch {
                    updateChecker.downloadAndInstall(apkUrl)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Cancelando scope y desconectando")
        
        // CORRECCIÓN CLAVE: Al cancelar el scope, todas las corrutinas lanzadas
        // con launchIn(serviceScope) se detienen inmediatamente. 
        // Esto evita la duplicidad de logs y notificaciones.
        serviceScope.cancel() 
        
        alarmPlayer.stop()
        repository.disconnectSocket()
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Observers ─────────────────────────────────────────────────────

    private fun observeConnectionState() {
        repository.connectionState
            .onEach { state ->
                // FIX PROBLEMA 1: comparar con el enum, no con .name string
                val isConnected = state == ConnectionState.CONNECTED
                Log.d(TAG, "ConnectionState cambió: $state → isConnected=$isConnected")
                notificationHelper.updateForegroundNotification(isConnected, currentDeviceAlias)
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha ALARM_ACTIVATE.
     * Si ya hay un sonido activo, responde con ERROR según la documentación.
     * Si no, reproduce la alarma y lanza la notificación.
     */
    private fun observeAlarmEvents() {
        repository.alarmEvents
            .onEach { event ->
                Log.d(TAG, "ALARM_ACTIVATE recibido: ${event.deviceAlias}")
                Log.d(TAG, "  Duración: ${event.durationSeconds} segundos")
                if (alarmPlayer.isPlaying()) return@onEach
                alarmPlayer.playAlarm(durationSeconds = event.durationSeconds)
                notificationHelper.showAlarmNotification(event.deviceAlias)
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha MESSAGE_RECEIVE.
     * Guarda el mensaje en Room y lanza la notificación push.
     * La pantalla MessagesScreen se actualizará automáticamente
     * gracias al Flow de Room.
     */
    private fun observeMessageEvents() {
        repository.messageEvents
            .onEach { event ->
                Log.d(TAG, "MESSAGE_RECEIVE: ${event.sender} → ${event.message}")
                alarmPlayer.playMessage()
                repository.saveMessage(event, forceRead = repository.isMessagesScreenActive.value)

                if (!repository.isMessagesScreenActive.value) {
                    notificationHelper.showMessageNotification(event.sender, event.message)
                }
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha PING.
     * Reproduce el sonido corto y espera 3 segundos antes de enviar PONG,
     * tal como especifica la documentación.
     */
    private fun observePingEvents() {
        repository.pingEvents
            .onEach {
                Log.d(TAG, "PING recibido por el servicio")
                if (alarmPlayer.isPlaying()) return@onEach
                alarmPlayer.playPing()

                // Espera los 3 segundos del sonido antes de responder PONG
                serviceScope.launch {
                    delay(1500)
                    repository.sendPong()
                    Log.d(TAG, "PONG enviado")
                }
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha CHECK_FOR_UPDATE.
     * Responde al servidor con el resultado de la verificación, incluso si falla.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun observeCheckUpdateEvents() {
        repository.checkUpdateEvents
            .onEach { ack ->
                Log.d(TAG, "CHECK_FOR_UPDATE recibido")
                serviceScope.launch {
                    val result = updateChecker.checkAndNotify()
                    ack?.call(result)
                    Log.d(TAG, "Respuesta a CHECK_FOR_UPDATE enviada: $result")
                }
            }
            .launchIn(serviceScope)
    }

    /**
     * FIX PROBLEMA 2: orden correcto de operaciones en modo mantenimiento.
     *
     * Orden crítico:
     *  1. Persistir estado
     *  2. Deshabilitar reconexión (flag interno + Socket.IO Manager)
     *  3. Desconectar (disconnect() ya hace el off() y null internamente)
     *  4. Actualizar notificación
     *  5. Programar JobService
     */
    private fun observeMaintenanceEvents() {
        repository.maintenanceEvents
            .onEach { event ->
                Log.w(TAG, "SET_MAINTENANCE_MODE recibido. Hasta: ${event.untilTimestampMs}")

                // 1. Persistir ANTES de desconectar
                repository.setMaintenanceMode(true, event.untilTimestampMs)

                // 2. Deshabilitar reconexión PRIMERO
                repository.disableSocketReconnection()

                // 3. Desconectar
                repository.disconnectSocket()

                // 4. Notificación
                notificationHelper.updateMaintenanceNotification(event.untilTimestampMs)

                // 5. Programar Job
                StatusCheckJobService.schedule(applicationContext, event.untilTimestampMs)

                Log.w(TAG, "Socket desconectado. Job programado.")
            }
            .launchIn(serviceScope)
    }

    /**
     * FIX: En lugar de drop(1), comparamos la URL emitida contra
     * la URL que el socket está usando actualmente.
     *
     * drop(1) falla porque:
     *  - Solo descarta el primer valor POR SUSCRIPCIÓN, no por vida del Flow.
     *  - Si el servicio se recrea (START_STICKY), el nuevo observer hace
     *    otro drop(1) y DataStore re-emite el valor guardado → reconexión falsa.
     *
     * distinctUntilChanged() + comparación explícita garantizan que solo
     * reconectamos si la URL realmente es diferente a la que ya usamos.
     */
    private fun observeServerUrlChanges() {
        repository.serverUrl
            .distinctUntilChanged()
            .onEach { newUrl ->
                if (newUrl == activeSocketUrl) {
                    // Misma URL — no hacer nada (arranque normal o recreación del servicio)
                    Log.d(TAG, "URL sin cambio real ($newUrl), ignorando")
                    return@onEach
                }

                // URL genuinamente diferente a la activa
                Log.d(TAG, "URL cambiada: '$activeSocketUrl' → '$newUrl'. Reconectando.")
                activeSocketUrl = newUrl
                repository.reconnectWithNewUrl(newUrl)
            }
            .launchIn(serviceScope)
    }
}
