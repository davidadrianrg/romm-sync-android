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
 * Handler de saves para Switch (Eden, Yuzu, Sudachi).
 *
 * Estructura esperada (Eden en Android):
 * ```
 * {savesBasePath}/{userId}/{profileId}/{titleId}/
 * ```
 *
 * El `titleId` son 16 caracteres hexadecimales (p.ej. `0100000000010000`
 * para Super Mario Odyssey). Se extrae del nombre del ROM si esta disponible.
 * El `profileId` es otro ID de 16 hex chars asignado por el emulador al perfil
 * del usuario.
 *
 * Ruta base por defecto (nand/user/save):
 * `/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save`
 *
 * La carpeta de saves del titulo se zipea entera para subir al servidor.
 */
class SwitchSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val titleId = extractTitleId(romFileName) ?: return@withContext results

        val saveDir = findSaveDir(savesBasePath, titleId) ?: return@withContext results
        if (!saveDir.isDirectory) return@withContext results

        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext results

        val newestMtime = files.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("switch_save_${titleId}_", ".zip")
        zipFolder(saveDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${titleId}_switch_save.zip",
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

        // Determinar la ruta destino
        val saveDir = findSaveDir(savesBasePath, titleId)
            ?: File(savesBasePath, "$DEFAULT_USER_ID/$DEFAULT_PROFILE_ID/$titleId")

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
     * Busca la carpeta de saves para un title-id en la estructura NAND.
     * Estructura: {basePath}/{userId}/{profileId}/{titleId}/
     * Recorre user-ids y profile-ids para encontrar el titulo.
     */
    private fun findSaveDir(basePath: String, titleId: String): File? {
        val saveRoot = File(basePath)
        if (!saveRoot.isDirectory) return null

        // Recorrer cada user-id -> profile-id -> buscar title-id
        saveRoot.listFiles()?.filter { it.isDirectory }?.forEach { userDir ->
            userDir.listFiles()?.filter { it.isDirectory }?.forEach { profileDir ->
                val titleDir = profileDir.listFiles()?.firstOrNull { dir ->
                    dir.isDirectory && dir.name.equals(titleId, ignoreCase = true)
                }
                if (titleDir != null) return titleDir
            }
        }

        return null
    }

    /**
     * Extrae un title-id de Switch (16 hex chars) del nombre del fichero ROM.
     * Formatos: "[0100000000010000] Game.nsp", "Game [0100000000010000].xci"
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
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save"
        private const val DEFAULT_USER_ID = "0000000000000000"
        private const val DEFAULT_PROFILE_ID = "0000000000000001"
    }
}
