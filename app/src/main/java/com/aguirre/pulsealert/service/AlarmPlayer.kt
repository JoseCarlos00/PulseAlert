package com.aguirre.pulsealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.aguirre.pulsealert.R

private const val TAG = "AlarmPlayer"

/**
 * Reproduce sonidos de alarma y ping con máximo volumen,
 * ignorando el modo "No Molestar" del dispositivo.
 *
 * Maneja dos tipos de sonido:
 *  - Alarma crítica: sonido persistente, duración configurable.
 *  - Ping: sonido corto fijo de 3 segundos.
 *
 * Uso desde ForegroundService:
 *   alarmPlayer.playAlarm(durationSeconds = 30)
 *   alarmPlayer.playPing()
 *   alarmPlayer.stop()  // si necesitas detenerlo antes
 *
 * IMPORTANTE: Añadir en res/raw/ los archivos de audio:
 *   - alarm_sound.mp3  (sonido de alarma)
 *   - ping_sound.mp3   (sonido corto de ping)
 * Si no existen, usará el tono de alarma del sistema como fallback.
 */
class AlarmPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Guarda el volumen original para restaurarlo al terminar
    private var originalVolume: Int = 0

    /**
     * Reproduce la alarma crítica.
     *
     * @param durationSeconds Duración en segundos. 0 = indefinido hasta stop().
     */
    fun playAlarm(durationSeconds: Int = 10) {
        if (mediaPlayer?.isPlaying == true) {
            Log.w(TAG, "Ya hay un sonido reproduciéndose, ignorando ALARM_ACTIVATE")
            return
        }

        Log.d(TAG, "Reproduciendo alarma por $durationSeconds segundos")

        setupAudioFocus()
        setMaxVolume()

        mediaPlayer = createMediaPlayer(isAlarm = true).apply {
            isLooping = durationSeconds == 0  // loop infinito si duración = 0

            setOnPreparedListener { mp ->
                mp.start()

                // Sí tiene duración definida, programar el stop
                if (durationSeconds > 0) {
                    mp.postDelayed({ stop() }, durationSeconds * 1000L)
                }
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Error en MediaPlayer: what=$what extra=$extra")
                stop()
                true
            }

            prepareAsync()
        }
    }

    /**
     * Reproduce el sonido corto de ping (3 segundos fijos).
     * Mismas condiciones críticas que la alarma: máximo volumen,
     * ignora No Molestar.
     */
    fun playPing() {
        if (mediaPlayer?.isPlaying == true) {
            Log.w(TAG, "Ya hay un sonido reproduciéndose, ignorando PING")
            return
        }

        Log.d(TAG, "Reproduciendo ping")

        setupAudioFocus()
        setMaxVolume()

        mediaPlayer = createMediaPlayer(isAlarm = false).apply {
            isLooping = false

            setOnPreparedListener { mp ->
                mp.start()
                // El ping siempre dura 3 segundos según la documentación
                mp.postDelayed({ stop() }, 3000L)
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Error en MediaPlayer (ping): what=$what extra=$extra")
                stop()
                true
            }

            prepareAsync()
        }
    }

    /**
     * Detiene la reproducción y restaura el volumen original.
     * Llamado automáticamente al terminar la duración, o manualmente
     * desde ForegroundService si es necesario.
     */
    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
            restoreVolume()
            abandonAudioFocus()
        }
    }

    /**
     * Devuelve true si hay un sonido reproduciéndose actualmente.
     * Usado por ForegroundService para responder al servidor con ERROR
     * si llega una alarma mientras ya hay una activa.
     */
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    // ── Helpers privados ──────────────────────────────────────────────

    /**
     * Crea el MediaPlayer con el audio correcto.
     * Intenta cargar el archivo local en res/raw/.
     * Si no existe, usa el tono de alarma del sistema como fallback.
     */
    private fun createMediaPlayer(isAlarm: Boolean): MediaPlayer {
        return try {
            val rawRes = if (isAlarm) R.raw.alarm_sound else R.raw.ping_sound
            MediaPlayer.create(context, rawRes) ?: fallbackMediaPlayer()
        } catch (e: Exception) {
            Log.w(TAG, "No se encontró audio en res/raw, usando tono del sistema")
            fallbackMediaPlayer()
        }
    }

    private fun fallbackMediaPlayer(): MediaPlayer {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return MediaPlayer().apply {
            setAudioAttributes(buildAudioAttributes())
            setDataSource(context, alarmUri)
        }
    }

    /**
     * Solicita el foco de audio al sistema.
     * USAGE_ALARM hace que Android trate el sonido como alarma crítica,
     * lo que le permite sonar sobre el modo No Molestar.
     */
    private fun setupAudioFocus() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(buildAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .build()
        audioManager?.requestAudioFocus(audioFocusRequest!!)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    /**
     * Guarda el volumen actual y sube al máximo en STREAM_ALARM.
     */
    private fun setMaxVolume() {
        audioManager?.let { am ->
            originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                maxVolume,
                0 // sin UI de volumen
            )
        }
    }

    private fun restoreVolume() {
        audioManager?.setStreamVolume(
            AudioManager.STREAM_ALARM,
            originalVolume,
            0
        )
    }

    private fun buildAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

// Extensión para postDelayed en MediaPlayer sin Handler explícito
private fun MediaPlayer.postDelayed(action: () -> Unit, delayMs: Long) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMs)
}