package com.aguirre.pulsealert.core

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aguirre.pulsealert.ui.home.HomeScreen
import com.aguirre.pulsealert.ui.messages.MessagesScreen
import com.aguirre.pulsealert.ui.settings.SettingsScreen

/**
 * Composable raíz de la navegación.
 * Se llama una sola vez desde MainActivity.
 *
 * Contiene:
 *  - Scaffold con BottomNavigationBar
 *  - NavHost con las tres pantallas
 *
 * El NavController vive aquí y se pasa hacia abajo solo cuando
 * una pantalla necesita navegar a otra (ej. notificación → Messages).
 */
@Composable
fun AppNavigation() {

    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->

        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.Messages.route) {
                MessagesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

/**
 * BottomNavigationBar generada automáticamente desde [bottomNavScreens].
 * Para agregar o quitar tabs, solo modifica la lista en Navigation.kt.
 *
 * Marca como "seleccionado" el item cuya ruta coincide con la pantalla
 * activa usando hierarchy, lo que funciona correctamente incluso con
 * pantallas anidadas en el futuro.
 */
@Composable
private fun AppBottomBar(
    navController: androidx.navigation.NavController
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        bottomNavScreens.forEach { screen ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title
                    )
                },
                label = { Text(screen.title) },
                selected = currentDestination?.hierarchy?.any {
                    it.route == screen.route
                } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        // Evita acumular pantallas en el back stack al
                        // tocar tabs repetidamente.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}