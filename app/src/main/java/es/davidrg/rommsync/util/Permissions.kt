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
 * Comprueba si la app está exenta de optimizaciones de batería.
 * Necesario para que la sincronización periódica en segundo plano
 * funcione de forma fiable en todos los fabricantes.
 */
fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE)
        as android.os.PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Solicita al sistema que excluya la app de las optimizaciones de batería.
 * Abre un diálogo del sistema directamente (no la pantalla de ajustes).
 */
@android.annotation.SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
