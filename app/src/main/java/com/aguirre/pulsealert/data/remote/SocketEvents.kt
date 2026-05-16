package com.aguirre.pulsealert.data.remote

/**
 * Constantes de todos los eventos del protocolo Socket.IO.
 *
 * Centralizar los nombres aquí evita typos dispersos por el código.
 * Si el servidor cambia el nombre de un evento, solo se cambia aquí.
 *
 * Separado en dos objetos según la dirección del evento:
 *  - Incoming: eventos que el servidor envía y la app escucha.
 *  - Outgoing: eventos que la app envía al servidor.
 */
object SocketEvents {

    /**
     * Eventos que llegan desde el servidor → la app los escucha.
     * Referencia: Documentación de la API de WebSocket - AlertScanner.
     */
    object Incoming {
        const val ALARM_ACTIVATE      = "ALARM_ACTIVATE"
        const val PING                = "PING"
        const val MESSAGE_RECEIVE     = "MESSAGE_RECEIVE"
        const val CHECK_FOR_UPDATE    = "CHECK_FOR_UPDATE"
        const val GET_DEVICE_INFO     = "GET_DEVICE_INFO"
        const val SET_MAINTENANCE_MODE = "SET_MAINTENANCE_MODE"
    }

    /**
     * Eventos que la app envía al servidor.
     * Referencia: Documentación AUTH Arquitectura Web + Android.
     */
    object Outgoing {
        const val REGISTER_DEVICE = "REGISTER_DEVICE"
        const val HEARTBEAT       = "HEARTBEAT"
        const val PING = "PING"
    }
}