package es.davidrg.rommsync.data.metadata

import android.util.Xml
import es.davidrg.rommsync.domain.model.Rom
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.StringWriter

/**
 * Lee y escribe archivos gamelist.xml de ES-DE haciendo *merge* in-place
 * (nunca sobrescribe entradas existentes que el usuario haya editado
 * manualmente; solo actualiza/añade campos que vienen de RomM).
 *
 * Estructura de ES-DE:
 * ```
 * ~/ES-DE/gamelists/<system>/gamelist.xml
 * ```
 *
 * Y la media va en una ubicación separada:
 * ```
 * <roms_root>/downloaded_media/<system>/{covers,screenshots,videos,manuals}/
 * ```
 *
 * Las rutas dentro del XML son relativas a %ROMPATH% (ej: `./snes/game.smc`).
 */
object GamelistWriter {

    /**
     * Representa una entrada `<game>` del gamelist.xml.
     * Solo los campos que sabemos escribir desde RomM.
     */
    data class GameEntry(
        val path: String,           // <path> — relativo, ej: "./snes/game.smc"
        val name: String? = null,   // <name>
        val desc: String? = null,   // <desc>
        val image: String? = null,  // <image> — ruta a cover
        val screenshot: String? = null,
        val video: String? = null,
        val rating: String? = null, // 0-1 (float)
        val releasedate: String? = null, // YYYYMMDDTHHMMSS
        val developer: String? = null,
        val publisher: String? = null,
        val genre: String? = null,
        val players: String? = null,    // ej: "1-2"
        val manual: String? = null,
    )

    /**
     * Lee el gamelist.xml existente (si existe) y devuelve un mapa
     * path → GameEntry con todas las entradas encontradas.
     */
    fun readGamelist(file: File): Map<String, GameEntry> {
        if (!file.exists()) return emptyMap()

        val result = mutableMapOf<String, GameEntry>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(file.inputStream(), "UTF-8")

            var currentEntry: GameEntry? = null
            var currentTag = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (parser.name == "game") {
                            currentEntry = GameEntry(path = "")
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty() && currentEntry != null) {
                            currentEntry = when (currentTag) {
                                "path" -> currentEntry.copy(path = text)
                                "name" -> currentEntry.copy(name = text)
                                "desc" -> currentEntry.copy(desc = text)
                                "image" -> currentEntry.copy(image = text)
                                "screenshot" -> currentEntry.copy(screenshot = text)
                                "video" -> currentEntry.copy(video = text)
                                "rating" -> currentEntry.copy(rating = text)
                                "releasedate" -> currentEntry.copy(releasedate = text)
                                "developer" -> currentEntry.copy(developer = text)
                                "publisher" -> currentEntry.copy(publisher = text)
                                "genre" -> currentEntry.copy(genre = text)
                                "players" -> currentEntry.copy(players = text)
                                "manual" -> currentEntry.copy(manual = text)
                                else -> currentEntry
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "game" && currentEntry != null && currentEntry.path.isNotEmpty()) {
                            result[currentEntry.path] = currentEntry
                        }
                        currentEntry = null
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Si el XML está corrupto, empezamos de cero
        }

        return result
    }

    /**
     * Hace merge de las entradas existentes con las nuevas que vienen de RomM.
     * Para campos que ya existen (name, desc), solo se actualizan si el valor
     * de RomM no es nulo. Para media (image, video, etc.), siempre se actualiza
     * con la nueva ruta local.
     *
     * Devuelve el XML completo listo para escribir.
     */
    fun mergeAndSerialize(
        existing: Map<String, GameEntry>,
        updates: List<GameEntry>,
    ): String {
        val merged = existing.toMutableMap()

        for (update in updates) {
            val key = update.path
            val prev = merged[key]
            merged[key] = if (prev != null) {
                prev.copy(
                    name = update.name ?: prev.name,
                    desc = update.desc ?: prev.desc,
                    image = update.image ?: prev.image,
                    screenshot = update.screenshot ?: prev.screenshot,
                    video = update.video ?: prev.video,
                    rating = update.rating ?: prev.rating,
                    releasedate = update.releasedate ?: prev.releasedate,
                    developer = update.developer ?: prev.developer,
                    publisher = update.publisher ?: prev.publisher,
                    genre = update.genre ?: prev.genre,
                    players = update.players ?: prev.players,
                    manual = update.manual ?: prev.manual,
                )
            } else {
                update
            }
        }

        return serializeGamelist(merged.values.toList())
    }

    /**
     * Serializa una lista de GameEntry a XML gamelist válido para ES-DE.
     */
    private fun serializeGamelist(entries: List<GameEntry>): String {
        val writer = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

        serializer.startTag("", "gameList")

        for (entry in entries) {
            serializer.startTag("", "game")

            serializer.startTag("", "path").text(entry.path).endTag("", "path")

            entry.name?.let {
                serializer.startTag("", "name").text(it).endTag("", "name")
            }
            entry.desc?.let {
                serializer.startTag("", "desc").text(it).endTag("", "desc")
            }
            entry.image?.let {
                serializer.startTag("", "image").text(it).endTag("", "image")
            }
            entry.screenshot?.let {
                serializer.startTag("", "screenshot").text(it).endTag("", "screenshot")
            }
            entry.video?.let {
                serializer.startTag("", "video").text(it).endTag("", "video")
            }
            entry.rating?.let {
                serializer.startTag("", "rating").text(it).endTag("", "rating")
            }
            entry.releasedate?.let {
                serializer.startTag("", "releasedate").text(it).endTag("", "releasedate")
            }
            entry.developer?.let {
                serializer.startTag("", "developer").text(it).endTag("", "developer")
            }
            entry.publisher?.let {
                serializer.startTag("", "publisher").text(it).endTag("", "publisher")
            }
            entry.genre?.let {
                serializer.startTag("", "genre").text(it).endTag("", "genre")
            }
            entry.players?.let {
                serializer.startTag("", "players").text(it).endTag("", "players")
            }
            entry.manual?.let {
                serializer.startTag("", "manual").text(it).endTag("", "manual")
            }

            serializer.endTag("", "game")
        }

        serializer.endTag("", "gameList")
        serializer.endDocument()
        return writer.toString()
    }

    /**
     * Convierte un [Rom] a un [GameEntry] con las rutas relativas correctas
     * para ES-DE. Las rutas de media usan el formato `%ROMPATH%/downloaded_media/...`
     * que ES-DE resuelve automáticamente.
     */
    fun romToEntry(
        rom: Rom,
        romsRootPath: String,
        platformSlug: String,
        mediaBaseName: String,
    ): GameEntry {
        // path del ROM: relativo a %ROMPATH%
        val romPath = "./$platformSlug/${rom.fileName}"

        // Rutas de media: relativas a %ROMPATH% (ES-DE las resuelve)
        val mediaBase = "./downloaded_media/$platformSlug"

        return GameEntry(
            path = romPath,
            name = rom.name,
            desc = rom.summary,
            image = rom.coverUrlLarge?.let { "$mediaBase/covers/${mediaBaseName}.jpg" },
            screenshot = if (rom.screenshots.isNotEmpty())
                "$mediaBase/screenshots/${mediaBaseName}.jpg" else null,
            video = rom.videoPath?.let { "$mediaBase/videos/${mediaBaseName}.mp4" },
            rating = rom.igdbMetadata?.totalRating?.let { String.format("%.2f", it / 100.0) },
            releasedate = rom.igdbMetadata?.firstReleaseDate?.let { ts ->
                // IGDB devuelve timestamp en segundos
                val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
                sdf.format(java.util.Date(ts * 1000))
            },
            developer = rom.igdbMetadata?.companies?.joinToString(", ")?.ifBlank { null },
            publisher = rom.igdbMetadata?.companies?.joinToString(", ")?.ifBlank { null },
            genre = (rom.genres + (rom.igdbMetadata?.genres ?: emptyList()))
                .distinct().joinToString(", ").ifBlank { null },
            players = rom.igdbMetadata?.playerCount,
            manual = rom.manualPath?.let { "$mediaBase/manuals/${mediaBaseName}.pdf" },
        )
    }
}
