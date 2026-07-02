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
    private val n3dsHandler = N3dsSaveHandler()
    private val cemuHandler = CemuSaveHandler()
    private val androidHandler = AndroidSaveHandler()

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
        AZAHAR("azahar", "Azahar"),
        CEMU("cemu", "Cemu"),
        ANDROID_NATIVE("android_native", "Nativo Android"),
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
            in N3DS_SLUGS -> listOf(EmulatorId.AZAHAR, EmulatorId.RETROARCH)
            in WIIU_SLUGS -> listOf(EmulatorId.CEMU)
            "switch" -> listOf(EmulatorId.EDEN)
            in ANDROID_SLUGS -> listOf(EmulatorId.ANDROID_NATIVE)
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
            in N3DS_SLUGS -> EmulatorId.AZAHAR
            in WIIU_SLUGS -> EmulatorId.CEMU
            "switch" -> EmulatorId.EDEN
            in ANDROID_SLUGS -> EmulatorId.ANDROID_NATIVE
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
            EmulatorId.AZAHAR.id -> n3dsHandler
            EmulatorId.CEMU.id -> cemuHandler
            EmulatorId.ANDROID_NATIVE.id -> androidHandler
            EmulatorId.RETROARCH.id -> retroArchHandler
            else -> retroArchHandler
        }
    }

    /**
     * Devuelve la ruta de saves por defecto para un emulador y plataforma.
     *
     * La plataforma es necesaria porque algunos emuladores (Dolphin) usan
     * rutas distintas segun la plataforma: GameCube guarda en `.../files/GC`
     * mientras que Wii guarda en `.../files/Wii/title`.
     */
    fun getDefaultSavesPath(emulatorId: String, platformSlug: String, retroArchBase: String): String {
        return when (emulatorId) {
            EmulatorId.MELONDS.id -> MelonDsSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.PPSSPP.id -> PpssppSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.AETHERSX2.id -> Ps2SaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.DOLPHIN.id -> if (platformSlug.lowercase() in DolphinSaveHandler.GC_SLUGS) {
                DolphinSaveHandler.DEFAULT_GC_SAVES_PATH
            } else {
                DolphinSaveHandler.DEFAULT_WII_SAVES_PATH
            }
            EmulatorId.EDEN.id -> SwitchSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.AZAHAR.id -> N3dsSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.CEMU.id -> CemuSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.ANDROID_NATIVE.id -> AndroidSaveHandler.DEFAULT_SAVES_PATH
            else -> retroArchBase
        }
    }

    /** Slugs de plataforma (ES-DE / RomM) que corresponden a Nintendo 3DS. */
    private val N3DS_SLUGS = setOf("3ds", "n3ds", "nintendo-3ds", "nintendo_3ds")

    /** Slugs de plataforma (ES-DE / RomM) que corresponden a Wii U. */
    private val WIIU_SLUGS = setOf("wiiu", "wii-u", "wii_u", "nintendo-wii-u")

    /** Slugs de plataforma que corresponden a juegos nativos Android. */
    private val ANDROID_SLUGS = setOf("android", "android-games")
}
