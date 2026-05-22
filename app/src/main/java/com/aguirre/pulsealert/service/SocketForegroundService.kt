package com.aguirre.pulsealert.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.aguirre.pulsealert.core.AppConfig
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.repository.DeviceRepository
import com.aguirre.pulsealert.service.NotificationHelper.Companion.NOTIF_ID_FOREGROUND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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

    private var heartbeatJob: Job? = null

    // ── Ciclo de vida ─────────────────────────────────────────────────

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
        //startHeartbeat()
        observeMaintenanceEvents()
        observeServerUrlChanges()

        // Detectar mantenimiento cuando el socket falla 10 veces consecutivas
        repository.setOnMaintenanceDetectedListener { _ ->
            serviceScope.launch {
                // No tenemos timestamp del servidor — consultamos /status
                // El JobService arranca inmediatamente (delayMs = 0)
                Log.w(TAG, "10 fallos detectados. Lanzando StatusCheckJobService.")
                repository.setMaintenanceMode(true, 0L)
                repository.disableSocketReconnection()
                notificationHelper.updateMaintenanceNotification(0L)
                StatusCheckJobService.schedule(applicationContext, System.currentTimeMillis())
            }
        }

        repository.setOnDeviceAliasReceivedListener { alias ->
            serviceScope.launch {
                Log.d(TAG, "Alias recibido del servidor: $alias")
                repository.saveDeviceAlias(alias)
            }
        }

        // Verificar actualización al arrancar
        serviceScope.launch {
            updateChecker.checkAndNotify()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Recibido intent")

        val notification = notificationHelper.buildForegroundNotification(isConnected = false)

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
                    repository.connectSocket()
                }
            }
        } else {
            // Socket ya conectado — solo actualizar la notificación al estado real
            notificationHelper.updateForegroundNotification(isConnected = true)
        }

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

    // —— Observers (sin cambios en la lógica interna) ————

    private fun observeConnectionState() {
        repository.connectionState
            .onEach { state ->
                val isConnected = state.name == "CONNECTED"
                notificationHelper.updateForegroundNotification(isConnected)
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
                Log.d(TAG, "MESSAGE_RECEIVE único: ${event.sender} → ${event.message}")

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
                    delay(3000)
                    repository.sendPong()
                    Log.d(TAG, "PONG enviado")
                }
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha CHECK_FOR_UPDATE.
     * En una app interna sin Play Store, esto puede usarse para
     * notificar al usuario que hay una nueva APK disponible en el servidor.
     * Por ahora loga el evento — puedes expandirlo según tus necesidades.
     */
    private fun observeCheckUpdateEvents() {
        repository.checkUpdateEvents
            .onEach {
                Log.d(TAG, "CHECK_FOR_UPDATE recibido")
                serviceScope.launch {
                    updateChecker.checkAndNotify()
                }
            }
            .launchIn(serviceScope)
    }

    /**
     * Escucha SET_MAINTENANCE_MODE.
     * Orquesta la desconexión limpia del socket, persiste el estado,
     * muestra la notificación y programa el JobService para despertar.
     */
    private fun observeMaintenanceEvents() {
        repository.maintenanceEvents
            .onEach { event ->
                Log.w(TAG, "SET_MAINTENANCE_MODE recibido. Hasta: ${event.untilTimestampMs}")

                // 1. Persistir estado ANTES de desconectar
                repository.setMaintenanceMode(true, event.untilTimestampMs)

                // 2. Deshabilitar reconexión automática y desconectar
                repository.disableSocketReconnection()
                repository.disconnectSocket()

                // 3. Notificación visible para el usuario
                notificationHelper.updateMaintenanceNotification(event.untilTimestampMs)

                // 4. Programar el Job para cuando termine el mantenimiento
                StatusCheckJobService.schedule(applicationContext, event.untilTimestampMs)

                Log.w(TAG, "Socket desconectado. Job programado.")
            }
            .launchIn(serviceScope)
    }

    /**
     * Observa cambios en la URL del servidor.
     * drop(1) ignora el valor inicial — solo reacciona a cambios reales
     * que ocurren mientras el servicio está corriendo.
     */
    private fun observeServerUrlChanges() {
        repository.serverUrl
            .drop(1)
            .onEach { newUrl ->
                Log.d(TAG, "URL del servidor cambiada. Reconectando con: $newUrl")
                repository.reconnectWithNewUrl(newUrl)
            }
            .launchIn(serviceScope)
    }

    /**
     * Envía el estado del dispositivo al servidor cada 45 segundos.
     * Lee la batería del dispositivo en cada ciclo para tener el valor actual.
     */
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (true) {
                if (repository.isSocketConnected()) {
                    val (battery, charging) = getBatteryInfo()
                    repository.sendHeartbeat(battery, charging)
                    Log.d(TAG, "HEARTBEAT enviado: $battery% charging=$charging")
                }
                delay(AppConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Lee el nivel de batería y estado de carga del sistema.
     * Devuelve un par (porcentaje: Int, cargando: Boolean).
     */
    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(pct, charging)
    }
}
