package es.davidrg.rommsync.util

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Puente mínimo con el shell de root (`su`) para teléfonos rooteados.
 *
 * Caso de uso: las partidas de juegos Android nativos viven en
 * `/data/data/<paquete>/`, inaccesible con la API File de Java. Si el
 * dispositivo tiene root y concede su a la app, los handlers pueden usar
 * este helper para listar/tar/extraer en rutas privilegiadas.
 *
 * Todo método devuelve null en cuanto algo falla (sin root, su denegado,
 * timeout) — los callers deben tener un fallback sin root.
 */
object RootShell {

    private const val TAG = "RootShell"

    /** true si `su` está disponible y concede permiso a esta app. */
    val available: Boolean by lazy {
        run("id")?.contains("uid=0") == true
    }

    /**
     * Ejecuta `su -c [cmd]` y devuelve su stdout si el exit code es 0.
     * Solo para comandos con salida ACOTADA (listados, stat, md5sum).
     * Para crear archivos grandes usa [runToFile].
     */
    fun run(cmd: String, timeoutSec: Long = 15): String? = try {
        val process = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(false)
            .start()
        val output = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
        }
        outThread.isDaemon = true
        outThread.start()
        // Drenar stderr en background para no bloquear el pipe
        val errThread = Thread {
            try { process.errorStream.read() } catch (_: Exception) {}
        }
        errThread.isDaemon = true
        errThread.start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        outThread.join(2000)
        if (finished && process.exitValue() == 0) output.toString() else null
    } catch (e: Exception) {
        Log.d(TAG, "su failed: ${e.message}")
        null
    }

    /**
     * Ejecuta un comando cuya salida va a un ARCHIVO (p. ej. tar -czf) y
     * devuelve true si terminó con exit 0.
     */
    fun runToFile(cmd: String, timeoutSec: Long = 120): Boolean = try {
        val process = ProcessBuilder("su", "-c", cmd).start()
        val errThread = Thread {
            try { process.errorStream.read() } catch (_: Exception) {}
        }
        errThread.isDaemon = true
        errThread.start()
        process.waitFor(timeoutSec, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Exception) {
        Log.d(TAG, "su runToFile failed: ${e.message}")
        false
    }

    /** Escapa una ruta para incrustarla entre comillas simples en un sh -c. */
    fun sq(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
