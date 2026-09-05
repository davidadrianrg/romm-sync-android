package es.davidrg.rommsync.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity

/**
 * Estado compartido entre el [CollapsingHeader] y el contenedor que aloja
 * tanto el header como la rejilla con scroll.
 *
 * El [connection] debe instalarse con `Modifier.nestedScroll(...)` en el
 * ANCESTRO COMÚN del header y del contenedor lazy: nestedScroll propaga los
 * deltas de scroll de los hijos hacia sus ancestros, y la rejilla es hermana
 * del header (no su hija), por lo que si se instala en el propio header nunca
 * recibe los scrolls.
 */
@Stable
class CollapsingHeaderState {

    /** Altura natural (expandida) del header en píxeles. */
    var headerHeightPx by mutableFloatStateOf(0f)
        internal set

    /** Colapso actual en píxeles (0 = expandido, headerHeightPx = oculto). */
    var collapsePx by mutableFloatStateOf(0f)
        internal set

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Scroll hacia abajo (available.y < 0): colapsamos antes de que
            // se mueva la rejilla.
            val delta = available.y
            if (delta < 0f && headerHeightPx > 0f) {
                val newCollapse = (collapsePx - delta).coerceIn(0f, headerHeightPx)
                val consumed = newCollapse - collapsePx
                collapsePx = newCollapse
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Sobrante hacia arriba tras llegar al principio de la rejilla:
            // lo usamos para expandir el header.
            val delta = available.y
            if (delta > 0f && headerHeightPx > 0f) {
                val newCollapse = (collapsePx - delta).coerceIn(0f, headerHeightPx)
                val used = collapsePx - newCollapse
                collapsePx = newCollapse
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // Snap: si el header quedó a media altura, lo llevamos al estado
            // completo más cercano (o al que indique la dirección del fling).
            val partiallyCollapsed = collapsePx > 0f && collapsePx < headerHeightPx
            if (!partiallyCollapsed || headerHeightPx <= 0f) return Velocity.Zero
            val target = when {
                available.y < -400f -> headerHeightPx // fling hacia arriba: colapsar
                available.y > 400f -> 0f              // fling hacia abajo: expandir
                else -> if (collapsePx > headerHeightPx / 2f) headerHeightPx else 0f
            }
            animate(collapsePx, target, animationSpec = tween(220)) { value, _ ->
                collapsePx = value.coerceIn(0f, headerHeightPx)
            }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState =
    remember { CollapsingHeaderState() }

/**
 * Contenedor que mide a su contenido con la altura NATURAL (sin el límite del
 * colapso) y luego se dibuja recortado a la parte visible.
 *
 * Esto evita el bucle de feedback de la implementación anterior, donde
 * `onSizeChanged` medía la altura YA recortada y el header convergía a 0dp
 * en el primer frame, dejando la toolbar invisible e incliclable.
 */
@Composable
fun CollapsingHeader(
    state: CollapsingHeaderState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        // Medir sin techo de altura: siempre obtenemos la altura natural del
        // contenido, nunca la altura colapsada.
        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        state.headerHeightPx = placeable.height.toFloat()
        val collapse = state.collapsePx.coerceIn(0f, placeable.height.toFloat())
        val visibleHeight = (placeable.height - collapse).toInt()
        // El contenido queda anclado arriba: al colapsar desaparecen primero
        // las filas inferiores (filtros, búsqueda) y el selector de
        // plataforma es lo último en ocultarse.
        layout(placeable.width, visibleHeight) {
            placeable.placeRelative(0, 0)
        }
    }
}
