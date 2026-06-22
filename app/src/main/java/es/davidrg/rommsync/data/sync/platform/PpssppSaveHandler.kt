package es.davidrg.rommsync.data.sync.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handler de saves para PPSSPP (PlayStation Portable).
 *
 * Estructura esperada:
 * ```
 * {ppssppBase}/PSP/SAVEDATA/{discId}<...>/
 * ```
 *
 * Cada juego genera N carpetas con un prefijo de disc-id (9 caracteres,
 * p.ej. `ULUS10064`). Todas las carpetas que comparten el prefijo pertenecen
 * al mismo juego (DATA00, SETTINGS, etc.) y se agrupan en un zip para upload.
 *
 * NOTA: esta versión usa el nombre del ROM como proxy del disc-id (eliminando
 * extensión y tags). En fases futuras se puede extraer el disc-id del ISO.
 */
class PpssppSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val saveDataDir = File(savesBasePath)
        if (!saveDataDir.isDirectory) return@withContext results

        // Intentar buscar por disc-id si el filename tiene formato conocido
        val discId = extractDiscIdFromFileName(romFileName)
        if (discId == null) return@withContext results

        val matchedFolders = saveDataDir.listFiles()?.filter { dir ->
            dir.isDirectory && dir.name.startsWith(discId, ignoreCase = true)
        } ?: return@withContext results

        if (matchedFolders.isEmpty()) return@withContext results

        // Agrupar todas las carpetas en un zip virtual
        val newestMtime = matchedFolders.maxOf { folderLastModified(it) }
        val zipFile = File.createTempFile("ppsspp_save_${discId}_", ".zip")
        zipFolders(matchedFolders, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${discId}_saves.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )

        results
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val saveDataDir = File(savesBasePath)
        saveDataDir.mkdirs()

        val discId = extractDiscIdFromFileName(romFileName) ?: return@withContext false

        // Borrar carpetas existentes del mismo disc-id antes de extraer
        saveDataDir.listFiles()?.filter { dir ->
            dir.isDirectory && dir.name.startsWith(discId, ignoreCase = true)
        }?.forEach { it.deleteRecursively() }

        // Extraer zip en PSP/SAVEDATA/
        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(saveDataDir, entry.name)
                    // Protección path traversal
                    if (!outFile.canonicalPath.startsWith(saveDataDir.canonicalPath)) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zipIn.copyTo(out)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intenta extraer un disc-id PSP del nombre del ROM.
     * Formato típico: "ULUS10064 - Game Name.iso" o "[ULUS10064] Game Name.cso"
     */
    private fun extractDiscIdFromFileName(fileName: String): String? {
        val patterns = listOf(
            Regex("""\[?([A-Z]{4}\d{5})\]?"""),
            Regex("""([A-Z]{4}-\d{5})"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(fileName)
            if (match != null) {
                return match.groupValues[1].replace("-", "")
            }
        }
        return null
    }

    private fun folderLastModified(dir: File): Long {
        var newest = dir.lastModified()
        dir.walkTopDown().forEach { file ->
            if (file.lastModified() > newest) newest = file.lastModified()
        }
        return newest
    }

    private fun zipFolders(folders: List<File>, output: File) {
        ZipOutputStream(FileOutputStream(output)).use { zos ->
            for (folder in folders) {
                folder.walkTopDown().forEach { file ->
                    val relativePath = "${folder.name}/${folder.toPath().relativize(file.toPath())}"
                    if (file.isFile) {
                        zos.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    } else if (file != folder) {
                        zos.putNextEntry(ZipEntry("$relativePath/"))
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/PSP/SAVEDATA"
    }
}
