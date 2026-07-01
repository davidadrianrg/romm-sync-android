package es.davidrg.rommsync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val slug: String,
    val name: String,
    val romCount: Int,
    val visible: Boolean = true,
    /** ID del emulador asignado para sync (retroarch, melonds, ppsspp, aethersx2, dolphin...). Null = default. */
    val emulatorId: String? = null,
    /** Override de ruta de saves para esta plataforma. Null = usar ruta por defecto del emulador. */
    val savesPathOverride: String? = null,
    /** Aspect ratio de los covers de esta plataforma (ej: "2 / 3", "3 / 4", "1 / 1"). */
    val aspectRatio: String? = null,
)
