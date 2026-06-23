package es.davidrg.rommsync.util

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Checks whether the app has the MANAGE_EXTERNAL_STORAGE permission
 * (All Files Access), required for writing ROMs directly to the ES-DE
 * folder structure on Android 11+.
 */
fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // Below Android 11, WRITE_EXTERNAL_STORAGE covers it
    }
}

/**
 * Opens the system settings page to grant MANAGE_EXTERNAL_STORAGE.
 */
fun requestAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}

/**
 * Compose helper: request POST_NOTIFICATIONS permission (Android 13+).
 * Returns a [Boolean] state that is updated when the user returns from the permission dialog.
 */
@Composable
fun rememberNotificationPermissionState(): androidx.compose.runtime.MutableState<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = remember { mutableStateOf(hasNotificationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted.value = isGranted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted.value) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    return granted
}

fun hasNotificationPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

/**
 * Convierte un tree-URI del Storage Access Framework (devuelto por
 * [ActivityResultContracts.OpenDocumentTree]) en una ruta absoluta de
 * filesystem utilizable con [java.io.File].
 *
 * La app usa MANAGE_EXTERNAL_STORAGE, por lo que puede operar directamente
 * sobre rutas absolutas. El document-id de un tree-URI tiene el formato
 * `"{volumen}:{ruta relativa}"` (p.ej. `"primary:ROMs/saves"`).
 *
 * - Volumen `primary` -> `/storage/emulated/0/{ruta}`.
 * - Otros volúmenes (tarjeta SD) -> `/storage/{volumen}/{ruta}`.
 *
 * Devuelve null si el URI no se puede interpretar.
 */
fun treeUriToPath(uri: android.net.Uri): String? {
    return runCatching {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":", limit = 2)
        val volume = split.getOrNull(0).orEmpty()
        val relative = split.getOrNull(1).orEmpty()

        val base = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }

        if (relative.isEmpty()) base else "$base/$relative"
    }.getOrNull()
}
