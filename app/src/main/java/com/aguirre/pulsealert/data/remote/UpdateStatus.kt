package com.aguirre.pulsealert.data.remote

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class Available(val versionCode: Long, val downloadUrl: String) : UpdateStatus
    data class Downloading(val percent: Int) : UpdateStatus   // -1 = indeterminado
    data object Installing : UpdateStatus
    data class Error(val reason: String) : UpdateStatus
}