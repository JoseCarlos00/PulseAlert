package com.aguirre.pulsealert.ui.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.local.MessageEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RepositoryProvider.get(application)

    /**
     * Usamos un Channel para eventos de navegación "one-shot".
     * El buffer CONFLATED asegura que si llegan varios, solo procesamos el último,
     * pero a diferencia del SharedFlow, el Channel mantendrá el evento hasta
     * que AppNavigation empiece a escucharlo (evita la race condition al abrir la app).
     */
    private val _navigationChannel = Channel<String>(capacity = Channel.CONFLATED)
    val navigationRequest: Flow<String> = _navigationChannel.receiveAsFlow()

    private val _newMessageIds = MutableStateFlow<Set<Int>>(emptySet())
    val newMessageIds: StateFlow<Set<Int>> = repository.newMessageIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
     * Solicita una navegación. Se llama desde MainActivity.
     */
    fun triggerNavigation(route: String) {
        viewModelScope.launch {
            _navigationChannel.send(route)
        }
    }

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

    fun setScreenActive(active: Boolean) {
        repository.setMessagesScreenActive(active)
    }

    fun clearNew(messageId: Int) {
        viewModelScope.launch { repository.clearNewMessage(messageId) }
    }
}
