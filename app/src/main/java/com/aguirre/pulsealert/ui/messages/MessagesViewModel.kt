package com.aguirre.pulsealert.ui.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.local.MessageEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RepositoryProvider.get(application)

    /**
     * Lista de mensajes como StateFlow.
     * stateIn convierte el Flow de Room en un StateFlow que Compose puede
     * observar. SharingStarted.WhileSubscribed(5000) mantiene el Flow activo
     * 5 segundos después de que no haya suscriptores — evita recargar Room
     * en rotaciones de pantalla.
     */
    val messages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Cantidad de mensajes no leídos para el badge de la BottomBar.
     */
    val unreadCount: StateFlow<Int> = repository.unreadCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    /**
     * Marca todos como leídos al abrir la pantalla.
     * Se llama desde LaunchedEffect en MessagesScreen.
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllMessagesAsRead()
        }
    }

    /**
     * Elimina todos los mensajes del historial.
     */
    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAllMessages()
        }
    }
}