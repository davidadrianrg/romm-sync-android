package es.davidrg.rommsync.domain.model

data class Platform(
    val id: Int,
    val slug: String,
    val name: String,
    val romCount: Int,
    val visible: Boolean = true,
    val emulatorId: String? = null,
    val savesPathOverride: String? = null,
    val aspectRatio: String? = null,
)
