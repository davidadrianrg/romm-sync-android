package es.davidrg.rommsync.data.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handler de saves para melonDS (Nintendo DS).
 *
 * Estructura esperada:
 * ```
 * {savesBasePath}/{romBaseName}.sav
 * ```
 *
 * melonDS guarda las partidas como fichero plano `.sav` con el mismo
 * nombre base que la ROM.
 *
 * Ruta por defecto: la carpeta interna de la app melonDS en Android.
 */
class MelonDsSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val romBaseName = romFileName.substringBeforeLast('.')
        val savesDir = File(savesBasePath)

        if (savesDir.isDirectory) {
            savesDir.listFiles()?.filter { file ->
                file.isFile &&
                    file.nameWithoutExtension.equals(romBaseName, ignoreCase = true) &&
                    SAVE_EXTENSIONS.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
            }?.forEach { file ->
                results.add(file.toLocalSave(romId))
            }
        }

        results
    }

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(savesBasePath)
        targetDir.mkdirs()

        val targetFile = File(targetDir, targetFileName)
        try {
            tempFile.copyTo(targetFile, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data/me.magnum.melonds/files/saves"
        private val SAVE_EXTENSIONS = listOf(".sav", ".dsv")
    }
}
