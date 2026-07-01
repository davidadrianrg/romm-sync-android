package es.davidrg.rommsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_roms", indices = [Index("platformId")])
data class DownloadedRomEntity(
    @PrimaryKey val romId: Int,
    val name: String,
    val fileName: String,
    val platformId: Int,
    val platformSlug: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val fileHash: String? = null,
    val downloadedAt: Long = System.currentTimeMillis(),
    /** Override de ruta base de saves solo para este juego. Null = usar la de la plataforma. */
    val savesPathOverride: String? = null,
    /** Si es true, este juego se excluye de la sincronización de partidas. */
    val excludedFromSync: Boolean = false,
)
