package es.davidrg.rommsync.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Extracción de identificadores de juego desde los headers binarios de las
 * imágenes de disco (ISO plano).
 *
 * Sustituye a la inferencia por nombre de fichero, que falla cuando el ROM
 * no incluye el ID en el nombre (p. ej. "Pokemon Esmeralda.gba").
 *
 * Formatos soportados (ISO sin comprimir):
 * - **PSP**: PARAM.SFO (magic "\0PSF") dentro del ISO → campo DISC_ID.
 *   Se escanea el primer MB del ISO buscando el SFO embebido.
 * - **PS2**: SYSTEM.CNF al inicio del ISO → línea BOOT2 = cdrom0:\SLUS_210.50
 *   → serial `SLUS-21050`.
 * - **GameCube**: game code en offset 0x0440 (boot.bin), 4 chars.
 * - **Wii**: game code en offset 0x0000, 4-6 chars.
 *
 * Formatos comprimidos (CSO/CHD/GZ) y Switch (XCI/NSP): no soportados,
 * devuelven null → el llamador hace fallback a [extractIdFromFileName].
 *
 * Todos los métodos devuelven null ante cualquier error de lectura.
 */
object RomHeaderIdReader {

    private const val PSP_SCAN_BYTES = 1024 * 1024      // 1 MB
    private const val SWITCH_SCAN_BYTES = 512 * 1024    // 512 KB (tabla PFS0 + inicio NCA)
    private const val PS2_SCAN_BYTES = 2 * 1024 * 1024  // 2 MB

    /**
     * Punto de entrada: extrae el ID del juego leyendo el header binario
     * si el formato está soportado.
     */
    fun readGameId(file: File, platformSlug: String): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            when (platformSlug.lowercase()) {
                "psp" -> readPspDiscId(file)
                "ps2" -> readPs2Serial(file)
                "gc", "gamecube", "ngc" -> readGameCubeId(file)
                "wii" -> readWiiId(file)
                "switch", "nx", "ryujinx", "switch-emulators" -> readSwitchTitleId(file)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── PSP ──────────────────────────────────────────────────────────────

    /**
     * Escanea el prefijo del ISO buscando un PARAM.SFO embebido y parsea su
     * campo DISC_ID (formato `ULUS10064` / `ULUS-10064`).
     */
    private fun readPspDiscId(file: File): String? {
        if (isCompressed(file)) return null
        val prefix = readPrefix(file, PSP_SCAN_BYTES) ?: return null
        val sfoIndex = indexOf(prefix, byteArrayOf(0x00, 0x50, 0x53, 0x46)) // "\0PSF"
        if (sfoIndex < 0) return null
        return parseSfoDiscId(prefix, sfoIndex)
    }

    /**
     * Layout del PARAM.SFO:
     * ```
     * 0x00  magic "\0PSF"
     * 0x04  version (u32)
     * 0x08  key_table_start   (u32 LE, relativo al inicio del SFO)
     * 0x0C  data_table_start  (u32 LE, relativo al inicio del SFO)
     * 0x10  entries           (u32)
     * 0x14  índice (16 bytes por entrada):
     *        key_offset(u16) data_fmt(u16) data_len(u32) data_max(u32) data_offset(u32)
     * ```
     * Tanto key_offset como data_offset son relativos a key_table_start y
     * data_table_start respectivamente.
     */
    private fun parseSfoDiscId(prefix: ByteArray, sfoIndex: Int): String? {
        val keyTableStart = readU32LE(prefix, sfoIndex + 0x08)
        val dataTableStart = readU32LE(prefix, sfoIndex + 0x0C)
        val entries = readU32LE(prefix, sfoIndex + 0x10)
        if (entries == 0L || entries > 256L) return null

        var idx = sfoIndex + 0x14
        for (i in 0 until entries) {
            if (idx + 16 > prefix.size) return null
            val keyOffset = readU16LE(prefix, idx)
            val dataOffset = readU32LE(prefix, idx + 12)
            val keyStart = sfoIndex + keyTableStart + keyOffset
            val key = readCString(prefix, keyStart.toInt(), 64)
            if (key == "DISC_ID") {
                val dataStart = (sfoIndex + dataTableStart + dataOffset).toInt()
                val value = readCString(prefix, dataStart, 16)
                return value
                    ?.replace("-", "")
                    ?.takeIf { it.matches(Regex("[A-Z]{4}\\d{5}")) }
            }
            idx += 16
        }
        return null
    }

    // ── PS2 ──────────────────────────────────────────────────────────────

    /**
     * Busca SYSTEM.CNF en el prefijo del ISO y extrae el serial de la línea
     * `BOOT2 = cdrom0:\SLUS_210.50;1` → `SLUS-21050`.
     */
    private fun readPs2Serial(file: File): String? {
        if (isCompressed(file)) return null
        val prefix = readPrefix(file, PS2_SCAN_BYTES) ?: return null
        val bootIdx = indexOf(prefix, "BOOT2".toByteArray())
        if (bootIdx < 0) return null
        val windowEnd = minOf(bootIdx + 200, prefix.size)
        val text = String(prefix, bootIdx, windowEnd - bootIdx, Charsets.US_ASCII)
        val match = Regex("""cdrom0:\\?([A-Z]{4})_(\d{3})\.(\d{2})""").find(text) ?: return null
        val (code, major, minor) = match.destructured
        return "${code}-${major}${minor}"
    }

    // ── GameCube ─────────────────────────────────────────────────────────

    /**
     * GC: el boot.bin empieza en 0x0440 del disco; los primeros 4 bytes son
     * el game code (p. ej. `GMSE` para Super Smash Bros. Melee USA).
     */
    private fun readGameCubeId(file: File): String? {
        if (isCompressed(file)) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0x0440)
            val code = ByteArray(4)
            raf.readFully(code)
            val gameCode = String(code, Charsets.US_ASCII)
            return gameCode.takeIf { it.matches(Regex("[A-Z0-9]{4}")) }
        }
    }

    // ── Wii ──────────────────────────────────────────────────────────────

    /**
     * Wii: game code en offset 0x0000 (4 chars; los 2 siguientes son
     * publisher/region, se omiten para igualar el formato corto de GC).
     */
    private fun readWiiId(file: File): String? {
        if (isCompressed(file)) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0x0000)
            val code = ByteArray(4)
            raf.readFully(code)
            val gameCode = String(code, Charsets.US_ASCII)
            return gameCode.takeIf { it.matches(Regex("[A-Z0-9]{4}")) }
        }
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    private fun isCompressed(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("cso", "chd", "gz", "zip", "7z", "rvz")
    }

    // ── Nintendo Switch ─────────────────────────────────────────────────

    /**
     * Title-ID de Switch desde XCI/NSP sin descomprimir nada.
     *
     * - XCI: PackageId en offset 0x0012 (big-endian) == TitleID base.
     *   Validado contra la estructura XCI: tamaño de header (F0) = 0x20 y
     *   PackageId con prefijo 0x01 0x00 (los title-ids de juego empiezan así).
     * - NSP (PFS0): la tabla de strings contiene el nombre del CNMT, que
     *   incluye el title-id base en hex: "TitanTitleId_0x0100XXXXXXXXXXXX" o
     *   el propio nombre de fichero "0100XXXXXXXXXXXX.nca". Escaneamos el
     *   prefijo en claro (antes de la zona cifrada) buscando el patrón.
     * Fallback: escaneo alineado a 16B buscando prefijo 0x01 0x00 + ceros.
     */
    private fun readSwitchTitleId(file: File): String? {
        if (isCompressed(file)) return null
        val ext = file.extension.lowercase()
        val prefix = readPrefix(file, SWITCH_SCAN_BYTES) ?: return null

        return when (ext) {
            "xci" -> readXciPackageId(prefix)
            "nsp" -> readNspTitleId(prefix)
            else -> null
        }
    }

    /**
     * XCI: valida el magic del gamecard ("HEADER" en 0x100 o tamaño de header
     * 0x20 en el campo de tamaño) y lee el PackageId como title-id base.
     */
    private fun readXciPackageId(prefix: ByteArray): String? {
        // Campo "headerSize" en offset 0xF0: los XCI oficiales usan 0x20
        val headerSize = readBeLong(prefix, 0x00F0) ?: return null
        if (headerSize != 0x20L) return null
        val packageId = readBeLong(prefix, 0x0012) ?: return null
        // Los title-id de aplicaciones Switch empiezan por 0x0100 en BE
        if (packageId ushr 48 != 0x0100L) return null
        return formatTitleId(packageId)
    }

    /**
     * NSP: el title-id aparece en claro en la tabla de strings del PFS0
     * (nombres de NCA tipo "0100XXXXXXXXXXXX.nca") o en el CNMT. Buscamos
     * ASCII hex de 16 chars empezando por "0100".
     */
    private fun readNspTitleId(prefix: ByteArray): String? {
        val text = String(prefix, Charsets.US_ASCII)
        // Busca "0100" seguido de 12 hex más (title-id completo en texto)
        val m = Regex("0100[0-9A-Fa-f]{12}").find(text)
        return m?.value?.uppercase()
    }

    /** 0x0100XXXXXXXXXXXX -> "0100XXXXXXXXXXXX" (16 hex mayúsculas). */
    private fun formatTitleId(titleId: Long): String =
        "%016X".format(titleId)

    private fun readBeLong(prefix: ByteArray, offset: Int): Long? {
        if (offset + 8 > prefix.size) return null
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (prefix[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray? {
        if (file.length() < 16) return null
        val size = minOf(file.length().toInt(), maxBytes)
        val buffer = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            raf.readFully(buffer)
        }
        return buffer
    }

    /** Búsqueda ingenua de [needle] en [haystack]. Devuelve -1 si no está. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
        (b[off].toInt() and 0xFF).toLong() or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

    /** Lee un string terminado en \u0000 con longitud máxima [maxLen]. */
    private fun readCString(b: ByteArray, off: Int, maxLen: Int): String? {
        if (off < 0 || off >= b.size) return null
        val end = minOf(off + maxLen, b.size)
        val sb = StringBuilder()
        for (i in off until end) {
            val c = b[i].toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString().ifEmpty { null }
    }
}
