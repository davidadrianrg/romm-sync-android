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

    fun removeSyncedHash(romId: Int, fileName: String) {
        prefs.edit().remove(key(romId, fileName)).apply()
    }

    fun isAlreadySynced(romId: Int, fileName: String, currentSha1: String): Boolean {
        return getSyncedHash(romId, fileName) == currentSha1
    }

    private fun key(romId: Int, fileName: String) = "${romId}_$fileName"

    companion object {
        private const val PREFS_NAME = "synced_save_hashes"
    }
}
