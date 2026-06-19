package es.davidrg.rommsync.data.sync.platform

/**
 * Registry que selecciona el [SaveHandler] correcto según la plataforma
 * y el emulador configurado.
 *
 * Orden de resolución:
 * 1. Si la plataforma tiene un emulador específico configurado, se usa su handler.
 * 2. Si no, se aplica un mapeo por defecto según el slug de la plataforma.
 * 3. Fallback: RetroArchSaveHandler.
 */
object SaveHandlerRegistry {

    private val retroArchHandler = RetroArchSaveHandler()
    private val melonDsHandler = MelonDsSaveHandler()
    private val ppssppHandler = PpssppSaveHandler()
    private val ps2Handler = Ps2SaveHandler()
    private val dolphinHandler = DolphinSaveHandler()
    private val switchHandler = SwitchSaveHandler()

    /**
     * Emuladores soportados con sus IDs para configuración por plataforma.
     */
    enum class EmulatorId(val id: String, val displayName: String) {
        RETROARCH("retroarch", "RetroArch"),
        MELONDS("melonds", "melonDS"),
        PPSSPP("ppsspp", "PPSSPP"),
        AETHERSX2("aethersx2", "AetherSX2/NetherSX2"),
        DOLPHIN("dolphin", "Dolphin"),
        EDEN("eden", "Eden"),
    }

    /**
     * Devuelve los emuladores disponibles para un slug de plataforma dado.
     * Se usa para poblar el selector en la UI de configuración por plataforma.
     */
    fun getAvailableEmulators(platformSlug: String): List<EmulatorId> {
        return when (platformSlug.lowercase()) {
            "nds", "ds" -> listOf(EmulatorId.MELONDS, EmulatorId.RETROARCH)
            "psp" -> listOf(EmulatorId.PPSSPP, EmulatorId.RETROARCH)
            "ps2" -> listOf(EmulatorId.AETHERSX2, EmulatorId.RETROARCH)
            "gc", "gamecube", "ngc" -> listOf(EmulatorId.DOLPHIN, EmulatorId.RETROARCH)
            "wii" -> listOf(EmulatorId.DOLPHIN, EmulatorId.RETROARCH)
            "switch" -> listOf(EmulatorId.EDEN)
            else -> listOf(EmulatorId.RETROARCH)
        }
    }

    /**
     * Devuelve el emulador por defecto para un slug de plataforma.
     */
    fun getDefaultEmulator(platformSlug: String): EmulatorId {
        return when (platformSlug.lowercase()) {
            "nds", "ds" -> EmulatorId.MELONDS
            "psp" -> EmulatorId.PPSSPP
            "ps2" -> EmulatorId.AETHERSX2
            "gc", "gamecube", "ngc", "wii" -> EmulatorId.DOLPHIN
            "switch" -> EmulatorId.EDEN
            else -> EmulatorId.RETROARCH
        }
    }

    /**
     * Resuelve el handler correcto para una plataforma con un emulador
     * (opcionalmente) configurado por el usuario.
     */
    fun getHandler(platformSlug: String, emulatorId: String?): SaveHandler {
        val effective = emulatorId ?: getDefaultEmulator(platformSlug).id
        return when (effective) {
            EmulatorId.MELONDS.id -> melonDsHandler
            EmulatorId.PPSSPP.id -> ppssppHandler
            EmulatorId.AETHERSX2.id -> ps2Handler
            EmulatorId.DOLPHIN.id -> dolphinHandler
            EmulatorId.EDEN.id -> switchHandler
            EmulatorId.RETROARCH.id -> retroArchHandler
            else -> retroArchHandler
        }
    }

    /**
     * Devuelve la ruta de saves por defecto para un emulador dado.
     */
    fun getDefaultSavesPath(emulatorId: String, retroArchBase: String): String {
        return when (emulatorId) {
            EmulatorId.MELONDS.id -> MelonDsSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.PPSSPP.id -> PpssppSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.AETHERSX2.id -> Ps2SaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.DOLPHIN.id -> DolphinSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.EDEN.id -> SwitchSaveHandler.DEFAULT_SAVES_PATH
            else -> retroArchBase
        }
    }
}
