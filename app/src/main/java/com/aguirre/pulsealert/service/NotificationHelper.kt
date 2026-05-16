package com.aguirre.pulsealert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aguirre.pulsealert.MainActivity
import com.aguirre.pulsealert.R

/**
 * Centraliza la creación de notificaciones push y la notificación
 * persistente del ForegroundService.
 *
 * Android requiere NotificationChannels desde API 26 (Android 8).
 * Cada canal tiene su propia configuración de sonido, vibración e
 * importancia — se crean una sola vez en init{}.
 *
 * Canales:
 *  - CHANNEL_FOREGROUND: notificación silenciosa y persistente del servicio.
 *  - CHANNEL_ALARM:      notificación de alarma crítica, alta prioridad.
 *  - CHANNEL_MESSAGE:    notificación de mensaje recibido.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        // IDs de canales
        const val CHANNEL_FOREGROUND = "pulsealert_foreground"
        const val CHANNEL_ALARM      = "pulsealert_alarm"
        const val CHANNEL_MESSAGE    = "pulsealert_message"
        const val CHANNEL_MAINTENANCE = "pulsealert_maintenance"

        // IDs de notificaciones
        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_ALARM      = 2
        const val NOTIF_ID_MESSAGE    = 3
        const val NOTIF_ID_MAINTENANCE = 4

        // Extra para el Intent — indica qué pantalla abrir
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val NAV_MESSAGES      = "messages"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    // ── Notificación del ForegroundService ────────────────────────────

    /**
     * Crea la notificación persistente que mantiene vivo el servicio.
     * Es silenciosa y de baja prioridad para no molestar al usuario.
     * Se muestra siempre que el servicio esté activo.
     */
    fun buildForegroundNotification(isConnected: Boolean): Notification {
        val statusText = if (isConnected) "Conectado al servidor" else "Reconectando..."

        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setContentTitle("PulseAlert activo")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)           // no se puede descartar deslizando
            .setSilent(true)            // sin sonido ni vibración
            .setContentIntent(buildMainIntent())
            .build()
    }

    /**
     * Actualiza el texto de la notificación persistente sin recrearla.
     * Llamado desde ForegroundService cuando cambia el estado de conexión.
     */
    fun updateForegroundNotification(isConnected: Boolean) {
        notificationManager.notify(
            NOTIF_ID_FOREGROUND,
            buildForegroundNotification(isConnected)
        )
    }

    /**
     * Reemplaza la notificación persistente con una de mantenimiento.
     * Muestra la hora estimada de regreso para que el usuario sepa qué esperar.
     *
     * @param untilTimestampMs Timestamp en ms de cuando termina el mantenimiento.
     */
    fun updateMaintenanceNotification(untilTimestampMs: Long) {
        val contentText = if (untilTimestampMs > System.currentTimeMillis()) {
            val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(untilTimestampMs))
            "Reconexión automática a las $timeText"
        } else {
            "Verificando estado del servidor..."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_MAINTENANCE)
            .setContentTitle("🔧 Servidor en mantenimiento")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(buildMainIntent())
            .build()

        // Reemplaza la notificación del foreground con el mismo ID
        notificationManager.notify(NOTIF_ID_FOREGROUND, notification)
    }

    /**
     * Restaura la notificación persistente normal después del mantenimiento.
     * Llamado desde StatusCheckJobService cuando el servidor vuelve a ACTIVE.
     */
    fun clearMaintenanceNotification() {
        updateForegroundNotification(isConnected = false)
    }

    // ── Notificación de alarma ────────────────────────────────────────

    /**
     * Lanza una notificación de alarma crítica con alta prioridad.
     * Aparece como heads-up (banner) aunque la pantalla esté apagada.
     *
     * @param deviceAlias Nombre del origen de la alarma.
     */
    fun showAlarmNotification(deviceAlias: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setContentTitle("⚠️ ALARMA ACTIVADA")
            .setContentText("Origen: $deviceAlias")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(buildMainIntent())
            .build()

        notificationManager.notify(NOTIF_ID_ALARM, notification)
    }

    // ── Notificación de mensaje ───────────────────────────────────────

    /**
     * Lanza una notificación de mensaje recibido.
     * Al tocarla, abre la app directamente en MessagesScreen.
     *
     * @param sender  Nombre del remitente.
     * @param message Contenido del mensaje.
     */
    fun showMessageNotification(sender: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setContentTitle(sender)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Deep link: abre MessagesScreen al tocar la notificación
            .setContentIntent(buildMessagesIntent())
            .build()

        notificationManager.notify(NOTIF_ID_MESSAGE, notification)
    }

    // ── Helpers privados ──────────────────────────────────────────────

    /**
     * Intent que abre MainActivity sin navegar a ninguna pantalla específica.
     */
    private fun buildMainIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Intent que abre MainActivity con el extra EXTRA_NAVIGATE_TO = "messages".
     * MainActivity lo leerá en onNewIntent() y navegará a MessagesScreen.
     */
    private fun buildMessagesIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_TO, NAV_MESSAGES)
        }
        return PendingIntent.getActivity(
            context,
            1,  // requestCode diferente al de buildMainIntent
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Crea los canales de notificación.
     * Android ignora esta llamada si los canales ya existen,
     * por lo que es seguro llamarla múltiples veces.
     */
    private fun createChannels() {

        val channels = listOf(
            NotificationChannel(
                CHANNEL_FOREGROUND,
                "Servicio en segundo plano",
                NotificationManager.IMPORTANCE_LOW  // sin sonido, sin vibración
            ).apply {
                description = "Notificación persistente que mantiene la conexión activa"
            },

            NotificationChannel(
                CHANNEL_ALARM,
                "Alarmas críticas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de alarma activadas desde el servidor"
                enableVibration(true)
            },

            NotificationChannel(
                CHANNEL_MESSAGE,
                "Mensajes recibidos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mensajes enviados desde el panel web"
                enableVibration(true)
            },

            NotificationChannel(
                CHANNEL_MAINTENANCE,
                "Modo mantenimiento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación activa durante el mantenimiento del servidor"
            }
        )

        channels.forEach { notificationManager.createNotificationChannel(it) }
    }
}