package es.davidrg.rommsync.download

import java.io.File

/**
 * Maps RomM platform slugs to ES-DE compatible local folder structure.
 *
 * RomM Slug → Local path:
 *   snes      → /ROMs/snes/
 *   megadrive → /ROMs/megadrive/
 *
 * The root path (e.g. /storage/emulated/0/ROMs) is provided by the user
 * via DataStore configuration.
 */
object PathMapper {

    /**
     * Returns the full local directory for a platform slug.
     * Does NOT create the directory — caller is responsible.
     */
    fun getPlatformDir(romsRootPath: String, platformSlug: String): File {
        val normalizedSlug = platformSlug.lowercase().trim()
        return File(romsRootPath, normalizedSlug)
    }

    /**
     * Returns the full local file path for a ROM.
     */
    fun getRomFile(romsRootPath: String, platformSlug: String, fileName: String): File {
        return File(getPlatformDir(romsRootPath, platformSlug), fileName)
    }

    /**
     * Ensures the platform directory exists. Returns true if created or already exists.
     */
    fun ensurePlatformDir(romsRootPath: String, platformSlug: String): Boolean {
        val dir = getPlatformDir(romsRootPath, platformSlug)
        if (!dir.exists()) {
            return dir.mkdirs()
        }
        return true
    }
}
