# RomM Sync - Cliente Android

Cliente de sincronización minimalista para servidores **RomM**. Aplicación Android nativa en Kotlin con Jetpack Compose que actúa exclusivamente como cliente de descarga y sincronización de ROMs, **no como lanzador de juegos**.

## ✨ Características

- **Descarga directa** de ROMs desde un servidor RomM a la estructura de carpetas de ES-DE
- **Autenticación por API Key** (sin login OAuth/CSRF)
- **Motor de descarga resilient** con WorkManager + CoroutineWorker
- **Soporte mod_zip** para descargas dinámicas sin tamaño fijo
- **Sincronización bidireccional** de guardados y estados de emuladores
- **UI minimalista** optimizada para pantallas táctiles de consolas portátiles

## 🛠️ Stack Tecnológico

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material Design 3
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
| **Configuración** | URL servidor, API Key, directorio raíz, descargas simultáneas |
| **Plataformas** | Switches mostrar/ocultar por plataforma |
| **Biblioteca** | Rejilla de carátulas con Coil + búsqueda + filtros (Todos/Faltantes/Descargados) |
| **Cola de Descargas** | Progreso numérico + barra, estado indeterminate para mod_zip, cancelar |

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

## 📄 Licencia

MIT
