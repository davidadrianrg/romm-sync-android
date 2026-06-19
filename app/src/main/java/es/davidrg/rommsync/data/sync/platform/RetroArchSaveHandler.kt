package es.davidrg.rommsync.data.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Handler de saves para RetroArch.
 *
 * Estructura esperada en disco (subcarpetas por slug de plataforma, estilo ES-DE):
 * ```
 * {retroarchBase}/saves/{platformSlug}/{romBaseName}.srm
 * {retroarchBase}/states/{platformSlug}/{romBaseName}.state
 * {retroarchBase}/states/{platformSlug}/{romBaseName}.state1
 * ...
 * ```
 *
 * Los saves son ficheros planos cuyo nombre coincide con el nombre base del
 * ROM (sin extensión). Se buscan en la carpeta `saves/{slug}/` y opcionalmente
 * states en `states/{slug}/`.
 */
class RetroArchSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val romBaseName = romFileName.substringBeforeLast('.')

        // Buscar saves (.srm, .sav, etc.)
        val savesDir = File(savesBasePath, "saves/$platformSlug")
        if (savesDir.isDirectory) {
            savesDir.listFiles()?.filter { file ->
                file.isFile && file.nameWithoutExtension.equals(romBaseName, ignoreCase = true)
                    && SAVE_EXTENSIONS.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
            }?.forEach { file ->
                results.add(file.toLocalSave(romId))
            }
        }

        // Buscar states (.state, .state1, .state2, ...)
        val statesDir = File(savesBasePath, "states/$platformSlug")
        if (statesDir.isDirectory) {
            statesDir.listFiles()?.filter { file ->
                file.isFile && file.name.startsWith(romBaseName, ignoreCase = true)
                    && STATE_PATTERN.matches(file.name)
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
        // Determinar si es un save o un state por extensión
        val isState = STATE_PATTERN.matches(targetFileName)
        val subDir = if (isState) "states" else "saves"
        val targetDir = File(savesBasePath, "$subDir/$platformSlug")
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
        private val SAVE_EXTENSIONS = listOf(".srm", ".sav", ".save", ".eep", ".fla")
        private val STATE_PATTERN = Regex(
            ".*\\.state\\d*$",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Calcula SHA-1 de un fichero y construye un [LocalSave].
 */
internal suspend fun File.toLocalSave(romId: Int): LocalSave = withContext(Dispatchers.IO) {
    LocalSave(
        romId = romId,
        fileName = name,
        file = this@toLocalSave,
        lastModified = lastModified(),
        sha1 = sha1(),
    )
}

/**
 * Calcula el SHA-1 hex de un fichero.
 */
internal fun File.sha1(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
