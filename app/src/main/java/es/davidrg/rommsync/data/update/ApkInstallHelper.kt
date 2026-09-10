package es.davidrg.rommsync.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Download progress of the update APK.
 */
sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

/**
 * Resultado de intentar lanzar el instalador del sistema.
 */
sealed class InstallLaunchResult {
    /** El instalador del sistema se abrió con el APK. */
    data object Started : InstallLaunchResult()

    /** Falta el permiso "Instalar apps desconocidas": se abrió Ajustes
     *  para concederlo. El usuario debe volver a pulsar Instalar después. */
    data object NeedsPermission : InstallLaunchResult()

    /** No se pudo lanzar (uri/intent falló). */
    data class Error(val message: String) : InstallLaunchResult()
}

/**
 * Downloads the update APK into the app's cache dir and launches the
 * system installer via a FileProvider content URI.
 *
 * The APK lands in cacheDir (no storage permission needed) and is wiped
 * by the system on uninstall or low storage.
 */
class ApkInstallHelper(
    private val context: Context,
    private val okHttpClient: OkHttpClient = AppUpdateChecker.defaultClient(),
) {

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    /**
     * Downloads [update] to cache and, on success, moves to ReadyToInstall.
     * Safe to call repeatedly; a previous partial file is overwritten.
     */
    suspend fun download(update: UpdateCheckResult.UpdateAvailable) = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, update.apkName.ifBlank { "romm-sync-update.apk" })
        val request = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", "RomM-Sync-Android-Updater")
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            _downloadState.value = UpdateDownloadState.Error(
                "Error de descarga: ${e.message ?: "error de red"}",
            )
            return@withContext
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                _downloadState.value = UpdateDownloadState.Error(
                    "El servidor devolvió ${resp.code} al descargar el APK",
                )
                return@withContext
            }
            val body = resp.body ?: run {
                _downloadState.value = UpdateDownloadState.Error("Descarga vacía")
                return@withContext
            }

            try {
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val total = body.contentLength()
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastEmit = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            downloaded += read
                            // Emit at most ~10 times/second to avoid flooding recomposition.
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 100) {
                                lastEmit = now
                                _downloadState.value = UpdateDownloadState.Downloading(
                                    progress = if (total > 0) downloaded.toFloat() / total else 0f,
                                    downloadedBytes = downloaded,
                                    totalBytes = if (total > 0) total else update.apkSize,
                                )
                            }
                        }
                        output.flush()
                    }
                }
                _downloadState.value = UpdateDownloadState.ReadyToInstall(target)
            } catch (e: Exception) {
                target.delete()
                _downloadState.value = UpdateDownloadState.Error(
                    "No se pudo guardar el APK: ${e.message ?: "error de E/S"}",
                )
            }
        }
    }

    /**
     * Fires the system package-installer intent for [apkFile].
     *
     * Si el dispositivo aún no concedió a la app el permiso "Instalar apps
     * desconocidas" (Android 8+), abre la pantalla de Ajustes específica
     * para concederlo y devuelve [InstallLaunchResult.NeedsPermission]:
     * el usuario debe volver a pulsar "Instalar" tras concederlo.
     */
    fun launchInstaller(apkFile: File): InstallLaunchResult {
        // 1. Permiso de instalación de orígenes desconocidos (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(settingsIntent)
                InstallLaunchResult.NeedsPermission
            } catch (e: Exception) {
                InstallLaunchResult.Error(
                    "No se pudo abrir Ajustes para el permiso de instalación",
                )
            }
        }

        // 2. Lanzar el instalador con el APK descargado
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        } catch (e: Exception) {
            return InstallLaunchResult.Error("No se pudo preparar el APK: ${e.message}")
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Obligatorio al lanzar desde application context
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            InstallLaunchResult.Started
        } catch (e: Exception) {
            InstallLaunchResult.Error(
                "Ninguna app pudo abrir el instalador: ${e.message}",
            )
        }
    }

    /** Deletes a previously downloaded update APK, if any. */
    fun cleanup() {
        _downloadState.value = UpdateDownloadState.Idle
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk", ignoreCase = true)) file.delete()
        }
    }
}
