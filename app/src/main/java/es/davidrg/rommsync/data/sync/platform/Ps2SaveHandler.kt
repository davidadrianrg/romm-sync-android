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
 * Handler de saves para PS2 (AetherSX2 / NetherSX2) en modo Folder Memory Card.
 *
 * Estructura esperada:
 * ```
 * {basePath}/memcards/{cardName}.ps2/{BAserial}/
 * ```
 *
 * El emulador debe estar configurado con "Folder Memory Card" activo.
 * Cada juego tiene su propia subcarpeta identificada por serial del disco
 * con prefijo "BA" (p.ej. `BASLUS-21050`).
 *
 * Para sync se zipea la carpeta del serial y se sube/baja como un solo asset.
 *
 * NOTA: Si hay multiples memory cards (.ps2 dirs), se busca en todas.
 * El serial se extrae del nombre del ROM.
 */
class Ps2SaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val memcardsDir = File(savesBasePath, "memcards")
        if (!memcardsDir.isDirectory) return@withContext results

        val serial = extractSerialFromFileName(romFileName) ?: return@withContext results
        val baSerial = toBaFolderName(serial)

        // Buscar en todas las memory cards (.ps2 dirs)
        val cardDirs = memcardsDir.listFiles()?.filter {
            it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true)
        } ?: return@withContext results

        for (cardDir in cardDirs) {
            val saveFolder = cardDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && matchesFolderName(dir.name, serial)
            } ?: continue

            val mtime = folderLastModified(saveFolder)
            val zipFile = File.createTempFile("ps2_save_${baSerial}_", ".zip")
            zipFolder(saveFolder, zipFile)

            results.add(
                LocalSave(
                    romId = romId,
                    fileName = "${baSerial}_save.zip",
                    file = zipFile,
                    lastModified = mtime,
                    sha1 = zipFile.sha1(),
                ),
            )
            break // Usar la primera memory card que tenga el save
        }

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
        val memcardsDir = File(savesBasePath, "memcards")
        memcardsDir.mkdirs()

        val serial = extractSerialFromFileName(romFileName) ?: return@withContext false
        val baSerial = toBaFolderName(serial)

        // Encontrar o crear una memory card
        val cardDirs = memcardsDir.listFiles()?.filter {
            it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true)
        } ?: emptyList()

        val targetCard = if (cardDirs.isEmpty()) {
            File(memcardsDir, "Shared.ps2").also { it.mkdirs() }
        } else {
            cardDirs[0]
        }

        // Borrar carpeta existente del serial
        targetCard.listFiles()?.filter { dir ->
            dir.isDirectory && matchesFolderName(dir.name, serial)
        }?.forEach { it.deleteRecursively() }

        // Extraer zip dentro de la memory card
        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetCard, entry.name)
                    if (!outFile.canonicalPath.startsWith(targetCard.canonicalPath)) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
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
     * Intenta extraer un serial PS2 del nombre del ROM.
     * Formatos típicos: "SLUS-21050 - Game.iso", "[SLUS_21050] Game.iso"
     */
    private fun extractSerialFromFileName(fileName: String): String? {
        val patterns = listOf(
            Regex("""([A-Z]{4}[-_]\d{5})"""),
            Regex("""\[?([A-Z]{4}\d{5})\]?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(fileName)
            if (match != null) {
                return match.groupValues[1].replace("_", "-")
            }
        }
        return null
    }

    /**
     * Convierte un serial PS2 (SLUS-21050) al formato carpeta BA (BASLUS-21050).
     */
    private fun toBaFolderName(serial: String): String {
        val cleaned = serial.replace("-", "").replace("_", "")
        val match = Regex("^([A-Za-z]{4})(\\d+)$").find(cleaned)
        return if (match != null) {
            "BA${match.groupValues[1].uppercase()}-${match.groupValues[2]}"
        } else {
            "BA$cleaned"
        }
    }

    private fun matchesFolderName(folderName: String, serial: String): Boolean {
        val stripped = serial.replace("-", "")
        val baSerial = if (stripped.startsWith("BA", ignoreCase = true)) stripped else "BA$stripped"
        val folderStripped = folderName.replace("-", "")
        return folderStripped.startsWith(baSerial, ignoreCase = true)
    }

    private fun folderLastModified(dir: File): Long {
        var newest = dir.lastModified()
        dir.walkTopDown().forEach { file ->
            if (file.lastModified() > newest) newest = file.lastModified()
        }
        return newest
    }

    private fun zipFolder(folder: File, output: File) {
        ZipOutputStream(FileOutputStream(output)).use { zos ->
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

    companion object {
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/AetherSX2"
    }
}
