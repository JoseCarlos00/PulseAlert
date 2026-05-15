package com.aguirre.pulsealert.data.remote

import android.util.Log
import com.aguirre.pulsealert.core.AppConfig
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.Channel
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

    // —— Flows de eventos entrantes ————————
    // Usamos extraBufferCapacity=64 para que tryEmit() sea fiable incluso
    // si el colector está momentáneamente ocupado o procesando.

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
        if (socket?.connected() == true) {
            Log.d(TAG, "Ya conectado, ignorando llamada a connect()")
            return
        }

        try {
            val options = IO.Options.builder()
                .setQuery("clientType=ANDROID_APP&apiKey=$apiKey")
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(3000)       // 3s entre reintentos
                .setReconnectionDelayMax(30000)   // máximo 30s de espera
                .build()

            socket = IO.socket(URI.create(serverUrl), options).apply {
                registerConnectionListeners(this)
                registerIncomingEventListeners(this)
                connect()
            }

            _connectionState.tryEmit(ConnectionState.CONNECTING)
            Log.d(TAG, "Intentando conectar a $serverUrl")

        } catch (e: Exception) {
            Log.e(TAG, "Error al crear el socket: ${e.message}")
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
            val payload = args?.firstOrNull() as? JSONObject

            // El payload es opcional según la documentación.
            val event = if (payload != null) {
                AlarmEvent(
                    durationSeconds = payload.optInt("durationSeconds", 10),
                    deviceAlias = payload.optString("deviceAlias", "desconocido")
                )
            } else {
                AlarmEvent() // valores por defecto
            }

            Log.d(TAG, "ALARM_ACTIVATE recibido: $event")
            _alarmEvents.tryEmit(event)

            // Responde al servidor con ACK { status: "OK" }
            // El callback está en el último argumento si el servidor lo envió.
            val ack = args?.lastOrNull()
            if (ack is io.socket.emitter.Emitter.Listener) {
                // Socket.IO Android maneja el ACK automáticamente si el
                // servidor lo envía con callback. Ver nota abajo (*)
            }
        }

        // PING
        socket.on(SocketEvents.Incoming.PING) { args ->
            Log.d(TAG, "PING recibido")
            _pingEvents.tryEmit(Unit)

            // El servidor espera { status: "PONG" } después de 3 segundos.
            // El ForegroundService se encarga del delay y de enviar la respuesta
            // a través de sendPong(), para no bloquear este hilo de callback.
        }

        // MESSAGE_RECEIVE
        socket.on(SocketEvents.Incoming.MESSAGE_RECEIVE) { args ->
            val ack = args?.lastOrNull() as? Ack
            val payload = args?.firstOrNull() as? JSONObject ?: return@on

            val message = payload.optString("message", "")
            if (message.isBlank()) {
                Log.w(TAG, "MESSAGE_RECEIVE recibido con mensaje vacío")
                return@on
            }

            val event = MessageEvent(
                message = message,
                sender = payload.optString("sender", "Nuevo Mensaje")
            )

            val response = JSONObject()
            response.put("status", "OK")

            Log.d(TAG, "MESSAGE_RECEIVE: ${event.sender} → ${event.message}")

            _messageEvents.tryEmit(event)
            ack?.call(response)
        }

        // CHECK_FOR_UPDATE
        socket.on(SocketEvents.Incoming.CHECK_FOR_UPDATE) {
            Log.d(TAG, "CHECK_FOR_UPDATE recibido")
            _checkUpdateEvents.tryEmit(Unit)
        }

        // GET_DEVICE_INFO
        // El servidor espera el mismo payload que REGISTER_DEVICE.
        // Respondemos directamente aquí porque no necesita lógica externa.
        socket.on(SocketEvents.Incoming.GET_DEVICE_INFO) { args ->
            Log.d(TAG, "GET_DEVICE_INFO recibido")
            val response = JSONObject().apply {
                put("androidId", androidId)
                put("ipAddress", ipAddress)
                put("appVersion", appVersion)
            }
            // El ACK se envía emitiendo de vuelta al callback del servidor.
            // La librería Socket.IO Android lo maneja si el último arg es un Ack.
            val ack = args?.lastOrNull() as? io.socket.client.Ack
            ack?.call(response)
        }
    }

    // ── Emisión de eventos ────────────────────────────────────────────

    /**
     * Envía el HEARTBEAT periódico al servidor.
     * Llamado cada AppConfig.HEARTBEAT_INTERVAL_MS por el ForegroundService.
     */
    fun sendHeartbeat(battery: Int, charging: Boolean) {
        if (socket?.connected() != true) return

        val payload = JSONObject().apply {
            put("deviceId", androidId)
            put("battery", battery)
            put("charging", charging)
            put("timestamp", System.currentTimeMillis())
        }

        socket?.emit(SocketEvents.Outgoing.HEARTBEAT, payload)
        Log.d(TAG, "HEARTBEAT enviado: batería $battery% charging=$charging")
    }

    /**
     * Envía la respuesta PONG al servidor tras recibir un PING.
     * El ForegroundService llama esto después de reproducir el sonido (3s delay).
     */
    fun sendPong() {
        if (socket?.connected() != true) return
        // El PONG se envía como respuesta al ACK del PING.
        // Implementación pendiente: requiere guardar la referencia al Ack
        // del evento PING. Se completará en ForegroundService.
        Log.d(TAG, "sendPong() llamado")
    }

    /**
     * Devuelve true si el socket está actualmente conectado.
     */
    fun isConnected(): Boolean = socket?.connected() == true
}
