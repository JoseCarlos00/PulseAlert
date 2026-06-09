package com.aguirre.pulsealert.core

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aguirre.pulsealert.ui.home.HomeScreen
import com.aguirre.pulsealert.ui.messages.MessagesScreen
import com.aguirre.pulsealert.ui.messages.MessagesViewModel
import com.aguirre.pulsealert.ui.settings.SettingsScreen

/**
 * Composable raíz de la navegación.
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // El ViewModel se asocia a la Activity por defecto al llamarse desde aquí.
    val messagesViewModel: MessagesViewModel = viewModel()
    val unreadCount by messagesViewModel.unreadCount.collectAsStateWithLifecycle()

    // Escucha peticiones de navegación (ej. desde notificaciones push)
    LaunchedEffect(messagesViewModel) {
        messagesViewModel.navigationRequest.collect { route ->
            navController.navigate(route) {
                // Evita duplicados y limpia el stack hasta el inicio
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            AppBottomBar(
                navController  = navController,
                unreadCount    = unreadCount
            )
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.Messages.route) {
                // Pasamos el mismo viewModel para que comparta estado
                // con el que ya está vivo en AppNavigation.
                MessagesScreen(viewModel = messagesViewModel)
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
    navController: androidx.navigation.NavController,
    unreadCount: Int
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        bottomNavScreens.forEach { screen ->
            val isSelected = currentDestination?.hierarchy?.any {
                it.route == screen.route
            } == true

            NavigationBarItem(
                icon = {
                    // Solo la tab de Mensajes muestra badge
                    if (screen is Screen.Messages && unreadCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    // Muestra "9+" si hay más de 9 no leídos
                                    Text(
                                        text = if (unreadCount > 9) "9+" else unreadCount.toString()
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label   = { Text(screen.title) },
                selected = isSelected,
                onClick  = {
                    navController.navigate(screen.route) {
                        // Evita acumular pantallas en el back stack al
                        // tocar tabs repetidamente.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState    = true
                    }
                }
            )
        }
    }
}
