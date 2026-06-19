# Diseño: Sincronización de Partidas Guardadas

Documento de diseño para la funcionalidad de sincronización bidireccional
de saves y states con el servidor RomM.

## Protocolo del servidor (Device Sync Protocol, RomM 4.9+)

Referencia: https://docs.romm.app/latest/developers/device-sync-protocol/

### Flujo

1. **Registro del dispositivo** — `POST /api/devices`
   - Se envía una vez, se cachea el `device_id` localmente.
   - Incluye nombre, plataforma (`android`), hostname, paths locales.

2. **Negociación** — `POST /api/sync/negotiate`
   - El cliente envía la lista de ROMs que tiene descargados con sus saves:
     `{ rom_id, saves: [{ file, mtime, sha1 }] }`
   - El servidor responde con operaciones: `upload`, `download`, `conflict`, `noop`.

3. **Ejecución de operaciones**
   - `upload`: `POST /api/saves` (multipart)
   - `download`: `GET /api/saves/{id}/content`
   - `conflict`: requiere resolución del usuario (o política por defecto).

4. **Cierre de sesión** — `POST /api/sync/sessions/{session_id}/complete`

### Autenticación

Bearer token (`rmm_...`), el mismo que ya usamos. Scopes requeridos:
`assets.read`, `assets.write`, `devices.read`, `devices.write`.

---

## Arquitectura en la app

### Componentes nuevos

```
data/sync/
├── SaveSyncWorker.kt              # CoroutineWorker (WorkManager)
├── SyncCoordinator.kt             # Orquesta negotiate → execute → complete
├── SyncPayloads.kt                # DTOs: NegotiateRequest/Response, Operation
├── DeviceRegistration.kt          # Registro y cache del device_id
├── ConflictResolver.kt            # Lógica y UI de resolución de conflictos
├── SaveScanner.kt                 # Escanea saves locales por plataforma
└── platform/                      # Handlers por plataforma/emulador
    ├── SaveHandler.kt             # Interfaz base
    ├── RetroArchSaveHandler.kt    # Ficheros planos .srm/.state por slug
    ├── MelonDsSaveHandler.kt      # DS: .sav plano por nombre ROM
    ├── PspSaveHandler.kt          # PSP: carpetas por disc-id
    ├── Ps2SaveHandler.kt          # PS2: folder memory card por serial
    ├── GciSaveHandler.kt          # GameCube: GCI bundles
    ├── SwitchSaveHandler.kt       # Switch: carpetas por title-id
    └── SaveHandlerRegistry.kt     # Selecciona handler por (plataforma, emulador)
```

### Interfaz SaveHandler

```kotlin
interface SaveHandler {
    /** Localiza los ficheros de save para un ROM dado. */
    suspend fun findSaves(rom: Rom, config: PlatformSyncConfig): List<LocalSave>

    /** Prepara un save para subir (zip si es carpeta, raw si es fichero). */
    suspend fun prepareForUpload(save: LocalSave): File

    /** Extrae un save descargado al destino correcto en disco. */
    suspend fun extractDownload(tempFile: File, rom: Rom, config: PlatformSyncConfig): Boolean
}
```

---

## Configuración del usuario

### En la pantalla de Plataformas (nuevo campo por plataforma)

- **Ruta de saves** (override): por defecto se calcula según el emulador,
  pero el usuario puede cambiarla.
- **Emulador** (selector): determina qué handler usar. Opciones según
  la plataforma (p.ej. DS: melonDS / RetroArch).

### En Configuración general (nuevo campo)

- **Ruta base de RetroArch**: por defecto `/storage/emulated/0/RetroArch`

---

## Rutas de saves por plataforma

### RetroArch (caso general, ~80% de plataformas)

Estructura con subcarpetas por slug de plataforma (estilo ES-DE):

```
{retroarchBase}/saves/{platformSlug}/{romBaseName}.srm
{retroarchBase}/states/{platformSlug}/{romBaseName}.state
{retroarchBase}/states/{platformSlug}/{romBaseName}.state1
...
```

Donde:
- `{retroarchBase}` = ruta configurable (default: `/storage/emulated/0/RetroArch`)
- `{platformSlug}` = slug de la plataforma en RomM/ES-DE (`gba`, `snes`, `n64`, `psx`...)
- `{romBaseName}` = nombre del fichero ROM sin extensión

### DS — melonDS (prioridad 1)

```
{melondsBase}/saves/{romBaseName}.sav
```

Default: `/storage/emulated/0/melonDS/saves/`

### DS — RetroArch (prioridad 2)

Igual que el caso general de RetroArch con slug `nds`.

### PSP — PPSSPP

```
{ppssppBase}/PSP/SAVEDATA/{discId}*/
```

Cada juego genera N carpetas con prefijo de disc-id (9 chars, p.ej.
`ULUS10064`). Se agrupan todas las carpetas con el mismo prefijo, se
zipean para upload, se descomprimen en download.

Requiere extracción de disc-id del ISO/CSO.

### PS2 — AetherSX2/NetherSX2 (Folder Memory Card)

```
{aetherBase}/memcards/{cardName}.ps2/{BAserial}/
```

- El emulador debe estar configurado con "Folder Memory Card" activo.
- `{BAserial}` = `BA` + serial normalizado (p.ej. `BASLUS-21050`).
- Se zipea la carpeta del serial para upload.

Requiere extracción de serial del ISO.

### GameCube — Dolphin

```
{dolphinBase}/GC/{region}/Card A/*.gci
```

O en modo carpeta: subcarpetas por game-id. Se bundlean como zip.

### Switch — Yuzu/Sudachi

```
{yuzuBase}/nand/user/save/{userId}/{titleId}/
```

`{titleId}` = 16 chars hex.

---

## Gestión de conflictos

Cuando el servidor devuelve `"type": "conflict"`:

1. Se notifica al usuario (notificación + badge en la pestaña de sync).
2. En la UI, el usuario elige: "Mantener local", "Mantener servidor",
   "Mantener ambos".
3. Opción global: política por defecto configurable (para quien no quiera
   intervenir).

---

## Prioridad de implementación

| Fase | Alcance | Complejidad |
|------|---------|-------------|
| A    | Infraestructura: registro dispositivo, SyncWorker, negotiate/execute/complete | Media |
| B    | RetroArch handler (saves + states por slug/nombre) | Baja |
| C    | melonDS handler (DS, fichero plano) | Baja |
| D    | Configuración por plataforma (ruta override, selector emulador) | Media |
| E    | PPSSPP handler (carpetas por disc-id) | Media-alta |
| F    | PS2 handler (folder memcard por serial) | Alta |
| G    | Dolphin/GCI handler | Alta |
| H    | Switch handler | Media |

---

## Referencia: cómo lo hace Argosy

Argosy (rommapp/argosy-launcher) usa un `PlatformSaveHandlerRegistry` con
handlers especializados:

- `RetroArchSaveHandler`: fichero plano en la carpeta de saves de RA.
- `DefaultSaveHandler`: fallback genérico (fichero plano por nombre ROM).
- `FolderSaveHandler` (base): para Vita, Wii, Wii U (carpeta por title-id).
- `PspFolderHandler`: agrupa carpetas por prefijo de disc-id.
- `Ps2FolderHandler`: folder memcard con normalización de serial (`BA`-prefix).
- `N3dsFolderHandler`: traversal del arbol id0/id1.
- `SwitchSaveHandler`: carpeta por title-id.
- `GciSaveHandler`: GameCube GCI bundles.

Usa un submodulo (`sigil`) para extracción de title-id de los ROMs.
Nosotros empezaremos sin eso (usando el nombre de archivo y el slug),
y lo añadiremos cuando sea necesario para las plataformas con
carpetas por title-id.
