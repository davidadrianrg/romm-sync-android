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
 * Handler de saves para Dolphin (GameCube y Wii).
 *
 * GameCube (modo GCI Folder):
 * ```
 * {dolphinBase}/GC/{region}/Card A/{gameId}-*.gci
 * ```
 *
 * Wii:
 * ```
 * {dolphinBase}/Wii/title/{titleIdHigh}/{titleIdLow}/data/
 * ```
 *
 * En Android, la base es normalmente `/storage/emulated/0/dolphin-emu/`.
 *
 * Para GC: se buscan ficheros .gci cuyo nombre empieza con el game-id (6 chars,
 * extraído del nombre del ROM). Se agrupan en zip para sync.
 *
 * Para Wii: se busca la carpeta de título y se zipea entera.
 *
 * NOTA: esta versión extrae el game-id del nombre del fichero ROM si sigue el
 * formato estándar (p.ej. "GALE01 - Super Smash Bros Melee.iso").
 */
class DolphinSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val gameId = extractGameId(romFileName) ?: return@withContext results

        if (platformSlug.lowercase() in GC_SLUGS) {
            findGcSaves(romId, gameId, savesBasePath, results)
        } else {
            findWiiSaves(romId, gameId, savesBasePath, results)
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
        val gameId = extractGameId(romFileName) ?: return@withContext false

        if (platformSlug.lowercase() in GC_SLUGS) {
            extractGcDownload(tempFile, gameId, savesBasePath)
        } else {
            extractWiiDownload(tempFile, savesBasePath)
        }
    }

    // ── GameCube ──────────────────────────────────────────────────────────

    private fun findGcSaves(
        romId: Int,
        gameId: String,
        basePath: String,
        results: MutableList<LocalSave>,
    ) {
        // Buscar en todas las regiones
        val gcDir = File(basePath, "GC")
        if (!gcDir.isDirectory) return

        val gciFiles = mutableListOf<File>()
        REGIONS.forEach { region ->
            val cardDir = File(gcDir, "$region/Card A")
            if (cardDir.isDirectory) {
                cardDir.listFiles()?.filter { file ->
                    file.isFile && file.extension.equals("gci", ignoreCase = true)
                        && file.name.startsWith(gameId, ignoreCase = true)
                }?.let { gciFiles.addAll(it) }
            }
        }

        if (gciFiles.isEmpty()) return

        val newestMtime = gciFiles.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("dolphin_gc_${gameId}_", ".zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (gci in gciFiles) {
                zos.putNextEntry(ZipEntry(gci.name))
                gci.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${gameId}_gc_saves.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
    }

    private fun extractGcDownload(tempFile: File, gameId: String, basePath: String): Boolean {
        // Extraer a la primera región que exista, o USA por defecto
        val gcDir = File(basePath, "GC")
        val targetRegion = REGIONS.firstOrNull { File(gcDir, "$it/Card A").isDirectory } ?: "USA"
        val cardDir = File(gcDir, "$targetRegion/Card A")
        cardDir.mkdirs()

        // Borrar GCIs existentes del mismo game-id
        cardDir.listFiles()?.filter {
            it.isFile && it.name.startsWith(gameId, ignoreCase = true)
                && it.extension.equals("gci", ignoreCase = true)
        }?.forEach { it.delete() }

        return try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(cardDir, entry.name)
                        if (outFile.canonicalPath.startsWith(cardDir.canonicalPath)) {
                            FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
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

    // ── Wii ───────────────────────────────────────────────────────────────

    private fun findWiiSaves(
        romId: Int,
        gameId: String,
        basePath: String,
        results: MutableList<LocalSave>,
    ) {
        // Wii title IDs: los primeros 4 chars del game-id se mapean al title-id low
        // La estructura es: Wii/title/{high}/{low}/data/
        val wiiTitleDir = File(basePath, "Wii/title")
        if (!wiiTitleDir.isDirectory) return

        val dataDir = findWiiDataDir(wiiTitleDir, gameId) ?: return
        if (!dataDir.isDirectory || (dataDir.listFiles()?.isEmpty() != false)) return

        val newestMtime = folderLastModified(dataDir)
        val zipFile = File.createTempFile("dolphin_wii_${gameId}_", ".zip")
        zipFolder(dataDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${gameId}_wii_save.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
    }

    private fun extractWiiDownload(tempFile: File, basePath: String): Boolean {
        // Extraer directamente en Wii/title/ — el zip contiene la estructura data/...
        val wiiTitleDir = File(basePath, "Wii/title")
        wiiTitleDir.mkdirs()

        return try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(wiiTitleDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(wiiTitleDir.canonicalPath)) {
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
     * Busca la carpeta data/ del título Wii. Recorre la jerarquía title/{high}/{low}/data.
     */
    private fun findWiiDataDir(titleDir: File, gameId: String): File? {
        // Recorrer todas las subcarpetas buscando un match por game-id
        titleDir.listFiles()?.filter { it.isDirectory }?.forEach { highDir ->
            highDir.listFiles()?.filter { it.isDirectory }?.forEach { lowDir ->
                // El nombre del directorio low a veces contiene el game-id
                if (lowDir.name.contains(gameId.take(4), ignoreCase = true)) {
                    val dataDir = File(lowDir, "data")
                    if (dataDir.isDirectory) return dataDir
                }
            }
        }
        return null
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    /**
     * Extrae el game-id (6 chars como GALE01) del nombre del fichero ROM.
     * Formatos: "GALE01 - Name.iso", "[GALE01] Name.gcm", "GALE01.iso"
     */
    private fun extractGameId(fileName: String): String? {
        val patterns = listOf(
            Regex("""\[?([A-Z0-9]{6})\]?"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(fileName)
            if (match != null) {
                val candidate = match.groupValues[1]
                // Validar que parece un game-id: 4 letras + 2 alfanuméricos
                if (candidate.matches(Regex("[A-Z][A-Z0-9]{3}[A-Z0-9]{2}"))) {
                    return candidate
                }
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
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/dolphin-emu"
        private val GC_SLUGS = setOf("gc", "gamecube", "ngc")
        private val REGIONS = listOf("USA", "EUR", "JAP")
    }
}
