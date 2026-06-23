package es.davidrg.rommsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Límite superior de navegación. No se permite subir por encima de `/storage`,
 * que es la raíz que agrupa el almacenamiento interno y las tarjetas SD.
 */
private const val NAV_ROOT = "/storage"
private const val DEFAULT_START = "/storage/emulated/0"

/**
 * Explorador de carpetas propio basado en [java.io.File].
 *
 * A diferencia del Storage Access Framework (que en Android 11+ no permite
 * navegar a `Android/data/...`), este diálogo usa acceso directo al sistema de
 * ficheros, disponible gracias a `MANAGE_EXTERNAL_STORAGE`. Así el usuario puede
 * llegar a las carpetas internas de los emuladores donde viven los saves.
 *
 * @param initialPath ruta inicial; si no existe, se sube al primer ancestro
 *   existente.
 * @param onDismiss se invoca al cancelar.
 * @param onSelect se invoca con la ruta absoluta de la carpeta elegida.
 */
@Composable
fun FolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var currentDir by remember {
        mutableStateOf(existingAncestorOrDefault(initialPath))
    }

    val subDirs = remember(currentDir.path) {
        currentDir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    val canGoUp = currentDir.path != NAV_ROOT && currentDir.parentFile != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar carpeta") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    currentDir.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(8.dp))
                HorizontalDivider()

                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (canGoUp) {
                        item {
                            FolderRow(
                                name = "..",
                                icon = { UpIcon() },
                                onClick = {
                                    currentDir.parentFile?.let { parent ->
                                        if (parent.path.startsWith(NAV_ROOT)) {
                                            currentDir = parent
                                        }
                                    }
                                },
                            )
                        }
                    }
                    items(subDirs, key = { it.path }) { dir ->
                        FolderRow(
                            name = dir.name,
                            icon = { FolderIcon() },
                            onClick = { currentDir = dir },
                        )
                    }
                    if (subDirs.isEmpty() && !canGoUp) {
                        item {
                            Text(
                                "No hay subcarpetas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(currentDir.path) }) {
                Text("Usar esta carpeta")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun FolderRow(
    name: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderIcon() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun UpIcon() {
    Icon(
        Icons.Filled.KeyboardArrowUp,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * Devuelve la propia ruta si existe como directorio; si no, sube hasta el
 * primer ancestro existente. Como último recurso devuelve el almacenamiento
 * interno principal.
 */
private fun existingAncestorOrDefault(path: String): File {
    var file = File(path)
    while (!(file.exists() && file.isDirectory)) {
        file = file.parentFile ?: return File(DEFAULT_START)
    }
    return file
}
