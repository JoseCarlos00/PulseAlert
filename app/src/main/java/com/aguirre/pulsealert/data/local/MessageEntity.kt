package com.aguirre.pulsealert.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabla Room que representa un mensaje recibido desde el servidor.
 *
 * Cada fila es un mensaje persistente que el usuario puede consultar
 * en MessagesScreen aunque la app haya sido cerrada y vuelta a abrir.
 *
 * Campos:
 *  - id:        Autogenerado por Room. No necesitas asignarlo al insertar.
 *  - message:   Contenido del mensaje recibido en MESSAGE_RECEIVE.
 *  - sender:    Remitente opcional. Default "Nuevo Mensaje" según la API.
 *  - timestamp: Momento en que se recibió el mensaje (epoch millis).
 *               Se usa para ordenar y mostrar "hace X minutos".
 *  - isRead:    false cuando llega, true cuando el usuario abre MessagesScreen.
 *               Útil para mostrar un badge de no leídos en la BottomBar.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val message: String,
    val sender: String = "Nuevo Mensaje",
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)