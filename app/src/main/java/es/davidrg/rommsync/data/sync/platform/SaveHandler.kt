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
     * Localiza todos los ficheros de save en disco para un ROM dado.
     *
     * @param romId ID del ROM en RomM.
     * @param romFileName Nombre del fichero ROM (con extensión).
     * @param platformSlug Slug de la plataforma (estilo ES-DE: gba, snes, etc.).
     * @param savesBasePath Ruta base donde buscar saves.
     * @return Lista de saves encontrados.
     */
    suspend fun findSaves(
        romId: Int,
        romFileName: String,
        platformSlug: String,
        savesBasePath: String,
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
}
