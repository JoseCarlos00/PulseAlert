package com.aguirre.pulsealert.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Define todas las pantallas de la app como un sealed class.
 *
 * Cada objeto tiene:
 *  - route: string único que usa NavController para navegar.
 *  - title: texto que se muestra en la BottomBar.
 *  - icon:  ícono de la BottomBar.
 *
 * Para navegar desde cualquier pantalla:
 *   navController.navigate(Screen.Messages.route)
 *
 * Para abrir Messages desde una notificación push (deep link):
 *   navController.navigate(Screen.Messages.route) {
 *       popUpTo(Screen.Home.route)
 *   }
 */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : Screen(
        route = "home",
        title = "Inicio",
        icon = Icons.Outlined.Home
    )

    object Messages : Screen(
        route = "messages",
        title = "Mensajes",
        icon = Icons.Outlined.Mail
    )

    object Settings : Screen(
        route = "settings",
        title = "Configuración",
        icon = Icons.Outlined.Settings
    )
}

/**
 * Lista ordenada de pantallas que aparecen en la BottomNavigationBar.
 * Si en el futuro agregas una pantalla que NO va en la barra inferior
 * (ej. un detalle de mensaje), simplemente no la incluyas aquí.
 */
val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Messages,
    Screen.Settings
)