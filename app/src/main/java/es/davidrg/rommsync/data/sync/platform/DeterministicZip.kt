package es.davidrg.rommsync.data.sync.platform

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utilidades de zipeado determinista compartidas por los handlers de saves.
 *
 * Un zip "determinista" usa timestamps de entrada FIJOS (1980-01-01, el mínimo
 * que acepta el formato zip). Sin esto, cada ZipEntry hornea la hora actual:
 * dos corridas sobre los mismos ficheros producen bytes (y por tanto SHA-1)
 * distintos, el servidor ve "contenido nuevo" en cada negociación y ordena
 * re-subir saves que no cambiaron — el bug de re-uploads infinitos.
 *
 * Con timestamps fijos, mismo contenido ⇒ mismos bytes ⇒ mismo SHA-1 ⇒ el
 * hash cacheado tras el primer upload sirve para siempre.
 */
object DeterministicZip {

    /** 1980-01-01 00:00:00 en formato DOS (mínimo legal del formato zip). */
    private const val FIXED_TIME = 315532800000L

    fun fixedEntry(name: String): ZipEntry =
        ZipEntry(name).apply { time = FIXED_TIME }

    fun fixedDirEntry(name: String): ZipEntry =
        ZipEntry(name).apply { time = FIXED_TIME }
}

/**
 * Zipea [folder] completa bajo el prefijo "{folder.name}/" con timestamps fijos.
 */
internal fun zipFolderDeterministic(folder: File, output: File) {
    java.util.zip.ZipOutputStream(FileOutputStream(output)).use { zos ->
        folder.walkTopDown().forEach { file ->
            val relativePath = "${folder.name}/${folder.toPath().relativize(file.toPath())}"
            if (file.isFile) {
                zos.putNextEntry(DeterministicZip.fixedEntry(relativePath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            } else if (file != folder) {
                zos.putNextEntry(DeterministicZip.fixedDirEntry("$relativePath/"))
                zos.closeEntry()
            }
        }
    }
}

/**
 * Zipea varias carpetas en un único archivo, cada una bajo su propio nombre.
 */
internal fun zipFoldersDeterministic(folders: List<File>, output: File) {
    java.util.zip.ZipOutputStream(FileOutputStream(output)).use { zos ->
        for (folder in folders) {
            folder.walkTopDown().forEach { file ->
                val relativePath = "${folder.name}/${folder.toPath().relativize(file.toPath())}"
                if (file.isFile) {
                    zos.putNextEntry(DeterministicZip.fixedEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } else if (file != folder) {
                    zos.putNextEntry(DeterministicZip.fixedDirEntry("$relativePath/"))
                    zos.closeEntry()
                }
            }
        }
    }
}

/**
 * Zipea [folder] con rutas relativas a [root] (sin prefijo de nombre de carpeta),
 * con timestamps fijos.
 */
internal fun zipFolderRelativeToDeterministic(root: File, folder: File, output: File) {
    java.util.zip.ZipOutputStream(FileOutputStream(output)).use { zos ->
        folder.walkTopDown().forEach { file ->
            val relativePath = root.toPath().relativize(file.toPath()).toString()
            if (relativePath.isEmpty()) return@forEach
            if (file.isFile) {
                zos.putNextEntry(DeterministicZip.fixedEntry(relativePath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            } else if (file != folder) {
                zos.putNextEntry(DeterministicZip.fixedDirEntry("$relativePath/"))
                zos.closeEntry()
            }
        }
    }
}

/**
 * Zipea ficheros sueltos (sin estructura de carpetas) con timestamps fijos.
 */
internal fun zipFilesDeterministic(files: List<File>, output: File) {
    java.util.zip.ZipOutputStream(FileOutputStream(output)).use { zos ->
        for (file in files) {
            zos.putNextEntry(DeterministicZip.fixedEntry(file.name))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
