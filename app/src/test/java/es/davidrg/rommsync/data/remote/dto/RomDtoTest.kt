package es.davidrg.rommsync.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RomDto mapping and structure.
 * Validates that the DTO fields match what the RomM API returns.
 */
class RomDtoTest {

    @Test
    fun `RomDto has all essential fields with defaults`() {
        val dto = RomDto(
            id = 1,
            name = "Super Mario World",
            fileName = "Super Mario World (USA).sfc",
            platformId = 4,
        )

        assertEquals(1, dto.id)
        assertEquals("Super Mario World", dto.name)
        assertEquals("Super Mario World (USA).sfc", dto.fileName)
        assertEquals(4, dto.platformId)
        assertTrue(dto.genres.isEmpty())
        assertTrue(dto.regions.isEmpty())
    }

    @Test
    fun `RomDto with full metadata`() {
        val dto = RomDto(
            id = 1,
            name = "Chrono Trigger",
            fileName = "Chrono Trigger (USA).sfc",
            platformId = 4,
            genres = listOf("RPG", "Adventure"),
            regions = listOf("USA"),
            languages = listOf("en"),
            summary = "A time-traveling RPG classic.",
            revision = "Rev 1",
            fsSizeBytes = 2097152,
        )

        assertEquals(2, dto.genres.size)
        assertEquals("RPG", dto.genres[0])
        assertEquals("USA", dto.regions[0])
        assertEquals(2097152, dto.fileSizeBytes)
    }

    @Test
    fun `RomResponse pagination fields`() {
        val response = RomResponse(
            items = emptyList(),
            total = 500,
            page = 1,
            size = 50,
            pages = 10,
        )

        assertEquals(500, response.total)
        assertEquals(10, response.pages)
        assertTrue(response.items.isEmpty())
    }
}
