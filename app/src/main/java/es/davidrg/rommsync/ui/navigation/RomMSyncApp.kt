package es.davidrg.rommsync.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.ui.components.rememberWindowInfo
import es.davidrg.rommsync.ui.screens.ConfigScreen
import es.davidrg.rommsync.ui.screens.DownloadQueueScreen
import es.davidrg.rommsync.ui.screens.LibraryScreen
import es.davidrg.rommsync.ui.screens.PlatformsScreen
import es.davidrg.rommsync.ui.screens.SyncScreen
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
    val windowInfo = rememberWindowInfo()

    // En horizontal (portátiles 16:9 / 4:3) un rail lateral libera la altura
    // que consumía la bottom bar: todo el alto de pantalla para el contenido.
    if (windowInfo.isLandscape && !needsOnboarding) {
        Row(modifier = Modifier.fillMaxSize()) {
            AppNavRail(currentDestination) { route -> navigateTo(route, navController) }
            AppNavHost(
                navController = navController,
                needsOnboarding = needsOnboarding,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!needsOnboarding) {
                    AppBottomBar(currentDestination) { route -> navigateTo(route, navController) }
                }
            },
        ) { innerPadding ->
            AppNavHost(
                navController = navController,
                needsOnboarding = needsOnboarding,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AppNavRail(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {},
    ) {
        bottomNavItems.forEach { screen ->
            val selected = isSelected(screen.route, currentDestination)
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(screen.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                        contentDescription = screen.title,
                    )
                },
                label = { Text(screen.title) },
                alwaysShowLabel = false,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AppBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        bottomNavItems.forEach { screen ->
            val selected = isSelected(screen.route, currentDestination)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                        contentDescription = screen.title,
                    )
                },
                label = { Text(screen.title) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    needsOnboarding: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = if (needsOnboarding) Screen.Config.route else Screen.Library.route,
        modifier = modifier,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 8 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 8 } },
    ) {
        composable(Screen.Library.route) { LibraryScreen() }
        composable(Screen.Platforms.route) { PlatformsScreen() }
        composable(Screen.Downloads.route) { DownloadQueueScreen() }
        composable(Screen.Sync.route) { SyncScreen() }
        composable(Screen.Config.route) { ConfigScreen() }
    }
}

private fun isSelected(route: String, currentDestination: NavDestination?): Boolean =
    currentDestination?.hierarchy?.any { it.route == route } == true

private fun navigateTo(route: String, navController: NavHostController) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

// Order: Library (home) → Platforms → Downloads → Sync → Config
private val bottomNavItems = listOf(
    Screen.Library,
    Screen.Platforms,
    Screen.Downloads,
    Screen.Sync,
    Screen.Config,
)
