package com.aguirre.pulsealert.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO (Data Access Object) para la tabla "messages".
 *
 * Room genera la implementación automáticamente en tiempo de compilación.
 * Nunca instancies este interface manualmente — Room lo hace por ti
 * a través de AppDatabase.
 *
 * Todos los métodos son suspend o devuelven Flow para no bloquear
 * el hilo principal (requerimiento de Room).
 */
@Dao
interface MessageDao {

    /**
     * Inserta un nuevo mensaje en la base de datos.
     * Llamado desde SocketForegroundService al recibir MESSAGE_RECEIVE.
     *
     * OnConflictStrategy.IGNORE por seguridad, aunque en la práctica
     * los ID son autogenerados y no debería haber conflictos.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAndGetId(message: MessageEntity): Long

    /**
     * Devuelve todos los mensajes ordenados del más reciente al más antiguo.
     *
     * Flow hace que MessagesViewModel se actualice automáticamente
     * cada vez que se inserta un nuevo mensaje — sin necesidad de
     * hacer polling ni refrescar manualmente.
     */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    /**
     * Cuenta los mensajes no leídos.
     * Útil para mostrar un badge en la tab de Mensajes de la BottomBar.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    /**
     * Marca todos los mensajes como leídos.
     * Llamado cuando el usuario abre MessagesScreen.
     */
    @Query("UPDATE messages SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    /**
     * Elimina todos los mensajes. Acción disponible en MessagesScreen
     * para que el usuario limpie el historial.
     */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}