package com.aguirre.pulsealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import com.aguirre.pulsealert.R

private const val TAG = "AlarmPlayer"

/**
 * Reproduce sonidos de alarma y ping con máximo volumen.
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
        if (isPlaying()) {
            Log.w(TAG, "Ya hay un sonido reproduciéndose, ignorando")
            return
        }

        Log.d(TAG, "Reproduciendo alarma por $durationSeconds segundos")

        setupAudioFocus()
        setMaxVolume()

        try {
            mediaPlayer = createMediaPlayer(isAlarm = true).apply {
                isLooping = (durationSeconds == 0)

                setOnPreparedListener { mp ->
                    mp.start()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error al preparar alarma: ${e.message}")
            stop()
        }
    }

    /**
     * Reproduce el sonido corto de ping (3 segundos fijos).
     * Mismas condiciones críticas que la alarma: máximo volumen,
     * ignora No Molestar.
     */
    fun playPing() {
        if (isPlaying()) return

        Log.d(TAG, "Reproduciendo ping")

        setupAudioFocus()
        setMaxVolume()

        try {
            mediaPlayer = createMediaPlayer(isAlarm = false).apply {
                isLooping = false

                setOnPreparedListener { mp ->
                    mp.start()
                    mp.postDelayed({ stop() }, 3000L)
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Error en MediaPlayer (ping): what=$what extra=$extra")
                    stop()
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al preparar ping: ${e.message}")
            stop()
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
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener: ${e.message}")
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

    // ── Helpers privados corregidos ──────────────────────────────────

    /**
     * Crea un MediaPlayer vacío y le asigna la fuente de datos SIN prepararlo.
     * Esto permite que playAlarm() llame a prepareAsync() sin errores.
     */
    private fun createMediaPlayer(isAlarm: Boolean): MediaPlayer {
        val mp = MediaPlayer()
        mp.setAudioAttributes(buildAudioAttributes())

        try {
            val rawRes = if (isAlarm) R.raw.alarm_sound else R.raw.ping_sound
            val afd = context.resources.openRawResourceFd(rawRes)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (e: Exception) {
            Log.w(TAG, "No se encontró recurso raw, usando fallback del sistema")
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mp.setDataSource(context, alarmUri)
        }
        return mp
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
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
    }

    private fun restoreVolume() {
        audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
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
