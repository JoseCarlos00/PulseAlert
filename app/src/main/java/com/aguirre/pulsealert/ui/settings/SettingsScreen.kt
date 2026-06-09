package com.aguirre.pulsealert.ui.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Lanza el snackbar cada vez que isSaved se vuelve true
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            snackbarHostState.showSnackbar(
                message = "Configuración guardada",
                duration = SnackbarDuration.Short
            )
            viewModel.onSavedConsumed() // resetea el flag
        }
    }

    // Si no tiene acceso, mostramos la pantalla de bloqueo
    if (!uiState.isAccessGranted) {
        AuthGate(
            pinValue = uiState.pinInput,
            isError = uiState.isPinError,
            onPinChange = viewModel::onPinChange,
            onConfirm = viewModel::validatePin
        )
        return
    }

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

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                value = uiState.statusUrl,
                onValueChange = viewModel::onStatusUrlChange,
                label = { Text("URL de estado") },
                placeholder = { Text("https://mi-servicio.com/status") },
                supportingText = { Text("Endpoint independiente para verificar mantenimiento.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.updateUrl,
                onValueChange = viewModel::onUpdateUrlChange,
                label = { Text("URL de actualizaciones") },
                placeholder = { Text("https://raw.githubusercontent.com/...") },
                supportingText = { Text("JSON con la versión más reciente de la app.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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

@Composable
private fun AuthGate(
    pinValue: String,
    isError: Boolean,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Text("Acceso Restringido", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = pinValue,
            onValueChange = onPinChange,
            label = { Text("Introduce el PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done // Define el botón de "Enter" como "Listo"
            ),
            keyboardActions = KeyboardActions(
                onDone = { onConfirm() } // Ejecuta la validación al pulsar el botón del teclado
            ),
            isError = isError,
            supportingText = { if (isError) Text("PIN Incorrecto") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("Desbloquear")
        }
    }
}