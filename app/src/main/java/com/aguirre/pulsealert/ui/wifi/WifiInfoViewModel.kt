package com.aguirre.pulsealert.ui.wifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aguirre.pulsealert.core.RepositoryProvider
import com.aguirre.pulsealert.data.remote.HeartbeatStatus
import kotlinx.coroutines.flow.StateFlow

class WifiInfoViewModel(app: Application) : AndroidViewModel(app) {
    val status: StateFlow<HeartbeatStatus> = RepositoryProvider.get(app).heartbeat
}