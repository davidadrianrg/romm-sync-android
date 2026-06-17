package es.davidrg.rommsync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.ui.screens.ConfigScreen
import es.davidrg.rommsync.ui.screens.DownloadQueueScreen
import es.davidrg.rommsync.ui.screens.LibraryScreen
import es.davidrg.rommsync.ui.screens.PlatformsScreen
import es.davidrg.rommsync.util.hasAllFilesAccess

@Composable
fun RomMSyncApp() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container

    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val needsOnboarding = !settings.isConfigured || !hasAllFilesAccess()

    Scaffold(
        bottomBar = {
            if (!needsOnboarding) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = isSelected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (needsOnboarding) Screen.Config.route else Screen.Config.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Config.route) { ConfigScreen() }
            composable(Screen.Platforms.route) { PlatformsScreen() }
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Downloads.route) { DownloadQueueScreen() }
        }
    }
}

private val bottomNavItems = listOf(
    Screen.Config,
    Screen.Platforms,
    Screen.Library,
    Screen.Downloads,
)
