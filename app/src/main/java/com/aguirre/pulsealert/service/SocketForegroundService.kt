package com.aguirre.pulsealert.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "SocketForegroundService"

/**
 * Servicio que mantiene la conexión Socket.IO activa en background.
 *
 * Al ser un ForegroundService, Android lo trata con alta prioridad
 * y no lo mata por falta de memoria mientras muestre una notificación
 * persistente — que es exactamente lo que hace buildForegroundNotification().
 *
 * Ciclo de vida:
 *  onCreate()       → inicializa dependencias
 *  onStartCommand() → conecta el socket y lanza las coroutines
 *  onDestroy()      → desconecta y cancela todo limpiamente
 *
 * Coroutines:
 *  El servicio tiene su propio CoroutineScope con SupervisorJob.
 *  SupervisorJob garantiza que si una coroutine falla, las demás
 *  siguen funcionando (ej. el heartbeat no cancela el listener de alarmas).
 *
 * Declarar en AndroidManifest.xml:
 *  <service
 *      android:name=".service.SocketForegroundService"
 *      android:foregroundServiceType="connectedDevice"
 *      android:exported="false" />
 */
class SocketForegroundService : Service() {

    private lateinit var repository: DeviceRepository
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var notificationHelper: NotificationHelper

    // Scope propio del servicio. Se cancela en onDestroy().
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Job del heartbeat, guardado para poder cancelarlo si es necesario
    private var heartbeatJob: Job? = null

    // ── Ciclo de vida ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        repository         = RepositoryProvider.get(applicationContext)
        alarmPlayer        = AlarmPlayer(applicationContext)
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // Lanza la notificación persistente e inicia el ForegroundService.
        // DEBE llamarse antes de 5 segundos desde onCreate() o Android
        // matará el servicio con ANR.
        startForeground(
            NOTIF_ID_FOREGROUND,
            notificationHelper.buildForegroundNotification(isConnected = false)
        )

        // Conecta el socket
        repository.connectSocket()

        // Lanza todos los listeners como coroutines independientes
        observeConnectionState()
        observeAlarmEvents()
        observeMessageEvents()
        observePingEvents()
        observeCheckUpdateEvents()
        startHeartbeat()

        // START_STICKY: si Android mata el servicio, lo reinicia
        // automáticamente con Intent null
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy — desconectando socket")

        alarmPlayer.stop()
        repository.disconnectSocket()
        heartbeatJob?.cancel()
        // SupervisorJob se cancela automáticamente con todas sus coroutines
    }

    // ForegroundService no soporta binding en este caso
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Observers ────────────────────────────────────────────────────

    private fun observeConnectionState() {
        repository.connectionState
            .onEach { state ->
                Log.d(TAG, "Estado de conexión: $state")
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
                Log.d(TAG, "ALARM_ACTIVATE recibido: $event")

                if (alarmPlayer.isPlaying()) {
                    Log.w(TAG, "Alarma ignorada: ya hay un sonido activo")
                    // El ACK de error lo maneja SocketDataSource directamente
                    return@onEach
                }

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

                // Guarda en Room (MessagesScreen se actualiza sola)
                repository.saveMessage(event)

                // Notificación push que abre MessagesScreen al tocarla
                notificationHelper.showMessageNotification(
                    sender  = event.sender,
                    message = event.message
                )
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
                Log.d(TAG, "PING recibido — reproduciendo ping")

                if (alarmPlayer.isPlaying()) {
                    Log.w(TAG, "Ping ignorado: ya hay un sonido activo")
                    return@onEach
                }

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
                // TODO: implementar lógica de actualización interna
                // Ej: mostrar notificación con enlace a la nueva APK
            }
            .launchIn(serviceScope)
    }

    // ── Heartbeat ─────────────────────────────────────────────────────

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
        val intent = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val batteryPct = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            -1
        }

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }
}