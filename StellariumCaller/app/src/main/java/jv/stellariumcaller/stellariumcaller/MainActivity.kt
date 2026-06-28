package jv.stellariumcaller.stellariumcaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import jv.stellariumcaller.stellariumcaller.ui.navigation.Screen
import jv.stellariumcaller.stellariumcaller.ui.screens.CallDetailScreen
import jv.stellariumcaller.stellariumcaller.ui.screens.CallsScreen
import jv.stellariumcaller.stellariumcaller.ui.screens.SettingsScreen
import jv.stellariumcaller.stellariumcaller.ui.theme.DarkBackground
import jv.stellariumcaller.stellariumcaller.ui.theme.DarkSurface
import jv.stellariumcaller.stellariumcaller.ui.theme.Emerald
import jv.stellariumcaller.stellariumcaller.ui.theme.StellariumCallerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CallLogRepository.getInstance(applicationContext)

        setContent {
            StellariumCallerTheme {
                MainScreen(repo)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(repo: CallLogRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in listOf(Screen.Calls.route, Screen.Settings.route)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = DarkSurface,
                    contentColor = Emerald
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Calls.route,
                        onClick = {
                            if (currentRoute != Screen.Calls.route) {
                                navController.navigate(Screen.Calls.route) {
                                    popUpTo(Screen.Calls.route) { inclusive = true }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Phone, contentDescription = "Calls") },
                        label = { Text("Calls") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Emerald,
                            selectedTextColor = Emerald,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = DarkSurface
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Settings.route,
                        onClick = {
                            if (currentRoute != Screen.Settings.route) {
                                navController.navigate(Screen.Settings.route) {
                                    popUpTo(Screen.Calls.route) { inclusive = true }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Emerald,
                            selectedTextColor = Emerald,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = DarkSurface
                        )
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Calls.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(Screen.Calls.route) {
                CallsScreen(
                    repo = repo,
                    onCallClick = { callId ->
                        navController.navigate(Screen.CallDetail.createRoute(callId))
                    }
                )
            }
            composable(
                route = Screen.CallDetail.route,
                arguments = listOf(navArgument("callId") { type = NavType.LongType })
            ) { backStackEntry ->
                val callId = backStackEntry.arguments?.getLong("callId") ?: 0L
                CallDetailScreen(
                    callId = callId,
                    repo = repo,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
