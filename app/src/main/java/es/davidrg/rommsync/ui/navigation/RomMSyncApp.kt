package es.davidrg.rommsync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Scaffold(
        bottomBar = {
            if (!needsOnboarding) {
                NavigationBar(
                    modifier = if (isLandscape) Modifier.height(56.dp) else Modifier,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    bottomNavItems.forEach { screen ->
                        val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (isSelected) screen.selectedIcon else screen.icon,
                                    contentDescription = screen.title,
                                )
                            },
                            label = { if (!isLandscape) Text(screen.title) },
                            selected = isSelected,
                            alwaysShowLabel = !isLandscape,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
            // First launch → Config for onboarding; otherwise → Library (home)
            startDestination = if (needsOnboarding) Screen.Config.route else Screen.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Library.route) { LibraryScreen() }
            composable(Screen.Platforms.route) { PlatformsScreen() }
            composable(Screen.Downloads.route) { DownloadQueueScreen() }
            composable(Screen.Config.route) { ConfigScreen() }
        }
    }
}

// Order: Library (home) → Platforms → Downloads → Config (last)
private val bottomNavItems = listOf(
    Screen.Library,
    Screen.Platforms,
    Screen.Downloads,
    Screen.Config,
)
