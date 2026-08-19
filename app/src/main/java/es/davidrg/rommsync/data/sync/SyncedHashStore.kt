package es.davidrg.rommsync.data.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Almacena los SHA-1 de saves ya sincronizados con el servidor para poder
 * determinar localmente qué saves tienen cambios pendientes sin contactar
 * la API.
 *
 * Usa SharedPreferences planas (no necesita cifrado ni reactividad).
 * Clave: "romId_fileName", Valor: sha1 hex.
 *
 * ponytail: SharedPreferences es suficiente para un mapa de hashes.
 * Si el volumen crece a miles de entries, migrar a un fichero JSON o tabla Room.
 */
class SyncedHashStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSyncedHash(romId: Int, fileName: String): String? {
        return prefs.getString(key(romId, fileName), null)
    }

    fun setSyncedHash(romId: Int, fileName: String, sha1: String) {
        prefs.edit().putString(key(romId, fileName), sha1).apply()
    }

    // ── Mtime cache: saltarse el zipeo+hash de saves sin cambios ──────────

    /** Fingerprint del último sync exitoso para un ROM (o null si ninguno). */
    fun getFingerprint(romId: Int): String? =
        prefs.getString("fp_$romId", null)

    /** Guarda el fingerprint de los saves de un ROM tras un sync exitoso. */
    fun setFingerprint(romId: Int, fingerprint: String) {
        prefs.edit().putString("fp_$romId", fingerprint).apply()
    }

    fun removeSyncedHash(romId: Int, fileName: String) {
        prefs.edit().remove(key(romId, fileName)).apply()
    }

    fun isAlreadySynced(romId: Int, fileName: String, currentSha1: String): Boolean {
        return getSyncedHash(romId, fileName) == currentSha1
    }

    // ── Mtime cache: saltarse el zipeo+hash de saves sin cambios ──────────

    /**
     * Devuelve el SHA-1 guardado la última vez que se zipeó+hasheó este save,
     * solo si el mtime y tamaño del fichero zip temporal no han cambiado desde
     * entonces. Permite al sync completo saltarse el trabajo pesado.
     *
     * La clave mtime es del fichero de origen (guardado por el handler).
     */
    fun getCachedHashIfUnchanged(romId: Int, fileName: String, sourceMtime: Long, sourceSize: Long): String? {
        val stamp = prefs.getString(mtimeKey(romId, fileName), null) ?: return null
        val parts = stamp.split(":")
        if (parts.size != 2) return null
        val savedMtime = parts[0].toLongOrNull() ?: return null
        val savedSize = parts[1].toLongOrNull() ?: return null
        if (savedMtime != sourceMtime || savedSize != sourceSize) return null
        return getSyncedHash(romId, fileName)
    }

    /** Registra mtime+tamaño del save de origen junto al hash sincronizado. */
    fun stampSource(romId: Int, fileName: String, sourceMtime: Long, sourceSize: Long) {
        prefs.edit().putString(mtimeKey(romId, fileName), "$sourceMtime:$sourceSize").apply()
    }

    private fun key(romId: Int, fileName: String) = "${romId}_$fileName"
    private fun mtimeKey(romId: Int, fileName: String) = "mt_${romId}_$fileName"

    companion object {
        private const val PREFS_NAME = "synced_save_hashes"
    }
}
