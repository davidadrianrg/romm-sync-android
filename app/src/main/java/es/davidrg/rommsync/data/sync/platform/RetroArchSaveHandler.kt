package es.davidrg.rommsync.data.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Handler de saves para RetroArch.
 *
 * Estructura esperada en disco (estilo ES-DE, con subcarpetas por slug):
 * ```
 * {retroarchBase}/saves/{platformSlug}/{romBaseName}.srm
 * {retroarchBase}/states/{platformSlug}/{romBaseName}.state
 * {retroarchBase}/states/{platformSlug}/{romBaseName}.state1
 * ...
 * ```
 *
 * RetroArch también puede escribir saves/states directamente en la raíz de
 * `saves/` y `states/` (comportamiento por defecto si no se activa el
 * ordenamiento por plataforma). Se buscan AMBAS ubicaciones: primero la
 * subcarpeta del slug y luego la raíz; si un mismo nombre existe en las dos,
 * gana la subcarpeta.
 *
 * Los states (.state, .state1..., .state.auto) se sincronizan igual que los
 * saves.
 */
class RetroArchSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val romBaseName = romFileName.substringBeforeLast('.')
        val seenNames = mutableSetOf<String>()

        // Directorios candidatos en orden de prioridad: slug primero, raíz después.
        val saveDirs = listOf(
            File(savesBasePath, "saves/$platformSlug"),
            File(savesBasePath, "saves"),
        )
        val stateDirs = listOf(
            File(savesBasePath, "states/$platformSlug"),
            File(savesBasePath, "states"),
        )

        for (dir in saveDirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter { file ->
                file.isFile && file.nameWithoutExtension.equals(romBaseName, ignoreCase = true)
                    && SAVE_EXTENSIONS.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
            }?.forEach { file ->
                if (seenNames.add(file.name.lowercase())) {
                    results.add(file.toLocalSave(romId))
                }
            }
        }

        for (dir in stateDirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter { file ->
                file.isFile &&
                    file.name.startsWith("$romBaseName.state", ignoreCase = true) &&
                    STATE_PATTERN.matches(file.name)
            }?.forEach { file ->
                if (seenNames.add(file.name.lowercase())) {
                    results.add(file.toLocalSave(romId))
                }
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
        val isState = STATE_PATTERN.matches(targetFileName)
        val subDir = if (isState) "states" else "saves"

        // Restaurar donde viva ya el fichero (slug o raíz); si no existe
        // todavía, preferir la subcarpeta del slug si ya existe, si no la raíz.
        val slugDir = File(savesBasePath, "$subDir/$platformSlug")
        val rootDir = File(savesBasePath, subDir)
        val targetDir = when {
            File(slugDir, targetFileName).isFile -> slugDir
            File(rootDir, targetFileName).isFile -> rootDir
            slugDir.isDirectory -> slugDir
            rootDir.isDirectory -> rootDir
            else -> slugDir
        }
        targetDir.mkdirs()

        val targetFile = File(targetDir, targetFileName)
        try {
            tempFile.copyTo(targetFile, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun savesFingerprint(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val romBaseName = romFileName.substringBeforeLast('.')

        fun scan(dir: File, pattern: (String) -> Boolean): List<String> {
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && pattern(it.name) }
                ?.map { "${it.name}:${it.length()}:${it.lastModified()}" }
                ?: emptyList()
        }

        val isSave = { name: String ->
            name.substringBeforeLast('.').equals(romBaseName, ignoreCase = true) &&
                SAVE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        }
        val isState = { name: String ->
            name.startsWith("$romBaseName.state", ignoreCase = true) &&
                STATE_PATTERN.matches(name)
        }

        val parts = (
            scan(File(savesBasePath, "saves/$platformSlug"), isSave) +
                scan(File(savesBasePath, "saves"), isSave) +
                scan(File(savesBasePath, "states/$platformSlug"), isState) +
                scan(File(savesBasePath, "states"), isState)
            ).sorted()
        if (parts.isEmpty()) null else parts.joinToString("|")
    }

    companion object {
        private val SAVE_EXTENSIONS = listOf(".srm", ".sav", ".save", ".eep", ".fla")

        /** .state, .state1... y .state.auto (auto-save de RetroArch). */
        private val STATE_PATTERN = Regex(
            ".*\\.state(\\d*|\\.auto)$",
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
