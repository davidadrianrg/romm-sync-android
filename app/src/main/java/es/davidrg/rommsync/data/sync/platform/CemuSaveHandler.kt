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
 * Handler de saves para Wii U (Cemu).
 *
 * Estructura esperada:
 * ```
 * {savesBasePath}/{titleIdLow}/user/...
 * ```
 *
 * Cemu guarda las partidas en `mlc01/usr/save/{titleIdHigh}/{titleIdLow}/`.
 * Para juegos de disco el `titleIdHigh` es siempre `00050000`, que ya forma
 * parte de la ruta base por defecto. La carpeta del juego es el `titleIdLow`
 * (8 hex), que se extrae del nombre del ROM (title-id de 16 hex).
 *
 * La carpeta del juego se zipea entera para subir al servidor.
 *
 * Ruta base por defecto:
 * `/storage/emulated/0/Android/data/info.cemu.cemu/files/mlc01/usr/save/00050000`
 */
class CemuSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val titleId = extractTitleId(romFileName) ?: return@withContext results
        val titleIdLow = titleId.takeLast(8)

        val saveDir = findSaveDir(savesBasePath, titleIdLow) ?: return@withContext results
        if (!saveDir.isDirectory) return@withContext results

        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext results

        val newestMtime = files.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("wiiu_save_${titleIdLow}_", ".zip")
        zipFolder(saveDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${titleId}_wiiu_save.zip",
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
        val titleId = extractTitleId(romFileName) ?: return@withContext false
        val titleIdLow = titleId.takeLast(8)

        val saveDir = findSaveDir(savesBasePath, titleIdLow)
            ?: File(savesBasePath, titleIdLow)

        // Borrar saves existentes antes de restaurar
        if (saveDir.isDirectory) {
            saveDir.deleteRecursively()
        }
        saveDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(saveDir, entry.name.removePrefix("${saveDir.name}/"))
                    if (!outFile.canonicalPath.startsWith(saveDir.canonicalPath)) {
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
     * Busca la carpeta de save del juego (title-id low) bajo la ruta base.
     */
    private fun findSaveDir(basePath: String, titleIdLow: String): File? {
        val saveRoot = File(basePath)
        if (!saveRoot.isDirectory) return null

        return saveRoot.listFiles()?.firstOrNull { dir ->
            dir.isDirectory && dir.name.equals(titleIdLow, ignoreCase = true)
        }
    }

    /**
     * Extrae un title-id de Wii U (16 hex chars) del nombre del fichero ROM.
     * Formatos: "[0005000010101D00] Game.wux", "Game [0005000010101D00].rpx"
     */
    private fun extractTitleId(fileName: String): String? {
        val pattern = Regex("""\[?([0-9A-Fa-f]{16})\]?""")
        val match = pattern.find(fileName)
        return match?.groupValues?.get(1)?.uppercase()
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
        const val DEFAULT_SAVES_PATH =
            "/storage/emulated/0/Android/data/info.cemu.cemu/files/mlc01/usr/save/00050000"
    }
}
