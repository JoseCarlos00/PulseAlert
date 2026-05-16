package com.aguirre.pulsealert.data.remote

import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.net.URI

private const val TAG = "SocketDataSource"

// ── Modelos de eventos entrantes ─────────────────────────────────────────────

/**
 * Datos del evento ALARM_ACTIVATE recibido desde el servidor.
 */
data class AlarmEvent(
    val durationSeconds: Int = 10,
    val deviceAlias: String = "desconocido"
)

/**
 * Datos del evento MESSAGE_RECEIVE recibido desde el servidor.
 */
data class MessageEvent(
    val message: String,
    val sender: String = "Nuevo Mensaje"
)

/**
 * Datos del evento SET_MAINTENANCE_MODE recibido desde el servidor.
 */
data class MaintenanceEvent(
    val untilTimestampMs: Long,
    val reason: String = "",
    val estimatedDuration: Int = 0
)

/**
 * Estado de la conexión Socket.IO.
 * El ForegroundService y el HomeViewModel observan este estado.
 */
enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

// ── SocketDataSource ──────────────────────────────────────────────────────────

/**
 * Encapsula toda la lógica del cliente Socket.IO.
 *
 * Responsabilidades:
 *  - Crear y mantener la conexión con el servidor.
 *  - Registrar el dispositivo tras conectar (REGISTER_DEVICE).
 *  - Escuchar eventos entrantes y exponerlos como Flows.
 *  - Enviar el HEARTBEAT periódico (lo orquesta el ForegroundService).
 *  - Desconectarse limpiamente.
 *
 * NO reproduce audio ni lanza notificaciones — eso es responsabilidad
 * del ForegroundService, que consume los Flows de esta clase.
 *
 * @param serverUrl   URL del servidor Socket.IO (desde AppPreferences).
 * @param apiKey      API Key para el handshake (desde AppPreferences).
 * @param androidId   ID único del dispositivo Android.
 * @param deviceAlias Alias del dispositivo (desde AppPreferences).
 * @param appVersion  Versión de la app (desde BuildConfig).
 * @param ipAddress   IP local del dispositivo.
 */
class SocketDataSource(
    private val serverUrl: String,
    private val apiKey: String,
    private val androidId: String,
    private val deviceAlias: String,
    private val appVersion: String,
    private val ipAddress: String
) {
    private var socket: Socket? = null

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: Flow<ConnectionState> = _connectionState.asSharedFlow()

    private val _alarmEvents = MutableSharedFlow<AlarmEvent>(replay = 0, extraBufferCapacity = 64)
    val alarmEvents: Flow<AlarmEvent> = _alarmEvents.asSharedFlow()

    private val _messageEvents = MutableSharedFlow<MessageEvent>(replay = 0, extraBufferCapacity = 64)
    val messageEvents: Flow<MessageEvent> = _messageEvents.asSharedFlow()

    private val _pingEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 64)
    val pingEvents: Flow<Unit> = _pingEvents.asSharedFlow()

    private val _checkUpdateEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 64)
    val checkUpdateEvents: Flow<Unit> = _checkUpdateEvents.asSharedFlow()

    private val _maintenanceEvents = MutableSharedFlow<MaintenanceEvent>(replay = 0, extraBufferCapacity = 64)
    val maintenanceEvents: Flow<MaintenanceEvent> = _maintenanceEvents.asSharedFlow()

    private var consecutiveFailCount = 0
    private var onMaintenanceDetected: ((Long) -> Unit)? = null

    // ── Conexión ──────────────────────────────────────────────────────

    /**
     * Establece la conexión con el servidor.
     *
     * El handshake incluye:
     *  - clientType: identifica a este cliente como dispositivo Android.
     *  - apiKey: clave secreta que el servidor válida.
     *
     * Una vez conectado, registra el dispositivo con REGISTER_DEVICE.
     */
    fun connect() {
        if (socket?.connected() == true) return

        try {
            val options = IO.Options.builder()
                .setQuery("clientType=ANDROID_APP&apiKey=$apiKey")
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(3000)       // 3s entre reintentos
                .setReconnectionDelayMax(30000)   // máximo 30s de espera
                .build()

            val newSocket = IO.socket(URI.create(serverUrl), options)
            newSocket.off() 

            registerConnectionListeners(newSocket)
            registerIncomingEventListeners(newSocket)
            
            socket = newSocket
            socket?.connect()

            _connectionState.tryEmit(ConnectionState.CONNECTING)
            Log.d(TAG, "Conectando a $serverUrl...")

        } catch (e: Exception) {
            Log.e(TAG, "Error al configurar socket: ${e.message}")
            _connectionState.tryEmit(ConnectionState.ERROR)
        }
    }

    /**
     * Desconecta el socket limpiamente.
     * Llamado desde ForegroundService.onDestroy().
     */
    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        Log.d(TAG, "Socket desconectado y listeners removidos")
    }

    // ── Listeners de conexión ─────────────────────────────────────────

    private fun registerConnectionListeners(socket: Socket) {
        socket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Conectado al servidor")
            consecutiveFailCount = 0  // resetear al conectar exitosamente
            _connectionState.tryEmit(ConnectionState.CONNECTED)
            registerDevice(socket)
        }

        socket.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args?.firstOrNull()?.toString() ?: "desconocido"
            Log.d(TAG, "Desconectado: $reason")
            _connectionState.tryEmit(ConnectionState.DISCONNECTED)
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args?.firstOrNull()?.toString() ?: "error desconocido"
            Log.e(TAG, "Error de conexión: $error")
            _connectionState.tryEmit(ConnectionState.ERROR)

            consecutiveFailCount++
            Log.w(TAG, "Fallos consecutivos: $consecutiveFailCount")

            if (consecutiveFailCount >= 10) {
                Log.w(TAG, "10 fallos consecutivos. Notificando para consultar /status")
                consecutiveFailCount = 0  // resetear para no disparar múltiples veces
                onMaintenanceDetected?.invoke(System.currentTimeMillis())
            }
        }
    }

    // ── Registro del dispositivo ──────────────────────────────────────

    /**
     * Envía REGISTER_DEVICE tras conectar exitosamente.
     * Incluye androidId, ipAddress y appVersion según la documentación.
     *
     * El servidor responde con { status: "OK" } o { status: "ERROR", reason: "..." }.
     */
    private fun registerDevice(socket: Socket) {
        val payload = JSONObject().apply {
            put("androidId", androidId)
            put("ipAddress", ipAddress)
            put("appVersion", appVersion)
        }

        socket.emit(SocketEvents.Outgoing.REGISTER_DEVICE, payload, Ack { args ->
            val response = args?.firstOrNull() as? JSONObject
            val status = response?.optString("status")

            if (status == "OK") {
                Log.d(TAG, "Dispositivo registrado correctamente")
            } else {
                val reason = response?.optString("reason") ?: "sin motivo"
                Log.e(TAG, "Error en REGISTER_DEVICE: $reason")
            }
        })
    }

    // ── Listeners de eventos entrantes ────────────────────────────────

    private fun registerIncomingEventListeners(socket: Socket) {
        
        // ALARM_ACTIVATE
        socket.on(SocketEvents.Incoming.ALARM_ACTIVATE) { args ->
            try {
                val payload = args?.firstOrNull() as? JSONObject
                val ack = args?.lastOrNull() as? Ack

                Log.d(TAG, "ALARM_ACTIVATE recibido: ${payload?.toString()}")
                
                // Extraer como Double para manejar decimales (0.2, etc)
                val rawDuration = payload?.optDouble("durationSeconds", 10.0) ?: 10.0
                
                val finalDuration = when {
                    rawDuration == 0.0 -> 0 // Infinito
                    rawDuration > 0.0 && rawDuration < 1.0 -> 1 // Mínimo 1 segundo para decimales positivos
                    rawDuration > 10.0 -> 10 // Si hay más de 10 segundos, usar 10 segundos
                    rawDuration < 0.0 -> 10 // Default para negativos
                    else -> rawDuration.toInt() // Trunca decimales mayores a 1 (ej: 5.5 -> 5)
                }

                Log.d(TAG, "ALARM_ACTIVATE procesado: raw=$rawDuration -> final=$finalDuration")

                val event = AlarmEvent(
                    durationSeconds = finalDuration,
                    deviceAlias = payload?.optString("deviceAlias", "desconocido") ?: "desconocido"
                )
                
                _alarmEvents.tryEmit(event)
                ack?.call(JSONObject().put("status", "OK"))

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando ALARM_ACTIVATE: ${e.message}")
            }
        }

        // MESSAGE_RECEIVE
        socket.on(SocketEvents.Incoming.MESSAGE_RECEIVE) { args ->
            try {
                val payload = args?.firstOrNull() as? JSONObject ?: return@on
                val message = payload.optString("message", "")

                if (message.isBlank()) {
                    Log.w(TAG, "MESSAGE_RECEIVE recibido con mensaje vacío")
                    return@on
                }
                    val event = MessageEvent(
                        message = message,
                        sender = payload.optString("sender", "Panel Control")
                    )
                    _messageEvents.tryEmit(event)

                val ack = args.lastOrNull() as? Ack
                ack?.call(JSONObject().put("status", "OK"))
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando MESSAGE_RECEIVE: ${e.message}")
            }
        }

        // GET_DEVICE_INFO
        socket.on(SocketEvents.Incoming.GET_DEVICE_INFO) { args ->
            try {
                val ack = args?.lastOrNull() as? Ack
                val response = JSONObject().apply {
                    put("androidId", androidId)
                    put("ipAddress", ipAddress)
                    put("appVersion", appVersion)
                    put("deviceAlias", deviceAlias)
                }
                ack?.call(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error en GET_DEVICE_INFO: ${e.message}")
            }
        }

        // PING
        socket.on(SocketEvents.Incoming.PING) { _pingEvents.tryEmit(Unit) }

        // CHECK_FOR_UPDATE
        socket.on(SocketEvents.Incoming.CHECK_FOR_UPDATE) { _checkUpdateEvents.tryEmit(Unit) }

        // SET_MAINTENANCE_MODE
        socket.on(SocketEvents.Incoming.SET_MAINTENANCE_MODE) { args ->
            try {
                val payload = args?.firstOrNull() as? JSONObject
                val ack     = args?.lastOrNull() as? Ack

                val untilMs = payload?.optLong("untilTimestampMs", 0L) ?: 0L

                if (untilMs > System.currentTimeMillis()) {
                    Log.w(TAG, "SET_MAINTENANCE_MODE recibido. Durmiendo hasta: $untilMs")

                    val event = MaintenanceEvent(
                        untilTimestampMs    = untilMs,
                        reason              = payload?.optString("reason", "") ?: "",
                        estimatedDuration   = payload?.optInt("estimatedDuration", 0) ?: 0
                    )
                    _maintenanceEvents.tryEmit(event)

                    // ACK al servidor antes de desconectar
                    ack?.call(JSONObject().apply {
                        put("status", "OK")
                        put("message", "Entrando en modo mantenimiento")
                    })

                } else {
                    Log.w(TAG, "SET_MAINTENANCE_MODE con timestamp inválido: $untilMs")
                    ack?.call(JSONObject().apply {
                        put("status", "ERROR")
                        put("reason", "Timestamp no válido o ya expirado.")
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando SET_MAINTENANCE_MODE: ${e.message}")
            }
        }
    }

    // ── Emisión de eventos ────────────────────────────────────────────

    /**
     * Envía el HEARTBEAT periódico al servidor.
     * Llamado cada AppConfig.HEARTBEAT_INTERVAL_MS por el ForegroundService.
     */
    fun sendHeartbeat(battery: Int, charging: Boolean) {
        val payload = JSONObject().apply {
            put("deviceId", androidId)
            put("battery", battery)
            put("charging", charging)
            put("timestamp", System.currentTimeMillis())
        }
        socket?.emit(SocketEvents.Outgoing.HEARTBEAT, payload)
    }

    /**
     * Envía la respuesta PONG al servidor tras recibir un PING.
     * El ForegroundService llama esto después de reproducir el sonido (3s delay).
     */
    fun sendPong() {
        socket?.emit(SocketEvents.Outgoing.PING, JSONObject().put("status", "PONG"))
        Log.d(TAG, "sendPong() llamado")
    }

    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * Deshabilita la reconexión automática del socket.
     * Llamado ANTES de disconnect() durante el modo mantenimiento,
     * para evitar que Socket.IO intente reconectarse solo.
     */
    fun disableReconnection() {
        socket?.io()?.reconnection(false)
        Log.d(TAG, "Reconexión automática deshabilitada")
    }

    /**
     * Rehabilita la reconexión automática del socket.
     * Llamado desde StatusCheckJobService cuando el servidor vuelve a ACTIVE.
     */
    fun enableReconnection() {
        socket?.io()?.reconnection(true)
        Log.d(TAG, "Reconexión automática rehabilitada")
    }

    /**
     * Callback que el ForegroundService asigna para ser notificado cuando
     * el contador de fallos consecutivos supera el límite.
     * Recibe el timestamp actual para que el JobService pueda programarse.
     */
    fun setOnMaintenanceDetectedListener(listener: (Long) -> Unit) {
        onMaintenanceDetected = listener
    }
}
