package com.aguirre.pulsealert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.aguirre.pulsealert.core.AppNavigation
import com.aguirre.pulsealert.service.NotificationHelper.Companion.EXTRA_NAVIGATE_TO
import com.aguirre.pulsealert.service.NotificationHelper.Companion.NAV_MESSAGES
import com.aguirre.pulsealert.service.SocketForegroundService
import com.aguirre.pulsealert.ui.theme.PulseAlertTheme

class MainActivity : ComponentActivity() {

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

        setContent {
            PulseAlertTheme {
                AppNavigation()
            }
        }

        // Solicita permisos al iniciar. El diálogo aparece automáticamente
        // la primera vez. En aperturas posteriores isGranted() devuelve
        // true y no se vuelve a mostrar nada.
        requestRequiredPermissions()
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
     * Maneja el deep link cuando el usuario toca una notificación push
     * con la app ya abierta (launchMode="singleTop" en el Manifest).
     *
     * Android llama onNewIntent() en lugar de recrear la Activity
     * cuando llega un Intent con FLAG_ACTIVITY_SINGLE_TOP.
     *
     * Por ahora loga el Intent — la navegación real al abrir desde
     * notificación se puede implementar con un SharedFlow en AppNavigation
     * cuando lo necesites.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)
        if (navigateTo == NAV_MESSAGES) {
            // TODO: navegar a MessagesScreen programáticamente.
            // Para implementarlo completamente, sube el NavController
            // a un ViewModel compartido o usa un StateFlow en AppNavigation
            // que MainActivity pueda escribir.
            android.util.Log.d("MainActivity", "Deep link → MessagesScreen")
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