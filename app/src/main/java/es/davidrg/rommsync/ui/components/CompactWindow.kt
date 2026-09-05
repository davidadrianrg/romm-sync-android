package es.davidrg.rommsync.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clasificación de la ventana según tamaño físico aproximado y orientación.
 *
 * La app se usa sobre todo en consolas portátiles Android (Anbernic & co.)
 * con pantallas de 3.5"–7" en 4:3 o 16:9, además de móviles y tablets:
 *
 * - [COMPACT]: ~<4.5" físicos o muy poca altura en dp (4" 16:9 vertical,
 *   4" 4:3 vertical). Todo debe caber sin scroll: barras de ~48dp, tipografía
 *   ajustada y controles con hit area mínima de 40dp.
 * - [MEDIUM]: móviles normales (~5–7"), el caso por defecto.
 * - [EXPANDED]: tablets / pantallas grandes.
 */
enum class WindowSizeClass { COMPACT, MEDIUM, EXPANDED }

data class WindowInfo(
    val sizeClass: WindowSizeClass,
    val isLandscape: Boolean,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
) {
    /** Cierto en portátiles con pantalla pequeña en horizontal. */
    val isCompactLandscape: Boolean get() = isLandscape && sizeClass == WindowSizeClass.COMPACT
    /** Cierto en portátiles con pantalla pequeña en vertical. */
    val isCompactPortrait: Boolean get() = !isLandscape && sizeClass == WindowSizeClass.COMPACT
    /** Cierto en cualquier disposición compacta (portátil). */
    val isCompact: Boolean get() = sizeClass == WindowSizeClass.COMPACT
}

val LocalWindowInfo = staticCompositionLocalOf {
    WindowInfo(WindowSizeClass.MEDIUM, false, 360, 640)
}

/**
 * Clasifica la ventana. Heurística combinada:
 * 1. Diagonal física aproximada (xdp/ydp → pulgadas con densidad) < 4.6"
 *    ⇒ compacta (4" 16:9 ≈ 4.0", 4" 4:3 ≈ 3.9", Anbernic 552 5.5" no).
 * 2. Si la pantalla es muy corta en dp (altura útil < 480dp en vertical,
 *    < 360dp en horizontal) también se considera compacta: es la situación
 *    en la que los menús no caben aunque la diagonal sea mayor.
 */
@Composable
fun rememberWindowInfo(): WindowInfo {
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp

    // Diagonal física aproximada: dp * (densidad/160) = px; px / dpi = pulgadas.
    // screenWidthDp ya es dp independiente de densidad: pulgadas ≈ dpDp / 160.
    val diagonalInches = Math.hypot(
        configuration.screenWidthDp.toDouble(),
        configuration.screenHeightDp.toDouble(),
    ) / 160.0

    val sizeClass = when {
        diagonalInches < 4.6 -> WindowSizeClass.COMPACT
        configuration.screenHeightDp < 480 -> WindowSizeClass.COMPACT
        configuration.screenWidthDp >= 840 -> WindowSizeClass.EXPANDED
        else -> WindowSizeClass.MEDIUM
    }

    return WindowInfo(
        sizeClass = sizeClass,
        isLandscape = isLandscape,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
}

/** Altura objetivo para TopAppBars según tamaño de ventana. */
val WindowInfo.appBarHeight: Dp
    get() = if (sizeClass == WindowSizeClass.COMPACT) 48.dp else 64.dp

/** Padding horizontal estándar de pantalla. */
val WindowInfo.screenPadding: Dp
    get() = when {
        sizeClass == WindowSizeClass.COMPACT -> 10.dp
        sizeClass == WindowSizeClass.EXPANDED -> 24.dp
        else -> 16.dp
    }

/** Espaciado vertical entre tarjetas/secciones. */
val WindowInfo.sectionSpacing: Dp
    get() = if (sizeClass == WindowSizeClass.COMPACT) 10.dp else 16.dp
