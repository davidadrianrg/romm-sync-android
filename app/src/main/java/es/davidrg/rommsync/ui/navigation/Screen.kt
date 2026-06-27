package es.davidrg.rommsync.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Config(
        route = "config",
        title = "Configuración",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
    Platforms(
        route = "platforms",
        title = "Plataformas",
        icon = Icons.Outlined.Storage,
        selectedIcon = Icons.Filled.Storage,
    ),
    Library(
        route = "library",
        title = "Biblioteca",
        icon = Icons.Outlined.SportsEsports,
        selectedIcon = Icons.Filled.SportsEsports,
    ),
    Downloads(
        route = "downloads",
        title = "Descargas",
        icon = Icons.Outlined.Download,
        selectedIcon = Icons.Filled.Download,
    ),
    Sync(
        route = "sync",
        title = "Sync",
        icon = Icons.Outlined.Sync,
        selectedIcon = Icons.Filled.Sync,
    ),
}
