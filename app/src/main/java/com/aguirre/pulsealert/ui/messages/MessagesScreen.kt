package com.aguirre.pulsealert.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aguirre.pulsealert.ui.components.EmptyState
import com.aguirre.pulsealert.ui.components.MessageItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    viewModel: MessagesViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Marca todos como leídos al entrar a la pantalla.
    // LaunchedEffect con Unit solo se ejecuta una vez al componer.
    LaunchedEffect(Unit) {
        viewModel.markAllAsRead()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = {
                Text("Mensajes")
            },
            actions = {
                if (messages.isNotEmpty()) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Limpiar historial"
                        )
                    }
                }
            }
        )

        if (messages.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pequeño espacio al inicio de la lista
                item { Spacer(Modifier.size(4.dp)) }

                items(
                    items = messages,
                    // key evita recomposiciones innecesarias al insertar
                    key = { it.id }
                ) { message ->
                    MessageItem(message = message)
                }

                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }

    // ── Diálogo de confirmación para limpiar ──────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Limpiar historial") },
            text = { Text("Se eliminarán todos los mensajes guardados. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    showDeleteDialog = false
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}