package com.miptv

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Playlist sources: add by URL or local file (SAF), refresh, remove. Locations are never displayed in full for URLs with credentials. */
fun LazyListScope.sourcesSection(
    state: LiveState,
    addUrl: (String, String) -> Unit,
    addFile: (String, String) -> Unit,
    refresh: (Long) -> Unit,
    remove: (Long) -> Unit
) {
    item { Text("Ajoutez une playlist M3U/M3U8 par adresse ou depuis un fichier local. Les identifiants contenus dans une adresse restent sur l'appareil.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { AddSourceCard(state.importStatus, addUrl, addFile) }
    item {
        val status = state.importStatus
        if (status.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(status.message.orEmpty(), modifier = Modifier.testTag("import-status"))
            }
        } else if (status.message != null) {
            Text(status.message, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("import-status"))
        }
    }
    item { Text("Sources configurées · ${state.sources.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    if (state.sources.isEmpty()) item { Text("Aucune source pour le moment.", modifier = Modifier.testTag("no-sources")) }
    items(state.sources, key = { "source-${it.id}" }) { source -> SourceCard(source, state.importStatus.running, { refresh(source.id) }, { remove(source.id) }) }
}

@Composable
private fun AddSourceCard(status: ImportStatus, addUrl: (String, String) -> Unit, addFile: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        addFile(name.ifBlank { displayName.orEmpty() }, uri.toString())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nouvelle source", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom (facultatif)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("source-name"))
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Adresse de la playlist") }, singleLine = true,
                placeholder = { Text("https://exemple.tld/playlist.m3u") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("source-url"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { addUrl(name, url) }, enabled = !status.running && url.isNotBlank(), modifier = Modifier.testTag("add-url")) { Text("Ajouter l'adresse") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !status.running, modifier = Modifier.testTag("add-file")) { Text("Fichier .m3u") }
            }
        }
    }
}

@Composable
private fun SourceCard(source: Source, busy: Boolean, refresh: () -> Unit, remove: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("source-${source.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(if (source.kind == SourceKind.URL) "Adresse · ${source.location.redactedHost()}" else "Fichier local", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                source.lastRefreshed?.let { "${source.channelCount} chaînes · actualisé le ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" } ?: "Jamais actualisé",
                style = MaterialTheme.typography.bodySmall
            )
            source.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = refresh, enabled = !busy, modifier = Modifier.testTag("refresh-${source.id}")) {
                    Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Actualiser")
                }
                TextButton(onClick = remove, enabled = !busy, modifier = Modifier.testTag("remove-${source.id}")) {
                    Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Supprimer")
                }
            }
        }
    }
}

/** Shows only the scheme and host: paths and query strings often carry credentials. */
private fun String.redactedHost(): String {
    val scheme = substringBefore("://", "")
    val host = substringAfter("://").substringBefore('/').substringAfter('@')
    return if (scheme.isEmpty()) host else "$scheme://$host"
}
