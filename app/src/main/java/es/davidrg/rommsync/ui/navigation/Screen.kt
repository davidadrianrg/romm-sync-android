package es.davidrg.rommsync.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    Config(
        route = "config",
        title = "Configuración",
        icon = Icons.Filled.Settings,
    ),
    Platforms(
        route = "platforms",
        title = "Plataformas",
        icon = Icons.Filled.Storage,
    ),
    Library(
        route = "library",
        title = "Biblioteca",
        icon = Icons.Filled.SportsEsports,
    ),
    Downloads(
        route = "downloads",
        title = "Descargas",
        icon = Icons.Filled.Download,
    ),
}
