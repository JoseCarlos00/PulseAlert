package com.aguirre.pulsealert.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Pantalla de configuración.
 *
 * Observa el uiState del ViewModel con collectAsStateWithLifecycle,
 * que es la forma correcta en Compose — pausa la colección cuando
 * la pantalla no está visible para ahorrar recursos.
 *
 * El ViewModel se crea automáticamente con viewModel() la primera vez
 * y se reutiliza mientras la pantalla esté en el back stack.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "Configuración",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Sección servidor ─────────────────────────────────────────

        SectionLabel("Servidor")

        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("URL del servidor") },
            placeholder = { Text("http://192.168.1.100:3000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("Clave secreta del servidor") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Sección dispositivo ──────────────────────────────────────

        SectionLabel("Dispositivo")

        OutlinedTextField(
            value = uiState.deviceAlias,
            onValueChange = viewModel::onDeviceAliasChange,
            label = { Text("Alias del dispositivo") },
            placeholder = { Text("Ej. Bodega 3 - Entrada") },
            supportingText = { Text("Nombre que verá el operador en el panel web.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Botones ──────────────────────────────────────────────────

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::resetSettings,
                modifier = Modifier.weight(1f)
            ) {
                Text("Restablecer")
            }

            Button(
                onClick = viewModel::saveSettings,
                modifier = Modifier.weight(1f)
            ) {
                Text("Guardar")
            }
        }

        // ── Confirmación ────────────────────────────────—────────────
        // AnimatedVisibility muestra/oculta el texto con fade suave.

        AnimatedVisibility(
            visible = uiState.isSaved,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "✓ Configuración guardada",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Etiqueta de sección reutilizable.
 * Composable privado porque solo lo usa esta pantalla.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}