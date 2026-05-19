package com.aguirre.pulsealert.data.repository


import com.aguirre.pulsealert.data.local.AppPreferences
import com.aguirre.pulsealert.data.local.MessageDao
import com.aguirre.pulsealert.data.local.MessageEntity
import com.aguirre.pulsealert.data.remote.AlarmEvent
import com.aguirre.pulsealert.data.remote.ConnectionState
import com.aguirre.pulsealert.data.remote.MaintenanceEvent
import com.aguirre.pulsealert.data.remote.MessageEvent
import com.aguirre.pulsealert.data.remote.SocketDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuente única de verdad de la app.
 *
 * Todos los ViewModels y el ForegroundService obtienen datos desde aquí.
 * Nadie habla directamente con AppPreferences, MessageDao o SocketDataSource
 * — todo pasa por el Repository.
 *
 * Responsabilidades:
 *  - Exponer los Flows de configuración (DataStore).
 *  - Exponer los Flows de mensajes (Room).
 *  - Exponer los Flows de eventos del socket.
 *  - Guardar mensajes entrantes en Room.
 *  - Proveer acciones: guardar configuración, conectar/desconectar socket.
 *
 * El Repository NO toma decisiones de negocio como reproducir audio
 * o lanzar notificaciones — eso es responsabilidad del ForegroundService.
 *
 * @param prefs         DataStore de configuración.
 * @param messageDao    DAO de Room para mensajes.
 * @param socketDataSource Cliente Socket.IO.
 */
class DeviceRepository(
    private val prefs: AppPreferences,
    private val messageDao: MessageDao,
    private val socketDataSource: SocketDataSource
) {

    // —— Configuración (DataStore) ———
    // Expuestos como Flow de solo lectura.
    // Los ViewModels observan estos Flows directamente.

    val serverUrl: Flow<String>    = prefs.serverUrl
    val statusUrl: Flow<String> = prefs.statusUrl
    val apiKey: Flow<String>       = prefs.apiKey
    val deviceAlias: Flow<String>  = prefs.deviceAlias

    val isMaintenanceMode: Flow<Boolean> = prefs.isMaintenanceMode
    val maintenanceUntilMs: Flow<Long>   = prefs.maintenanceUntilMs

    // ── Mensajes (Room) ───────────────────────────────────────────────

    /**
     * Lista de todos los mensajes ordenados del más reciente al más antiguo.
     * MessagesViewModel observa este Flow — se actualiza automáticamente
     * cuando el ForegroundService inserta un nuevo mensaje.
     */
    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()

    /**
     * Cantidad de mensajes no leídos.
     * Usado para el badge en la tab de Mensajes de la BottomBar.
     */
    val unreadCount: Flow<Int> = messageDao.getUnreadCount()

    // ── Estado del socket ─────────────────────────────────────────────

    /**
     * Estado actual de la conexión: CONNECTING, CONNECTED, DISCONNECTED, ERROR.
     * HomeViewModel lo observa para mostrar el indicador de estado.
     */
    val connectionState: Flow<ConnectionState> = socketDataSource.connectionState

    // ——— Eventos del socket ———
    // El ForegroundService se suscribe a estos Flows para reaccionar
    // a los eventos entrantes del servidor.

    val alarmEvents: Flow<AlarmEvent>  = socketDataSource.alarmEvents
    val messageEvents: Flow<MessageEvent> = socketDataSource.messageEvents
    val pingEvents: Flow<Unit>         = socketDataSource.pingEvents
    val checkUpdateEvents: Flow<Unit>  = socketDataSource.checkUpdateEvents
    val maintenanceEvents: Flow<MaintenanceEvent> = socketDataSource.maintenanceEvents

    // ── Acciones de configuración ─────────────────────────────────────

    suspend fun saveServerUrl(url: String)      = prefs.saveServerUrl(url)
    suspend fun saveApiKey(key: String)         = prefs.saveApiKey(key)
    suspend fun saveDeviceAlias(alias: String)  = prefs.saveDeviceAlias(alias)
    suspend fun resetPrefsToDefaults()          = prefs.resetToDefaults()
    suspend fun saveStatusUrl(url: String)      = prefs.saveStatusUrl(url)

    /**
     * Activa o desactiva el modo mantenimiento.
     * Llamado desde ForegroundService al recibir SET_MAINTENANCE_MODE,
     * o al reconectar exitosamente (active = false).
     */
    suspend fun setMaintenanceMode(active: Boolean, untilMs: Long = 0L) =
        prefs.setMaintenanceMode(active, untilMs)
    suspend fun getMaintenanceUntilMs(): Long = prefs.getMaintenanceUntilMs()

    // ── Acciones de mensajes ──────────────────────────────────────────

    /**
     * Persiste un mensaje entrante en Room.
     * Llamado desde el ForegroundService al recibir MESSAGE_RECEIVE.
     */
    suspend fun saveMessage(event: MessageEvent, forceRead: Boolean = false) {
        val id = messageDao.insertAndGetId(
            MessageEntity(
                message = event.message,
                sender  = event.sender,
                isRead  = forceRead
            )
        )
        if (forceRead) markMessageAsNew(id.toInt())
    }

    /**
     * Marca todos los mensajes como leídos.
     * Llamado desde MessagesViewModel cuando el usuario abre la pantalla.
     */
    suspend fun markAllMessagesAsRead() = messageDao.markAllAsRead()

    /**
     * Elimina todos los mensajes del historial.
     * Llamado desde MessagesViewModel con el botón "Limpiar".
     */
    suspend fun deleteAllMessages() = messageDao.deleteAll()

    // ── Acciones del socket ───────────────────────────────────────────

    /**
     * Inicia la conexión Socket.IO.
     * Llamado desde ForegroundService.onStartCommand().
     */
    fun connectSocket() = socketDataSource.connect()

    /**
     * Cierra la conexión Socket.IO limpiamente.
     * Llamado desde ForegroundService.onDestroy().
     */
    fun disconnectSocket() = socketDataSource.disconnect()

    /**
     * Envía el HEARTBEAT periódico al servidor.
     * Llamado desde ForegroundService cada 45 segundos.
     */
    fun sendHeartbeat(battery: Int, charging: Boolean) =
        socketDataSource.sendHeartbeat(battery, charging)

    /**
     * Envía la respuesta PONG tras reproducir el sonido de PING.
     * Llamado desde ForegroundService después del delay de 3 segundos.
     */
    fun sendPong() = socketDataSource.sendPong()

    fun isSocketConnected(): Boolean = socketDataSource.isConnected()

    fun disableSocketReconnection() = socketDataSource.disableReconnection()
    fun enableSocketReconnection() = socketDataSource.enableReconnection()

    fun setOnMaintenanceDetectedListener(listener: (Long) -> Unit) =
        socketDataSource.setOnMaintenanceDetectedListener(listener)

    // ── Estado de UI ──────────────────────────────────────────────────────

    private val _isMessagesScreenActive = MutableStateFlow(false)
    val isMessagesScreenActive: StateFlow<Boolean> = _isMessagesScreenActive

    private val _newMessageIds = MutableStateFlow<Set<Int>>(emptySet())
    val newMessageIds: StateFlow<Set<Int>> = _newMessageIds

    fun setMessagesScreenActive(active: Boolean) {
        _isMessagesScreenActive.value = active
    }

    fun markMessageAsNew(messageId: Int) {
        _newMessageIds.value += messageId
    }

    fun clearNewMessage(messageId: Int) {
        _newMessageIds.value -= messageId
    }
}