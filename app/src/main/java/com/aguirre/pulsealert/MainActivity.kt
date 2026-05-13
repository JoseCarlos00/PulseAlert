package com.aguirre.pulsealert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aguirre.pulsealert.core.AppNavigation
import com.aguirre.pulsealert.ui.theme.PulseAlertTheme



/**
 * Único Activity de la app. Su responsabilidad es mínima:
 *  1. Habilitar edge-to-edge (pantalla completa moderna).
 *  2. Aplicar el tema.
 *  3. Lanzar AppNavigation, que toma el control desde aquí.
 *
 * TODO: Cuando implementes el SocketForegroundService, aquí
 * también arrancarás y detendrás el servicio:
 *
 *   override fun onStart() {
 *       super.onStart()
 *       Intent(this, SocketForegroundService::class.java).also {
 *           startService(it)
 *       }
 *   }
 *
 * TODO: Si una notificación push debe abrir directamente la pantalla
 * de mensajes, leerás el Intent aquí y llamarás:
 *   navController.navigate(Screen.Messages.route)
 * Para eso necesitarás subir el navController a este nivel o usar
 * un SharedViewModel / EventBus. Lo veremos cuando lleguemos al Service.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PulseAlertTheme {
                AppNavigation()
            }
        }
    }
}