package com.aguirre.pulsealert.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MaintenanceCard(untilMs: Long) {

    val accentColor = Color(0xFFF57C00) // naranja — mismo tono que "Conectando"

    // Formatear la hora de regreso solo si el timestamp es válido y futuro
    val timeText = remember(untilMs) {
        if (untilMs > System.currentTimeMillis()) {
            val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(untilMs))
            "Reconexión automática a las $formatted"
        } else {
            "Verificando estado del servidor…"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Build,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Servidor en mantenimiento",
                    style = MaterialTheme.typography.titleSmall,
                    color = accentColor
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}