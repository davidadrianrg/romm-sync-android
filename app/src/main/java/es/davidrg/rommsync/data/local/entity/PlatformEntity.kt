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
)
