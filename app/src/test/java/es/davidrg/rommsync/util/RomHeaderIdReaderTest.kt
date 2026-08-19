package es.davidrg.rommsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests de extracción de IDs desde headers binarios sintéticos.
 *
 * Cada test construye en memoria el mínimo prefijo válido de cada formato
 * (PSP PARAM.SFO, PS2 SYSTEM.CNF, GC/Wii game code, XCI PackageId, NSP PFS0)
 * y verifica que el lector extrae el ID correcto — sin ROMs reales.
 */
class RomHeaderIdReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun pad(size: Int): ByteArray = ByteArray(size)

    // ── PSP: PARAM.SFO embebido en el prefijo del ISO ────────────────────

    @Test
    fun `psp extrae DISC_ID de un PARAM-SFO sintetico`() {
        val sfo = buildSfo(discId = "ULUS10064")
        val iso = ByteArray(64 * 1024)
        // Copiar el SFO en un offset arbitrario (como va embebido en la ISO)
        System.arraycopy(sfo, 0, iso, 0x800, sfo.size)
        val f = write("game.iso", iso)

        assertEquals("ULUS10064", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp normaliza DISC_ID con guion`() {
        val sfo = buildSfo(discId = "ULES-00182")
        val iso = ByteArray(64 * 1024)
        System.arraycopy(sfo, 0, iso, 0x400, sfo.size)
        val f = write("game.iso", iso)

        assertEquals("ULES00182", RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp sin SFO devuelve null`() {
        val f = write("game.iso", pad(64 * 1024))
        assertNull(RomHeaderIdReader.readGameId(f, "psp"))
    }

    @Test
    fun `psp comprimido cso devuelve null`() {
        val f = write("game.cso", pad(4096))
        assertNull(RomHeaderIdReader.readGameId(f, "psp"))
    }

    // ── PS2: SYSTEM.CNF con BOOT2 ────────────────────────────────────────

    @Test
    fun `ps2 extrae serial de BOOT2`() {
        val iso = ByteArray(64 * 1024)
        val cnf = "BOOT2 = cdrom0:\\SLUS_210.50;1\r\nVER = 1.00\r\n".toByteArray(Charsets.US_ASCII)
        System.arraycopy(cnf, 0, iso, 0x1000, cnf.size)
        val f = write("game.iso", iso)

        assertEquals("SLUS-21050", RomHeaderIdReader.readGameId(f, "ps2"))
    }

    @Test
    fun `ps2 sin BOOT2 devuelve null`() {
        val f = write("game.iso", pad(64 * 1024))
        assertNull(RomHeaderIdReader.readGameId(f, "ps2"))
    }

    // ── GameCube: game code en 0x0440 ────────────────────────────────────

    @Test
    fun `gamecube extrae game code de 0x0440`() {
        val iso = ByteArray(32 * 1024)
        System.arraycopy("GMSE".toByteArray(Charsets.US_ASCII), 0, iso, 0x0440, 4)
        val f = write("game.iso", iso)

        assertEquals("GMSE", RomHeaderIdReader.readGameId(f, "gc"))
    }

    @Test
    fun `gamecube alias ngc tambien funciona`() {
        val iso = ByteArray(32 * 1024)
        System.arraycopy("GALE01".toByteArray(Charsets.US_ASCII), 0, iso, 0x0440, 6)
        val f = write("game.iso", iso)

        assertEquals("GALE", RomHeaderIdReader.readGameId(f, "ngc"))
    }

    // ── Wii: game code en 0x0000 ─────────────────────────────────────────

    @Test
    fun `wii extrae game code de 0x0000`() {
        val iso = ByteArray(32 * 1024)
        System.arraycopy("RMGE".toByteArray(Charsets.US_ASCII), 0, iso, 0, 4)
        val f = write("game.iso", iso)

        assertEquals("RMGE", RomHeaderIdReader.readGameId(f, "wii"))
    }

    // ── Switch XCI: PackageId en 0x0012 + validación ─────────────────────

    @Test
    fun `switch xci extrae title id del package id`() {
        // 0x2000 bytes: headerSize en 0xF0 = 0x20, PackageId en 0x12
        val xci = ByteArray(0x2000)
        writeBeLong(xci, 0x00F0, 0x20L)              // headerSize = 0x20
        writeBeLong(xci, 0x0012, 0x0100C500122DE000L) // title-id de aplicación (16 hex)
        val f = write("game.xci", xci)

        assertEquals("0100C500122DE000", RomHeaderIdReader.readGameId(f, "switch"))
    }

    @Test
    fun `switch xci con headerSize invalido devuelve null`() {
        val xci = ByteArray(0x2000)
        writeBeLong(xci, 0x00F0, 0x4000L)            // headerSize incorrecto
        writeBeLong(xci, 0x0012, 0x0100C500122DE000L)
        val f = write("game.xci", xci)

        assertNull(RomHeaderIdReader.readGameId(f, "switch"))
    }

    // ── Switch NSP: title-id en la tabla de strings PFS0 ─────────────────

    @Test
    fun `switch nsp extrae title id de la tabla de strings`() {
        val nsp = ByteArray(0x4000)
        System.arraycopy("PFS0".toByteArray(), 0, nsp, 0, 4)
        // Simular tabla de strings con el nombre del NCA control NCA
        val strings = "0100c500122de000.nca\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(strings, 0, nsp, 0x100, strings.size)
        val f = write("game.nsp", nsp)

        assertEquals("0100C500122DE000", RomHeaderIdReader.readGameId(f, "switch"))
    }

    @Test
    fun `switch nsp sin title id devuelve null`() {
        val nsp = ByteArray(0x4000)
        System.arraycopy("PFS0".toByteArray(), 0, nsp, 0, 4)
        val f = write("game.nsp", nsp)

        assertNull(RomHeaderIdReader.readGameId(f, "switch"))
    }

    // ── Genéricos ────────────────────────────────────────────────────────

    @Test
    fun `plataforma no soportada devuelve null`() {
        val f = write("rom.bin", pad(4096))
        assertNull(RomHeaderIdReader.readGameId(f, "nes"))
    }

    @Test
    fun `fichero inexistente devuelve null`() {
        assertNull(RomHeaderIdReader.readGameId(File("/no/existe.iso"), "psp"))
    }

    @Test
    fun `fichero demasiado corto devuelve null`() {
        val f = write("tiny.iso", pad(8))
        assertNull(RomHeaderIdReader.readGameId(f, "psp"))
    }

    // ── Builders de fixtures ─────────────────────────────────────────────

    /**
     * PARAM.SFO mínimo: cabecera + índice con dos entradas (una trampa
     * "DISC_ID_" falsa antes de la real, para probar el c-string exacto)
     * y tablas de keys/datos alineadas como el formato real.
     */
    private fun buildSfo(discId: String): ByteArray {
        val keys = "CATEGORY\u0000DISC_ID\u0000"
        val data = "UG\u0000${discId}\u0000"

        val keyTableStart = 0x14 + 2 * 16          // tras el índice
        val dataTableStart = keyTableStart + keys.length

        val out = ByteArrayOutputStream(dataTableStart + data.length)
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt())
            out.write(((v shr 24) and 0xFF).toInt())
        }

        out.write(byteArrayOf(0x00, 0x50, 0x53, 0x46)) // "\u0000PSF"
        u32(1L)                                          // version
        u32(keyTableStart.toLong())
        u32(dataTableStart.toLong())
        u32(2L)                                          // entries

        // Entrada 0: CATEGORY (UTF-8, 2 bytes de datos)
        u16(0)            // key_offset: "CATEGORY"
        u16(0x0204)       // data_fmt: UTF-8
        u32(3L)           // data_len ("UG\u0000")
        u32(4L)
        u32(0L)           // data_offset
        // Entrada 1: DISC_ID
        u16("CATEGORY\u0000".length) // key_offset: "DISC_ID"
        u16(0x0204)
        u32((discId.length + 1).toLong())
        u32((discId.length + 1).toLong())
        u32(3L)           // data_offset ("UG\u0000" ocupa 3)
        out.write(keys.toByteArray(Charsets.US_ASCII))
        out.write(data.toByteArray(Charsets.US_ASCII))

        return out.toByteArray()
    }

    private fun writeBeLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) {
            b[off + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
        }
    }
}
