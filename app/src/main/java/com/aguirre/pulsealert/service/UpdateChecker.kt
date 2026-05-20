package com.aguirre.pulsealert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat.getLongVersionCode
import com.aguirre.pulsealert.R
import com.aguirre.pulsealert.core.RepositoryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri

private const val TAG = "UpdateChecker"

/**
 * Verifica si hay una nueva versión de la app disponible consultando
 * un JSON remoto (recomendado: GitHub Releases).
 *
 * Si hay actualización disponible, muestra una notificación push.
 * Al tocarla, descarga e instala el APK automáticamente.
 *
 * Se invoca desde SocketForegroundService en dos momentos:
 *  - Al arrancar el servicio (onCreate)
 *  - Al recibir el evento CHECK_FOR_UPDATE por socket
 */
class UpdateChecker(private val context: Context) {
    companion object {
        const val ACTION_DOWNLOAD_UPDATE     = "com.aguirre.pulsealert.ACTION_DOWNLOAD_UPDATE"
        const val EXTRA_APK_URL              = "apk_url"
    }

    /**
     * Verifica si hay actualización disponible.
     * Si la hay, muestra una notificación — no descarga automáticamente.
     * La descarga ocurre cuando el usuario toca la notificación.
     *
     * suspend → debe llamarse desde una coroutine (serviceScope).
     */
    suspend fun checkAndNotify() = withContext(Dispatchers.IO) {
        try {
            val updateUrl = RepositoryProvider.get(context).updateUrl.first()
            Log.d(TAG, "Verificando actualizaciones en: $updateUrl")

            val connection = (URL(updateUrl).openConnection() as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json             = JSONObject(connection.inputStream.bufferedReader().readText())
                val latestVersionCode = json.getLong("versionCode")
                val downloadUrl      = json.getString("downloadUrl")
                val currentVersion   = getCurrentVersionCode()

                Log.d(TAG, "Versión actual: $currentVersion | Última: $latestVersionCode")

                if (latestVersionCode > currentVersion) {
                    Log.i(TAG, "Nueva versión disponible. Mostrando notificación.")
                    showUpdateNotification(downloadUrl)
                } else {
                    Log.d(TAG, "La app está actualizada.")
                }
            } else {
                Log.w(TAG, "HTTP ${connection.responseCode} al verificar actualizaciones")
            }

            connection.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error al verificar actualización: ${e.message}")
        }
    }

    /**
     * Descarga e instala el APK.
     * Llamado desde SocketForegroundService al recibir el Intent
     * ACTION_DOWNLOAD_UPDATE (cuando el usuario toca la notificación).
     */
    suspend fun downloadAndInstall(apkUrl: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Descargando APK desde: $apkUrl")
            val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply { connect() }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val file = File(context.externalCacheDir, "update.apk")
                file.outputStream().use { fos -> connection.inputStream.copyTo(fos) }
                Log.i(TAG, "APK descargado. Iniciando instalación.")
                installApk(file)
            } else {
                Log.e(TAG, "Error al descargar APK: HTTP ${connection.responseCode}")
            }

            connection.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar APK: ${e.message}")
        }
    }

    // ── Privados ──────────────────────────────────────────────────────

    private fun showUpdateNotification(downloadUrl: String) {
        // Lanzar MainActivity con el intent de descarga
        val intent = Intent(context, com.aguirre.pulsealert.MainActivity::class.java).apply {
            action = ACTION_DOWNLOAD_UPDATE
            putExtra(EXTRA_APK_URL, downloadUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, NotificationHelper.CHANNEL_UPDATE)
            .setContentTitle("Actualización disponible")
            .setContentText("Toca para descargar e instalar la nueva versión.")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NotificationHelper.NOTIF_ID_UPDATE, notification)
    }

    private fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.e(TAG, "Permiso REQUEST_INSTALL_PACKAGES no concedido.")
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
            context.startActivity(intent)
        } else {
            Log.e(TAG, "No se encontró actividad para instalar el APK.")
        }
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else getLongVersionCode(info)
        } catch (_: Exception) { -1L }
    }
}
