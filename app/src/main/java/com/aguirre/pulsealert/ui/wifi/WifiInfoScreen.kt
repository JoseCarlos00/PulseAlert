package com.aguirre.pulsealert.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WifiInfoScreen(viewModel: WifiInfoViewModel = viewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val wifi = status.wifi
    val roamedFrom = status.roamedFrom

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Red y ubicación", style = MaterialTheme.typography.headlineSmall)

        // Zona (lo que responde el servidor)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(28.dp))
                Column {
                    Text("Zona", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = when {
                            wifi == null -> "Esperando primer reporte…"
                            status.zone != null -> status.zone!!
                            else -> "Sin zona asignada"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        // Datos del heartbeat
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (wifi == null) {
                    Text("Sin datos todavía")
                } else if (wifi.locationRestricted) {
                    Text("BSSID restringido", color = MaterialTheme.colorScheme.error)
                    Text(
                        "Revisa el permiso de ubicación y que la ubicación del sistema esté encendida.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Field("BSSID", wifi.bssid ?: "—")
                    Field("SSID", wifi.ssid ?: "—")
                    Field("Señal", "${wifi.rssi} dBm")
                    Field("Canal", "${wifi.channel}  (${wifi.frequencyMhz} MHz)")
                    Field("Velocidad de enlace", "${wifi.linkSpeedMbps} Mbps")
                    Field("Estándar", wifiStandardName(wifi.standard))
                    Field("RoamedFrom", roamedFrom ?: "—")
                }
                Field("IP", wifi?.ipAddress ?: "—")
            }
        }

        // Estado del heartbeat
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Latencia", if (status.latencyMs >= 0) "${status.latencyMs} ms" else "—")
                Field("Último ACK", if (status.lastAckMs > 0) formatTime(status.lastAckMs) else "—")
                Field("Heartbeats perdidos", status.missed.toString())
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun wifiStandardName(s: Int) = when (s) {
    1 -> "Legacy"
    4 -> "Wi-Fi 4 (802.11n)"
    5 -> "Wi-Fi 5 (802.11ac)"
    6 -> "Wi-Fi 6 (802.11ax)"
    7 -> "Wi-Fi 6E"
    8 -> "Wi-Fi 7 (802.11be)"
    else -> "Desconocido"
}

private fun formatTime(ms: Long) =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))