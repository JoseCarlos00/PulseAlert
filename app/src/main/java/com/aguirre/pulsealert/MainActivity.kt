package com.aguirre.pulsealert

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.aguirre.pulsealert.core.AppNavigation
import com.aguirre.pulsealert.core.Screen
import com.aguirre.pulsealert.service.NotificationHelper
import com.aguirre.pulsealert.service.SocketForegroundService
import com.aguirre.pulsealert.service.UpdateChecker
import com.aguirre.pulsealert.ui.messages.MessagesViewModel
import com.aguirre.pulsealert.ui.theme.PulseAlertTheme

/**
 * Actividad principal de la aplicación PulseAlert.
 * Se encarga de gestionar el ciclo de vida de los servicios en primer plano,
 * la solicitud de permisos en tiempo de ejecución (ubicación y notificaciones),
 * la verificación de servicios de ubicación y el enrutamiento de intents o deep links.
 */
class MainActivity : ComponentActivity() {

    /** ViewModel compartido para la gestión de navegación y estado de mensajes. */
    private lateinit var messagesViewModel: MessagesViewModel

    /**
     * Launcher para solicitar múltiples permisos de una vez.
     * Debe declararse como propiedad — AndroidX requiere que se
     * registre ANTES de que la Activity llegue a onStart().
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.forEach { (permission, granted) ->
            val status = if (granted) "CONCEDIDO" else "DENEGADO"
            Log.d("Permissions", "$permission → $status")
        }
        requestBackgroundLocationIfNeeded()
        // La app sigue funcionando aunque se denieguen.
        // Solo se perderán las notificaciones si POST_NOTIFICATIONS
        // es denegado — el socket y las alarmas siguen activos.
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("Permissions", "ACCESS_BACKGROUND_LOCATION → ${if (granted) "CONCEDIDO" else "DENEGADO"}")
    }

    private var backgroundDialogShown = false

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            backgroundDialogShown = false
            return
        }
        if (backgroundDialogShown) return
        backgroundDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("Ubicación en segundo plano")
            .setMessage(
                "Para registrar a qué punto de acceso Wi-Fi está conectado este equipo, " +
                        "elige \"Permitir todo el tiempo\" en la siguiente pantalla."
            )
            .setPositiveButton("Continuar") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$packageName".toUri()
                        )
                    )
                } else {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /**
     * Inicializa la actividad, habilita edge-to-edge, configura la UI de Compose,
     * solicita los permisos requeridos y procesa los intents de apertura.
     */
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
      * Verifica que los servicios de localización estén habilitados y los permisos
     * de instalación de fuentes desconocidas estén concedidos al reanudar la actividad.
     */
    override fun onResume() {
        super.onResume()
        when {
            !checkLocationEnabled() -> Unit
            !requestBackgroundLocationIfNeeded() -> Unit
            else -> checkInstallUnknownAppsPermissionIfNeeded()
        }
    }

    private var installPermissionChecked = false

    /**
     * Verifica si la app tiene permiso para instalar paquetes de fuentes desconocidas (Android 8+).
     * Si no lo tiene, muestra un diálogo explicativo y redirige a los ajustes del sistema.
     */
    private fun checkInstallUnknownAppsPermissionIfNeeded() {
        if (packageManager.canRequestPackageInstalls()) {
            installPermissionChecked = false
            return
        }
        if (installPermissionChecked) return
        installPermissionChecked = true

        AlertDialog.Builder(this)
            .setTitle("Permitir instalación de actualizaciones")
            .setMessage(
                "Para que PulseAlert pueda actualizarse automáticamente al recibir una nueva versión, " +
                        "es necesario permitir la instalación de aplicaciones de fuentes desconocidas."
            )
            .setPositiveButton("Configurar") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /**
     * Maneja un nuevo intent cuando la actividad ya se encuentra en primer plano o segundo plano.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Actualiza el intent de la activity
        handleIntent(intent)
    }

    /**
     * Procesa el Intent recibido para disparar acciones de navegación o descargas de actualizaciones.
     *
     * @param intent El intent recibido que puede contener acciones de deep link.
     */
    private fun handleIntent(intent: Intent?) {
        Log.d("MainActivity", "handleIntent: action=${intent?.action}")

        when (intent?.action) {
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

    /**
     * Comprueba y solicita los permisos obligatorios en tiempo de ejecución
     * (ubicación precisa/aproximada y notificaciones según la versión de Android).
     */
    private fun requestRequiredPermissions() {

        val toRequest = buildList {

            // Android 8 hasta Android 13+
            if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (!isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            // Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    /** Bandera para evitar mostrar múltiples veces el diálogo de ubicación desactivada. */
    private var locationDialogShown = false

    /**
     * Verifica si el GPS / servicios de ubicación del dispositivo están activos.
     * Si están desactivados, muestra un diálogo de advertencia para que el usuario los active.
     */
    private fun checkLocationEnabled() {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (LocationManagerCompat.isLocationEnabled(lm)) {
            locationDialogShown = false
            return
        }
        if (locationDialogShown) return   // evita abrir el diálogo varias veces
        locationDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("Ubicación apagada")
            .setMessage(
                "Con la ubicación del equipo apagada, Android oculta el punto de acceso Wi-Fi " +
                        "al que estás conectado. Actívala para el mapeo de red."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /**
     * Comprueba si un permiso específico ha sido concedido.
     *
     * @param permission El nombre del permiso de [Manifest.permission].
     * @return `true` si el permiso ha sido concedido, `false` en caso contrario.
     */
    @Suppress("SameParameterValue")
    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
}
