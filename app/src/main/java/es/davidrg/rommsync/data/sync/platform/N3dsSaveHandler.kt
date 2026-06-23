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
 * Handler de saves para Nintendo 3DS (Azahar / Citra / Lime3DS).
 *
 * Estructura esperada:
 * ```
 * {savesBasePath}/{id0}/{id1}/title/{titleIdHigh}/{titleIdLow}/data/
 * ```
 *
 * Las carpetas `{id0}` (32 hex) y `{id1}` (32 hex) son aleatorias por
 * instalacion de consola/emulador, por lo que hay que recorrer el arbol para
 * localizarlas. El `titleIdHigh` (8 hex, p.ej. `00040000` para juegos retail)
 * y el `titleIdLow` (8 hex) se derivan del title-id de 16 hex extraido del
 * nombre del ROM.
 *
 * Solo se sincroniza la carpeta `data/` (los ficheros de save). Al restaurar
 * en otro dispositivo se reconstruye la ruta usando los `id0`/`id1` locales.
 *
 * Ruta base por defecto:
 * `/storage/emulated/0/Android/data/org.azahar_emu.azahar/files/sdmc/Nintendo 3DS`
 */
class N3dsSaveHandler : SaveHandler {

    override suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
    ): List<LocalSave> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalSave>()
        val titleId = extractTitleId(romFileName) ?: return@withContext results
        val high = titleId.take(8)
        val low = titleId.takeLast(8)

        val dataDir = findDataDir(savesBasePath, high, low) ?: return@withContext results
        val files = dataDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return@withContext results

        val newestMtime = files.maxOf { it.lastModified() }
        val zipFile = File.createTempFile("3ds_save_${titleId}_", ".zip")
        zipFolder(dataDir, zipFile)

        results.add(
            LocalSave(
                romId = romId,
                fileName = "${titleId}_3ds_save.zip",
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
        val high = titleId.take(8)
        val low = titleId.takeLast(8)

        val dataDir = findDataDir(savesBasePath, high, low)
            ?: constructDataDir(savesBasePath, high, low)
            ?: return@withContext false

        // Borrar saves existentes antes de restaurar
        if (dataDir.isDirectory) {
            dataDir.deleteRecursively()
        }
        dataDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(tempFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(dataDir, entry.name.removePrefix("${dataDir.name}/"))
                    if (!outFile.canonicalPath.startsWith(dataDir.canonicalPath)) {
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
     * Recorre `{base}/{id0}/{id1}/title/{high}/{low}/data` para localizar la
     * carpeta de save del juego. Devuelve la mas reciente si hay varias.
     */
    private fun findDataDir(basePath: String, high: String, low: String): File? {
        val root = File(basePath)
        if (!root.isDirectory) return null

        var best: File? = null
        var bestMtime = 0L

        root.listFiles()?.filter { it.isDirectory }?.forEach { id0 ->
            id0.listFiles()?.filter { it.isDirectory }?.forEach { id1 ->
                val titleDir = File(id1, "title")
                if (!titleDir.isDirectory) return@forEach
                val dataDir = File(titleDir, "$high/$low/data")
                if (dataDir.isDirectory) {
                    val mtime = folderLastModified(dataDir)
                    if (mtime >= bestMtime) {
                        bestMtime = mtime
                        best = dataDir
                    }
                }
            }
        }

        return best
    }

    /**
     * Construye la ruta `data` usando los `id0`/`id1` existentes en el
     * dispositivo. Necesario al restaurar un save descargado cuando aun no
     * existe la carpeta del juego.
     */
    private fun constructDataDir(basePath: String, high: String, low: String): File? {
        val root = File(basePath)
        val id0 = root.listFiles()?.firstOrNull { it.isDirectory } ?: return null
        val id1 = id0.listFiles()?.firstOrNull { it.isDirectory } ?: return null
        return File(id1, "title/$high/$low/data")
    }

    /**
     * Extrae un title-id de 3DS (16 hex chars) del nombre del fichero ROM.
     * Formatos: "[0004000000030200] Game.3ds", "Game [0004000000030200].cci"
     */
    private fun extractTitleId(fileName: String): String? {
        val pattern = Regex("""\[?([0-9A-Fa-f]{16})\]?""")
        val match = pattern.find(fileName)
        return match?.groupValues?.get(1)?.uppercase()
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
        const val DEFAULT_SAVES_PATH =
            "/storage/emulated/0/Android/data/org.azahar_emu.azahar/files/sdmc/Nintendo 3DS"
    }
}
