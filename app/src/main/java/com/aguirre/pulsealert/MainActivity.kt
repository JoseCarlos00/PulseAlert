package com.aguirre.pulsealert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.aguirre.pulsealert.core.AppNavigation
import com.aguirre.pulsealert.core.Screen
import com.aguirre.pulsealert.service.NotificationHelper
import com.aguirre.pulsealert.service.SocketForegroundService
import com.aguirre.pulsealert.service.UpdateChecker
import com.aguirre.pulsealert.ui.messages.MessagesViewModel
import com.aguirre.pulsealert.ui.theme.PulseAlertTheme

class MainActivity : ComponentActivity() {

    private lateinit var messagesViewModel: MessagesViewModel

    /**
     * Launcher para solicitar múltiples permisos de una vez.
     * Debe declararse como propiedad — AndroidX requiere que se
     * registre ANTES de que la Activity llegue a onStart().
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            val status = if (granted) "CONCEDIDO" else "DENEGADO"
            android.util.Log.d("Permissions", "$permission → $status")
        }
        // La app sigue funcionando aunque se denieguen.
        // Solo se perderán las notificaciones si POST_NOTIFICATIONS
        // es denegado — el socket y las alarmas siguen activos.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Obtenemos el ViewModel de la Activity para compartirlo con el sistema de navegación
        messagesViewModel = ViewModelProvider(this)[MessagesViewModel::class.java]

        setContent {
            PulseAlertTheme {
                AppNavigation()
            }
        }

        // Solicita permisos al iniciar. El diálogo aparece automáticamente
        // la primera vez. En aperturas posteriores isGranted() devuelve
        // true y no se vuelve a mostrar nada.
        requestRequiredPermissions()

        // Manejar el intent de apertura (app cerrada)
        handleIntent(intent)
    }

    /**
     * Arranca el ForegroundService cuando la app pasa a primer plano.
     * onStart() se llama tanto al abrir la app por primera vez como
     * al volver desde background — lo que garantiza que el servicio
     * siempre esté corriendo mientras la app exista.
     */
    override fun onStart() {
        super.onStart()
        Intent(this, SocketForegroundService::class.java).also {
            startService(it)
        }
    }

    /**
     * Maneja el intent cuando la app ya está abierta en segundo plano.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Actualiza el intent de la activity
        handleIntent(intent)
    }

    /**
     * Procesa el Intent para disparar la navegación si viene de una notificación.
     */
    private fun handleIntent(intent: Intent?) {
        Log.d("MainActivity", "handleIntent: action=${intent?.action}")
        
        when (intent?.action) {
            // SOLUCIÓN: Usar ACTION_NAV_MESSAGES para que coincida con el action del Intent
            NotificationHelper.ACTION_NAV_MESSAGES -> {
                Log.d("MainActivity", "Navegando a Mensajes vía Deep Link")
                messagesViewModel.triggerNavigation(Screen.Messages.route)
            }
            UpdateChecker.ACTION_DOWNLOAD_UPDATE -> {
                val apkUrl = intent.getStringExtra(UpdateChecker.EXTRA_APK_URL)
                if (!apkUrl.isNullOrBlank()) {
                    Log.d("MainActivity", "Iniciando descarga de actualización")
                    val serviceIntent = Intent(this, SocketForegroundService::class.java).apply {
                        action = UpdateChecker.ACTION_DOWNLOAD_UPDATE
                        putExtra(UpdateChecker.EXTRA_APK_URL, apkUrl)
                    }
                    startService(serviceIntent)
                }
            }
        }
    }

    // ── Permisos ──────────────────────────────────────────────────────

    /**
     * Construye la lista de permisos que faltan y los solicita juntos.
     *
     * POST_NOTIFICATIONS → requerido en Android 13+ para notificaciones.
     *   Sin este permiso las alertas de alarma y mensajes no aparecen.
     *
     * Los permisos "normales" (INTERNET, WAKE_LOCK, FOREGROUND_SERVICE,
     * ACCESS_WIFI_STATE) los concede Android automáticamente al instalar,
     * no necesitan diálogo.
     */
    private fun requestRequiredPermissions() {
        val toRequest = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
}
