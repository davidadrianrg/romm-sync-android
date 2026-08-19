package es.davidrg.rommsync.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests de la lógica pura de mediana usada para el aspect ratio de covers.
 */
class CoverAspectRatioCalculatorTest {

    @Test
    fun `median of odd list returns middle element`() {
        val result = CoverAspectRatioCalculator.median(listOf(0.6f, 0.7f, 0.65f))
        assertThat(result).isEqualTo(0.65f)
    }

    @Test
    fun `median of even list averages the two middle elements`() {
        val result = CoverAspectRatioCalculator.median(listOf(0.6f, 0.8f))
        assertThat(result).isWithin(0.0001f).of(0.7f)
    }

    @Test
    fun `median ignores order`() {
        val result = CoverAspectRatioCalculator.median(listOf(0.9f, 0.5f, 0.7f))
        assertThat(result).isEqualTo(0.7f)
    }

    @Test
    fun `median is robust to a single outlier`() {
        // Cuatro covers ~2:3 (0.667) y una apaisada atípica (1.5): con 5
        // elementos la mediana es el 3º ordenado (0.667), no el outlier.
        val result = CoverAspectRatioCalculator.median(
            listOf(0.66f, 0.665f, 0.667f, 0.68f, 1.5f),
        )
        assertThat(result).isWithin(0.001f).of(0.667f)
    }

    @Test
    fun `median of single element returns it`() {
        val result = CoverAspectRatioCalculator.median(listOf(0.75f))
        assertThat(result).isEqualTo(0.75f)
    }
}
