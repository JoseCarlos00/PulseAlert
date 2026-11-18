package com.aguirre.pulsealert.data.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.aguirre.pulsealert.core.RepositoryProvider
import org.json.JSONObject

/**
 * Foto del estado wifi en un instante.
 * Si falta el permiso de ubicación, ssid y bssid vienen en null
 * y locationRestricted = true.
 */
data class WifiSnapshot(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val linkSpeedMbps: Int,
    val standard: Int,
    val ipAddress: String,
    val locationRestricted: Boolean
) {
    fun toJson(roamedFrom: String? = null): JSONObject = JSONObject().apply {
        put("ssid", ssid ?: JSONObject.NULL)
        put("bssid", bssid ?: JSONObject.NULL)
        put("rssi", rssi)
        put("frequencyMhz", frequencyMhz)
        put("channel", channel)
        put("linkSpeedMbps", linkSpeedMbps)
        put("standard", standard)
        put("locationRestricted", locationRestricted)
        put("roamedFrom", roamedFrom ?: JSONObject.NULL)
    }
}

class WifiSnapshotProvider(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    fun capture(): WifiSnapshot {
        return try {
            val info = wifiManager.connectionInfo
            val rawBssid = info?.bssid
            val restricted = rawBssid == null ||
                    rawBssid == "02:00:00:00:00:00" ||
                    info.ssid == WifiManager.UNKNOWN_SSID

            val freq = info?.frequency ?: -1

            WifiSnapshot(
                ssid = if (restricted) null else info.ssid?.trim('"'),
                bssid = if (restricted) null else rawBssid.lowercase(),
                rssi = info?.rssi ?: -127,
                frequencyMhz = freq,
                channel = freqToChannel(freq),
                linkSpeedMbps = info?.linkSpeed ?: -1,
                standard = info?.wifiStandard ?: 0,
                ipAddress = RepositoryProvider.getLocalIpAddress(appContext),
                locationRestricted = restricted
            )
        } catch (_: Exception) {
            // Nunca debe tumbar el heartbeat por un fallo al leer el Wifi
            WifiSnapshot(
                ssid = null, bssid = null, rssi = -127,
                frequencyMhz = -1, channel = -1, linkSpeedMbps = -1,
                standard = 0, ipAddress = "0.0.0.0",
                locationRestricted = true
            )
        }
    }

    private fun freqToChannel(f: Int): Int = when (f) {
        2484 -> 14
        in 2412..2472 -> (f - 2407) / 5
        in 5170..5895 -> (f - 5000) / 5
        in 5955..7115 -> (f - 5950) / 5   // 6 GHz
        else -> -1
    }
}