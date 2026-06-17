package es.davidrg.rommsync.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for domain models and error handling.
 */
class ApiResultTest {

    @Test
    fun `Success holds data`() {
        val result = ApiResult.Success(listOf("a", "b"))
        assertThat(result.data).hasSize(2)
    }

    @Test
    fun `Error holds kind and message`() {
        val result = ApiResult.Error(ErrorKind.NETWORK, "Sin conexión")
        assertThat(result.kind).isEqualTo(ErrorKind.NETWORK)
        assertThat(result.message).isEqualTo("Sin conexión")
    }

    @Test
    fun `ErrorKind enum has all values`() {
        val kinds = ErrorKind.values()
        assertThat(kinds).asList()
            .containsExactly(ErrorKind.NETWORK, ErrorKind.AUTH, ErrorKind.NOT_FOUND, ErrorKind.SERVER, ErrorKind.UNKNOWN)
    }

    @Test
    fun `Rom domain model stores all metadata`() {
        val rom = Rom(
            id = 1,
            name = "Test Game",
            fileName = "test.rom",
            fileSizeBytes = 1024,
            platformId = 1,
            platformSlug = "nes",
            coverUrlSmall = "http://example.com/small.jpg",
            coverUrlLarge = "http://example.com/large.jpg",
            files = listOf(RomFile("test.rom", 1024)),
            isMulti = false,
            revision = null,
            regions = listOf("USA"),
            languages = listOf("en"),
            genres = listOf("Platform"),
        )

        assertThat(rom.name).isEqualTo("Test Game")
        assertThat(rom.regions).containsExactly("USA")
        assertThat(rom.genres).containsExactly("Platform")
        assertThat(rom.files).hasSize(1)
    }

    @Test
    fun `RomWithStatus combines rom and download status`() {
        val rom = Rom(
            id = 1, name = "X", fileName = "x.rom", fileSizeBytes = 0,
            platformId = 1, platformSlug = "nes",
            coverUrlSmall = null, coverUrlLarge = null,
            files = emptyList(), isMulti = false,
            revision = null, regions = emptyList(),
        )
        val rws = RomWithStatus(rom = rom, status = DownloadStatus.DOWNLOADED)

        assertThat(rws.status).isEqualTo(DownloadStatus.DOWNLOADED)
        assertThat(rws.rom.id).isEqualTo(1)
    }
}
