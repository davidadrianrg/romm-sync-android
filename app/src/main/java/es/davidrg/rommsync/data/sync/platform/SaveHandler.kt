package es.davidrg.rommsync.data.sync.platform

import java.io.File

/**
 * Representa un archivo de save localizado en disco.
 */
data class LocalSave(
    val romId: Int,
    val fileName: String,
    val file: File,
    val lastModified: Long,
    val sha1: String,
)

/**
 * Interfaz base para la localización y gestión de saves por plataforma/emulador.
 *
 * Cada plataforma/emulador implementa esta interfaz para que el SyncCoordinator
 * pueda encontrar saves, prepararlos para subir y extraerlos al descargar.
 */
interface SaveHandler {

    /**
     * Huella barata de una carpeta de saves: cambia si se añade/quita/modifica
     * cualquier fichero. "n:sum(mtime):sum(size)" sin leer contenidos.
     */
    fun folderFingerprint(dir: File): String? {
        if (!dir.isDirectory) return null
        var n = 0L
        var mtimeSum = 0L
        var sizeSum = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                n++
                mtimeSum += f.lastModified()
                sizeSum += f.length()
            }
        }
        return if (n == 0L) null else "$n:$mtimeSum:$sizeSum"
    }

    /**
     * Localiza todos los ficheros de save en disco para un ROM dado.
     *
     * @param romId ID del ROM en RomM.
     * @param romFileName Nombre del fichero ROM (con extensión).
     * @param platformSlug Slug de la plataforma (estilo ES-DE: gba, snes, etc.).
     * @param savesBasePath Ruta base donde buscar saves.
     * @param romLocalPath Ruta local del ROM en disco (opcional). Si se
     *   proporciona, los handlers que soportan extracción de ID por header
     *   binario (PSP/PS2/GC/Wii) la usarán para leer el ID real del juego en
     *   lugar de inferirlo del nombre del fichero.
     * @return Lista de saves encontrados.
     */
    suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        romLocalPath: String? = null,
    ): List<LocalSave>

    /**
     * Prepara un save para upload. Para ficheros planos devuelve el propio
     * fichero. Para saves de carpeta devuelve un zip temporal.
     */
    suspend fun prepareForUpload(save: LocalSave): File = save.file

    /**
     * Extrae un save descargado del servidor al destino correcto en disco.
     *
     * @param tempFile Fichero temporal descargado del servidor.
     * @param romFileName Nombre del fichero ROM (con extensión).
     * @param platformSlug Slug de la plataforma.
     * @param savesBasePath Ruta base de saves.
     * @param targetFileName Nombre del fichero destino (proporcionado por el servidor).
     * @return true si se extrajo correctamente.
     */
    suspend fun extractDownload(
        tempFile: File,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
        targetFileName: String,
    ): Boolean

    /**
     * Huella barata del estado de los saves de un ROM sin zipear ni hashear.
     *
     * Devuelve una cadena que cambia si CUALQUIER save del ROM cambió:
     * "n_ficheros:sum(mtime):sum(size)" de la carpeta/metadatos que el
     * handler considere. Si coincide con la del último sync, el coordinator
     * puede saltarse findSaves (zipeo+hash) por completo.
     *
     * Implementación por defecto: null (sin shortcut disponible).
     */
    suspend fun savesFingerprint(romId: Int, romFileName: String, platformSlug: String, savesBasePath: String, romLocalPath: String?): String? = null
}
