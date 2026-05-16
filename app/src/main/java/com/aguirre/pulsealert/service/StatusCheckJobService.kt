package com.aguirre.pulsealert.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aguirre.pulsealert.core.RepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.pow

private const val TAG = "StatusCheckJobService"

/**
 * JobService que verifica periódicamente el endpoint /status del servidor
 * mientras el dispositivo está en modo mantenimiento.
 *
 * Flujo:
 *  - Se programa desde SocketForegroundService al recibir SET_MAINTENANCE_MODE.
 *  - Al despertar, hace GET /status.
 *  - Si el servidor responde ACTIVE  → reconecta el socket y limpia el estado.
 *  - Si responde MAINTENANCE         → reprograma para el nuevo timestamp.
 *  - Si falla / error                → backoff exponencial hasta MAX_FAIL_ATTEMPTS.
 */
class StatusCheckJobService : JobService() {

    companion object {
        private const val JOB_ID = 1001
        private const val INITIAL_BACKOFF_MINUTES = 1L
        private const val MAX_BACKOFF_MINUTES     = 240L  // 4 horas
        private const val MAX_FAIL_ATTEMPTS       = 12    // ~1.5 días de reintentos

        /**
         * Programa el Job para ejecutarse en el momento indicado.
         * Si ya existe un Job con el mismo ID, lo reemplaza.
         *
         * @param context    Cualquier Context.
         * @param untilMs    Timestamp en ms cuando termina el mantenimiento.
         */
        fun schedule(context: Context, untilMs: Long) {
            val delayMs = (untilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, StatusCheckJobService::class.java))
                .setMinimumLatency(delayMs)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)  // sobrevive reinicios del dispositivo
                .build()

            if (jobScheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Job programado en ${TimeUnit.MILLISECONDS.toMinutes(delayMs)} minutos")
            } else {
                Log.e(TAG, "Fallo al programar el Job")
            }
        }

        /**
         * Cancela el Job si está pendiente.
         * Llamado cuando el servidor vuelve ACTIVE y ya no es necesario.
         */
        fun cancel(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.i(TAG, "Job cancelado")
        }
    }

    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var failCount = 0

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Iniciando verificación de estado del servidor")

        jobScope.launch {
            val repository   = RepositoryProvider.get(applicationContext)
            val statusUrl = repository.statusUrl.first()
            val notifHelper  = NotificationHelper(applicationContext)

            performStatusCheck(params, statusUrl, notifHelper)
        }

        return true // trabajo asíncrono en curso
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.w(TAG, "Job detenido por el sistema")
        jobScope.cancel()
        return true // reprogramar si el sistema lo canceló
    }

    // ── Lógica principal ──────────────────────────────────────────────

    private suspend fun performStatusCheck(
        params: JobParameters?,
        statusUrl: String,
        notifHelper: NotificationHelper
    ) {

        var connection: HttpURLConnection? = null

        try {
            connection = (withContext(Dispatchers.IO) {
                URL(statusUrl).openConnection()
            } as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept", "application/json")
            }

            Log.i(TAG, "GET $statusUrl → ${connection.responseCode}")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json         = JSONObject(connection.inputStream.bufferedReader().readText())
                when (val serverStatus = json.optString("status", "UNKNOWN")) {
                    "ACTIVE" -> handleActive(params, notifHelper)

                    "MAINTENANCE" -> {
                        val untilMs = json.optLong("untilTimestampMs", 0L)
                        if (untilMs > System.currentTimeMillis()) {
                            handleMaintenance(params, untilMs, notifHelper)
                        } else {
                            Log.w(TAG, "Timestamp de mantenimiento inválido, usando backoff")
                            handleFailure(params, notifHelper)
                        }
                    }

                    else -> {
                        Log.w(TAG, "Estado desconocido: $serverStatus")
                        handleFailure(params, notifHelper)
                    }
                }
            } else {
                Log.e(TAG, "HTTP ${connection.responseCode}")
                handleFailure(params, notifHelper)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar con /status: ${e.message}")
            handleFailure(params, notifHelper)
        } finally {
            connection?.disconnect()
        }
    }

    // ── Handlers de resultado ─────────────────────────────────────────

    /**
     * El servidor está activo — reconectar socket y limpiar estado.
     */
    private fun handleActive(params: JobParameters?, notifHelper: NotificationHelper) {
        Log.i(TAG, "Servidor ACTIVE. Reconectando...")

        val repository = RepositoryProvider.get(applicationContext)
        repository.enableSocketReconnection()  // rehabilitar antes de conectar
        repository.connectSocket()

        // Limpiar estado de mantenimiento en DataStore
        jobScope.launch { repository.setMaintenanceMode(false) }

        // Restaurar notificación normal
        notifHelper.clearMaintenanceNotification()

        // Relanzar el ForegroundService para que retome sus observers
        val serviceIntent = Intent(applicationContext, SocketForegroundService::class.java)
        applicationContext.startForegroundService(serviceIntent)

        jobFinished(params, false)
    }

    /**
     * El servidor sigue en mantenimiento con un nuevo timestamp.
     * Actualiza la notificación y reprograma el Job.
     */
    private fun handleMaintenance(params: JobParameters?, untilMs: Long, notifHelper: NotificationHelper) {
        Log.i(TAG, "Servidor en MAINTENANCE hasta: $untilMs")

        failCount = 0
        jobScope.launch {
            RepositoryProvider.get(applicationContext).setMaintenanceMode(true, untilMs)
        }

        notifHelper.updateMaintenanceNotification(untilMs)
        schedule(applicationContext, untilMs)
        jobFinished(params, false)
    }

    /**
     * Error de red o estado desconocido — backoff exponencial.
     * Si se supera MAX_FAIL_ATTEMPTS, se deja de intentar.
     */
    private fun handleFailure(params: JobParameters?, notifHelper: NotificationHelper) {
        failCount++
        Log.w(TAG, "Fallo #$failCount")

        if (failCount >= MAX_FAIL_ATTEMPTS) {
            Log.e(TAG, "Máximo de reintentos alcanzado. Apagando el servicio.")
            jobScope.launch {
                RepositoryProvider.get(applicationContext).setMaintenanceMode(false)
            }
            notifHelper.clearMaintenanceNotification()

            // Detener el ForegroundService — reconexión manual desde la app
            val stopIntent = Intent(applicationContext, SocketForegroundService::class.java)
            applicationContext.stopService(stopIntent)

            jobFinished(params, false)
            return
        }

        val backoffMinutes = (INITIAL_BACKOFF_MINUTES * 2.0.pow((failCount - 1)
            .coerceAtLeast(0)).toLong())
            .coerceAtMost(MAX_BACKOFF_MINUTES)

        Log.i(TAG, "Reintentando en $backoffMinutes minutos")
        schedule(applicationContext, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(backoffMinutes))
        jobFinished(params, false)
    }
}