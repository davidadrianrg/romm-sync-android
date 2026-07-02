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
 * Handler de saves para juegos nativos Android.
 *
 * A diferencia de los emuladores, cada juego Android guarda sus datos en una
 * ubicación distinta. Por tanto, este handler requiere que la ruta de saves se
 * configure por juego (savesPathOverride en DownloadedRomEntity) o por
 * plataforma (savesPathOverride en PlatformEntity).
 *
 * Comportamiento:
 * - findSaves: zipea todo el contenido de savesBasePath como un único asset.
 * - extractDownload: descomprime el zip en savesBasePath (reemplaza contenido).
 *
 * Ruta base por defecto (genérica, el usuario debe configurarla por juego):
 * `/storage/emulated/0/Android/data`
 *
 * ponytail: No hay forma genérica de descubrir saves de juegos Android
 * arbitrarios. El usuario debe indicar la ruta manualmente por juego.
 * Si en el futuro se necesita auto-descubrimiento, habría que integrar SAF.
 */
class AndroidSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val saveDir = File(savesBasePath)
        if (!saveDir.isDirectory) return@withContext emptyList()

        val files = saveDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext emptyList()

        val newestMtime = files.maxOf { it.lastModified() }
        val romBaseName = romFileName.substringBeforeLast('.')
        val zipFile = File.createTempFile("android_save_${romBaseName}_", ".zip")
        zipFolder(saveDir, zipFile)

        listOf(
            LocalSave(
                romId = romId,
                fileName = "${romBaseName}_android_save.zip",
                file = zipFile,
                lastModified = newestMtime,
                sha1 = zipFile.sha1(),
            ),
        )
    }

    override suspend fun prepareForUpload(save: LocalSave): File = save.file

    override suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val saveDir = File(savesBasePath)
        // Limpiar contenido existente antes de restaurar
        if (saveDir.isDirectory) {
            saveDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        saveDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    // Eliminar el directorio raíz del zip (nombre de la carpeta original)
                    val entryName = entry.name.substringAfter('/', entry.name)
                    if (entryName.isEmpty()) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    val outFile = File(saveDir, entryName)
                    // Protección path traversal
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
        const val DEFAULT_SAVES_PATH = "/storage/emulated/0/Android/data"
    }
}
