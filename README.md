# RomM Sync - Cliente Android

Cliente de sincronización minimalista para servidores **RomM**. Aplicación Android nativa en Kotlin con Jetpack Compose que actúa exclusivamente como cliente de descarga y sincronización de ROMs, **no como lanzador de juegos**.

## ✨ Características

- **Descarga directa** de ROMs desde un servidor RomM a la estructura de carpetas de ES-DE
- **Eliminación de descargas** que borra el ROM del disco y lo desmarca como descargado
- **Sincronización bidireccional de saves** con el servidor (RetroArch, melonDS, PPSSPP, AetherSX2, Dolphin y Eden)
- **Autenticación por API Key** (sin login OAuth/CSRF)
- **Motor de descarga resilient** con WorkManager + CoroutineWorker
- **Soporte mod_zip** para descargas dinámicas sin tamaño fijo
- **Configuración de emulador por plataforma** con ruta de saves personalizable
- **UI minimalista** optimizada para pantallas táctiles de consolas portátiles

## 🛠️ Stack Tecnológico

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material Design 3 (tema dinámico oscuro/claro)
- **Red:** Retrofit 2 + OkHttp 4 (interceptores de progreso)
- **Background:** WorkManager + CoroutineWorker
- **DB Local:** Room
- **Imágenes:** Coil
- **Config:** Jetpack DataStore

## 📋 Fases de Desarrollo

### Fase 1 — Arquitectura, Permisos y Almacenamiento (Scoped Storage)

- Declaración de permisos: `INTERNET`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `MANAGE_EXTERNAL_STORAGE`
- Onboarding para solicitud del permiso especial `MANAGE_EXTERNAL_STORAGE`
- Configuración de ruta raíz ES-DE mediante DataStore

### Fase 2 — Red y Conectividad con la API de RomM

- Autenticación persistente mediante API Key (`rmm_...`)
- Interceptor OkHttp para inyección del token Bearer
- Endpoints Retrofit:
  - `GET /api/platforms` — plataformas disponibles (slug)
  - `GET /api/roms?platform_ids=[id]&limit=50&offset=0` — juegos paginados
- ⚠️ Usar `platform_ids` (plural), no `platform_id`
- ⚠️ Paginación obligatoria (`limit` + `offset`) para evitar OOM en bibliotecas grandes

### Fase 3 — Motor de Descarga Asíncrono (WorkManager + mod_zip)

- `CoroutineWorker` para procesar cola de descargas
- Streaming directo a disco (sin cargar archivo completo en RAM)
- Progreso dinámico vía `setProgress()` cuando `Content-Length > 0`
- Soporte mod_zip: `ZipInputStream` + descompresión al vuelo cuando `Content-Length = -1`

### Fase 4 — Mapeo Estructural ES-DE

- Normalización de carpetas por slug de plataforma en minúsculas:
  - `snes` → `/ROMs/snes/`
  - `megadrive` → `/ROMs/megadrive/`

### Fase 5 — Interfaz de Usuario Minimalista

| Vista | Descripción |
|---|---|
| **Configuración** | URL servidor, API Key, directorio raíz y descargas simultáneas, agrupados en secciones (Cards) con iconos |
| **Plataformas** | Tarjetas con avatar de inicial y switches mostrar/ocultar por plataforma; cabecera con resumen y acción de actualizar |
| **Biblioteca** | Rejilla de carátulas con Coil, título superpuesto sobre degradado, badges de estado, búsqueda + filtros (Todos/Faltantes/Descargados). Pulsación larga abre el detalle con opción de eliminar la descarga. En horizontal, los controles se agrupan en una sola fila para maximizar el área de carátulas |
| **Cola de Descargas** | Tarjetas con badge de estado por color, barra de progreso animada, estado indeterminate para mod_zip, cancelar y reintentar |

### Fase 6 — Rediseño Visual (Sistema de Diseño)

- **Sistema de color completo** Material 3 ("Midnight Arcade") con esquemas oscuro y claro: roles `primary`/`secondary`/`tertiary` con sus *containers*, tiers de `surfaceContainer` para profundidad, `outline`, `scrim` e `inverse`
- **Tema adaptable** que sigue el modo del sistema (oscuro por defecto en consolas portátiles) con barras de sistema *edge-to-edge*
- **Tipografía** con jerarquía ampliada y *letter-spacing* afinado
- **Componentes basados en Cards** con esquinas redondeadas, badges de estado y *empty states* con icono
- **Navegación** con iconos *outlined*/*filled* según selección e indicador en `primaryContainer`

### Fase 7 — Sincronización de Saves (Device Sync Protocol)

Sincronización bidireccional de partidas guardadas con el servidor RomM,
siguiendo el [Device Sync Protocol](https://docs.romm.app/latest/developers/device-sync-protocol/) (RomM 4.9+).

**Flujo del protocolo:**

1. Registro del dispositivo (`POST /api/devices`, `device_id` cacheado)
2. Negociación (`POST /api/sync/negotiate`): el cliente envía sus saves (nombre, mtime, sha1) y el servidor responde con operaciones `upload`/`download`/`conflict`/`noop`
3. Ejecución (`POST /api/saves`, `GET /api/saves/{id}/content`)
4. Cierre de sesión (`POST /api/sync/sessions/{id}/complete`)

**Handlers por emulador** (patrón Strategy en `data/sync/platform/`):

| Plataforma | Emulador (defecto) | Estructura de saves |
|---|---|---|
| Retro (NES-N64, GBA, PSX...) | RetroArch | `{base}/saves/{slug}/{rom}.srm` y `states/{slug}/{rom}.state*` (slugs ES-DE) |
| DS | melonDS (o RetroArch) | `{base}/{rom}.sav` plano |
| PSP | PPSSPP | `{base}/PSP/SAVEDATA/{discId}*` (carpetas agrupadas en zip) |
| PS2 | AetherSX2/NetherSX2 | `{base}/memcards/{card}.ps2/{BAserial}/` (folder memory card) |
| GameCube | Dolphin | `{base}/GC/{region}/Card A/{gameId}*.gci` |
| Wii | Dolphin | `{base}/Wii/title/{high}/{low}/data/` |
| Switch | Eden | `{base}/nand/user/save/{userId}/{profileId}/{titleId}/` |

**Configuración:** desde la pestaña Plataformas, cada plataforma permite elegir
emulador y una ruta de saves personalizada (override). La ruta base de RetroArch
se configura en Configuración. La sincronización se dispara manualmente con el
botón "Sincronizar ahora" y corre como `CoroutineWorker` con notificación
foreground.

> ⚠️ Para PS2 se requiere activar **Folder Memory Card** en el emulador para
> sincronizar por juego (en vez de una memory card monolítica de 8 MB).

## 🏗️ Arquitectura

```
app/src/main/java/es/davidrg/rommsync/
├── RomMSyncApplication.kt     # Application + Configuration.Provider (WorkManager)
├── MainActivity.kt            # Single-activity host (Compose)
├── data/
│   ├── AppContainer.kt        # Manual DI container
│   ├── local/
│   │   ├── SettingsDataStore.kt    # DataStore (server config persistente)
│   │   ├── RomSyncDatabase.kt      # Room database
│   │   ├── entity/                 # PlatformEntity, DownloadedRomEntity
│   │   └── dao/                    # PlatformDao, RomDao
│   ├── remote/
│   │   ├── RomMApiService.kt       # Retrofit endpoints (platforms, roms, download)
│   │   ├── AuthInterceptor.kt      # OkHttp Bearer token injection
│   │   ├── NetworkModule.kt        # Retrofit/OkHttp factory
│   │   └── dto/                    # PlatformDto, RomDto (Moshi)
│   └── repository/
│       ├── RomRepository.kt        # RomM data bridge (remote + cache)
│       └── SettingsRepository.kt
├── domain/model/                   # Platform, Rom, DownloadTask (pure domain)
├── download/
│   ├── DownloadWorker.kt           # CoroutineWorker (stream + mod_zip extract)
│   ├── DownloadManager.kt          # WorkManager queue management
│   └── PathMapper.kt               # RomM slug → ES-DE folder mapping
├── data/sync/
│   ├── SyncCoordinator.kt          # Orquesta negotiate → execute → complete
│   ├── SaveSyncWorker.kt           # CoroutineWorker de sincronización
│   ├── SaveSyncManager.kt          # API para la UI (trigger + estado)
│   └── platform/                   # Handlers por emulador (Strategy)
│       ├── SaveHandler.kt          # Interfaz base + LocalSave
│       ├── SaveHandlerRegistry.kt  # Selección de handler por plataforma/emulador
│       ├── RetroArchSaveHandler.kt
│       ├── MelonDsSaveHandler.kt
│       ├── PpssppSaveHandler.kt
│       ├── Ps2SaveHandler.kt
│       ├── DolphinSaveHandler.kt
│       └── SwitchSaveHandler.kt
├── ui/
│   ├── theme/                      # Material3 dark-first theme
│   ├── navigation/                 # NavHost + bottom bar (4 tabs)
│   ├── viewmodel/                  # ConfigVM, PlatformsVM, LibraryVM, DownloadsVM
│   └── screens/                    # Config, Platforms, Library, Downloads
└── util/Permissions.kt             # MANAGE_EXTERNAL_STORAGE + notifications
```

## 🔧 Configuración del Servidor RomM

La app requiere:

- **ROMM_BASE_URL:** Dirección del servidor (ej: `https://romm.midominio.com`)
- **API Key:** Token de usuario con formato `rmm_...`

No se solicita usuario/contraseña — solo autenticación por API Key para evitar bloqueos de sesión OAuth/CSRF y problemas de redirección OIDC (Authentik, Authelia).

## 📁 Estructura Local

```
/storage/emulated/0/ROMs/   (o ruta configurada de microSD)
├── snes/
│   ├── Super Mario World.sfc
│   └── ...
├── megadrive/
│   ├── Sonic the Hedgehog.bin
│   └── ...
└── ...
```

## 🔐 Compilación y Firma (CI/CD)

Cada push a `master` dispara el workflow de GitHub Actions que compila el APK de release (R8 + shrink) y lo publica como GitHub Release.

### Firma estable

El APK se firma siempre con **una clave de release fija** para que las actualizaciones se instalen encima de la versión anterior sin el error de *"conflicto de paquete"* (Android rechaza actualizar si cambia el certificado de firma).

La clave se inyecta en CI mediante *secrets* del repositorio (Settings → Secrets and variables → Actions):

| Secret | Descripción |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Keystore (`.jks`) codificado en base64 |
| `SIGNING_STORE_PASSWORD` | Contraseña del almacén |
| `SIGNING_KEY_ALIAS` | Alias de la clave (`romm-sync`) |
| `SIGNING_KEY_PASSWORD` | Contraseña de la clave |

> ⚠️ El keystore y sus contraseñas son críticos: guárdalos con copia de seguridad. Si se pierden, no se pueden volver a publicar actualizaciones (obligaría a desinstalar/reinstalar en cada versión).

### Build local

Para firmar en local, crea un `keystore.properties` en la raíz (ignorado por git) con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`. Si no existe, el build de release usa la clave de debug.

```bash
./gradlew assembleRelease
```

## 📄 Licencia

MIT
