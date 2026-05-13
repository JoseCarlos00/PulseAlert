package com.aguirre.pulsealert.ui.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MessagesScreen () {
    Box(Modifier.fillMaxSize()) {
        Text("Messages", Modifier.align(Alignment.Center))
    }
}