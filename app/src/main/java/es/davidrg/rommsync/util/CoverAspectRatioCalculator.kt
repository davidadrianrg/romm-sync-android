package es.davidrg.rommsync.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calcula el aspect ratio (ancho/alto) real de los covers de una plataforma
 * muestreando las dimensiones de un subconjunto de imágenes.
 *
 * El valor de aspect ratio que reporta RomM por plataforma no siempre es
 * fiable, así que medimos directamente sobre las imágenes. Se usa el
 * [ImageLoader] singleton de Coil, que ya cachea en disco: las covers
 * muestreadas normalmente ya están descargadas para la rejilla.
 *
 * Se toma la MEDIANA de los ratios (robusta frente a covers atípicas como
 * carátulas apaisadas sueltas).
 */
class CoverAspectRatioCalculator(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {

    /**
     * Muestrea hasta [sampleSize] URLs de [coverUrls], decodifica sus
     * dimensiones y devuelve la mediana de ancho/alto. Devuelve null si no se
     * pudo medir ninguna cover.
     */
    suspend fun measure(
        coverUrls: List<String>,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
    ): Float? = withContext(Dispatchers.IO) {
        val sample = coverUrls.filter { it.isNotBlank() }.take(sampleSize)
        if (sample.isEmpty()) return@withContext null

        val ratios = sample.mapNotNull { url -> measureOne(url) }
        if (ratios.isEmpty()) return@withContext null

        median(ratios)
    }

    private suspend fun measureOne(url: String): Float? {
        // allowHardware(false) garantiza acceso a las dimensiones del bitmap.
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request)
        if (result !is SuccessResult) return null
        val drawable = result.drawable
        val width: Int
        val height: Int
        if (drawable is BitmapDrawable) {
            width = drawable.bitmap.width
            height = drawable.bitmap.height
        } else {
            width = drawable.intrinsicWidth
            height = drawable.intrinsicHeight
        }
        if (width <= 0 || height <= 0) return null
        return width.toFloat() / height.toFloat()
    }

    companion object {
        const val DEFAULT_SAMPLE_SIZE = 12

        /**
         * Mediana de una lista de ratios. Robusta frente a covers atípicas.
         * Pública para poder testearla sin dependencias de Android/Coil.
         */
        internal fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2f
            } else {
                sorted[mid]
            }
        }
    }
}
