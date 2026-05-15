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

        // Iniciamos los observadores una sola vez al nacer el servicio
        observeConnectionState()
        observeAlarmEvents()
        observeMessageEvents()
        observePingEvents()
        observeCheckUpdateEvents()
        //startHeartbeat()
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

        repository.connectSocket()

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
        heartbeatJob?.cancel()
        
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

                // Guarda en Room (MessagesScreen se actualiza sola)
                repository.saveMessage(event)
                notificationHelper.showMessageNotification(event.sender, event.message)
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
            .onEach { Log.d(TAG, "CHECK_FOR_UPDATE recibido") }
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
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(pct, charging)
    }
}
