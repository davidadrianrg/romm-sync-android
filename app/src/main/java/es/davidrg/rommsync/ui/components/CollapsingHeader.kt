package es.davidrg.rommsync.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Header que se colapsa al hacer scroll hacia abajo dentro de un contenedor
 * lazy (p. ej. LazyVerticalGrid) y se expande al volver arriba.
 *
 * Pensado para portátiles con pantalla pequeña: recupera ~140dp de altura
 * útil para la rejilla de carátulas. El contenido interno se recorta (clip)
 * pero no se recomponen los tamaños, así que los TextField conservan su
 * estado y el foco.
 */
@Composable
fun CollapsingHeader(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var collapsePx by remember { mutableFloatStateOf(0f) }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scroll hacia abajo (available.y < 0): colapsamos primero.
                val delta = available.y
                if (delta < 0f) {
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
                // Sobrante hacia arriba tras llegar al principio: expandimos.
                val delta = available.y
                if (delta > 0f && headerHeightPx > 0f) {
                    val newCollapse = (collapsePx - delta).coerceIn(0f, headerHeightPx)
                    val used = collapsePx - newCollapse
                    collapsePx = newCollapse
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            suspend fun snapIfFlung(available: Velocity): Velocity {
                // Snap: si quedó a medio colapsar, terminamos el movimiento.
                val remaining = headerHeightPx - collapsePx
                return if (remaining != 0f && kotlin.math.abs(available.y) > 800f) {
                    val target = if (available.y < 0) headerHeightPx else 0f
                    collapsePx = target
                    Velocity(0f, available.y)
                } else {
                    Velocity.Zero
                }
            }
        }
    }

    val density = LocalDensity.current
    val visibleDp = with(density) {
        ((headerHeightPx - collapsePx).coerceAtLeast(0f)).toDp()
    }
    val animatedHeight by animateDpAsState(
        targetValue = visibleDp,
        animationSpec = tween(160),
        label = "headerHeight",
    )

    Column(
        modifier = modifier
            .nestedScroll(connection)
            .onSizeChanged { headerHeightPx = it.height.toFloat() }
            .height(animatedHeight)
            .clipToBounds(),
    ) {
        content()
    }
}
