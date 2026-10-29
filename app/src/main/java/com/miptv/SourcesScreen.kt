package com.miptv

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Callbacks of the Sources screen. */
class SourceActions(
    val addUrl: (String, String) -> Unit,
    val addFile: (String, String) -> Unit,
    val addXtream: (name: String, server: String, username: String, password: String) -> Unit,
    val checkXtream: (server: String, username: String, password: String) -> Unit,
    val clearCheck: () -> Unit,
    val refresh: (Long) -> Unit,
    val remove: (Long) -> Unit
)

/**
 * Sources: an M3U playlist by URL or local file (SAF), or an Xtream provider by server, user name and password.
 * Locations are shown host-only and the password is masked; neither is ever logged.
 */
fun LazyListScope.sourcesSection(state: LiveState, actions: SourceActions) {
    item { Text("Une playlist M3U/M3U8 par adresse ou fichier local, ou un fournisseur Xtream Codes avec vos identifiants. Tout reste sur l'appareil ; le mot de passe est chiffré.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { AddSourceCard(state, actions) }
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
    items(state.sources, key = { "source-${it.id}" }) { source -> SourceCard(source, state.importStatus.running, { actions.refresh(source.id) }, { actions.remove(source.id) }) }
}

private enum class SourceForm(val label: String) { M3U("Playlist M3U"), XTREAM("Fournisseur Xtream") }

@Composable
private fun AddSourceCard(state: LiveState, actions: SourceActions) {
    var form by rememberSaveable { mutableStateOf(SourceForm.M3U) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nouvelle source", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SourceForm.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = form == option,
                        onClick = { form = option; actions.clearCheck() },
                        shape = SegmentedButtonDefaults.itemShape(index, SourceForm.entries.size),
                        modifier = Modifier.testTag("form-${option.name}")
                    ) { Text(option.label, maxLines = 1) }
                }
            }
            when (form) {
                SourceForm.M3U -> M3uForm(state.importStatus, actions.addUrl, actions.addFile)
                SourceForm.XTREAM -> XtreamForm(state, actions)
            }
        }
    }
}

@Composable
private fun M3uForm(status: ImportStatus, addUrl: (String, String) -> Unit, addFile: (String, String) -> Unit) {
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** The password lives in this composable's saved state only while the form is open; it is never echoed. */
@Composable
private fun XtreamForm(state: LiveState, actions: SourceActions) {
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val complete = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val busy = state.importStatus.running || state.checkingAccount
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom (facultatif)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("xtream-name"))
        OutlinedTextField(value = server, onValueChange = { server = it; actions.clearCheck() }, label = { Text("Serveur") }, singleLine = true,
            placeholder = { Text("http://exemple.tld:8080") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("xtream-server"))
        OutlinedTextField(value = username, onValueChange = { username = it; actions.clearCheck() }, label = { Text("Identifiant") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("xtream-username"))
        OutlinedTextField(value = password, onValueChange = { password = it; actions.clearCheck() }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("toggle-password")) { Text(if (showPassword) "Masquer" else "Afficher") }
            },
            modifier = Modifier.fillMaxWidth().testTag("xtream-password"))
        state.accountCheck?.let { check ->
            when (check) {
                is Fetched.Success -> Text(describeAccount(check.value), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-check"))
                is Fetched.Failure -> Text(check.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-check"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { actions.checkXtream(server, username, password) }, enabled = !busy && complete, modifier = Modifier.testTag("check-xtream")) {
                if (state.checkingAccount) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Tester la connexion")
            }
            Button(onClick = { actions.addXtream(name, server, username, password) }, enabled = !busy && complete, modifier = Modifier.testTag("add-xtream")) { Text("Ajouter") }
        }
    }
}

/** "Compte actif jusqu'au 01/01/2027 · 1/2 connexions · formats ts, m3u8". */
fun describeAccount(info: XtreamAccountInfo): String {
    val until = info.expiresAt?.let { "jusqu'au ${frenchDate(it)}" } ?: "sans date d'expiration"
    val connections = info.maxConnections?.let { max -> "${info.activeConnections ?: 0}/$max connexion(s)" }
    val formats = info.allowedOutputFormats.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "formats $it" }
    val warning = info.connectionsMessage()
    return listOfNotNull("Compte ${info.status.lowercase()} $until", connections, formats, warning).joinToString(" · ")
}

@Composable
private fun SourceCard(source: Source, busy: Boolean, refresh: () -> Unit, remove: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("source-${source.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(
                when (source.kind) {
                    SourceKind.URL -> "Adresse · ${source.location.redactedHost()}"
                    SourceKind.FILE -> "Fichier local"
                    SourceKind.XTREAM -> "Xtream · ${source.location.redactedHost()}" + (source.username?.let { " · $it" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source.kind == SourceKind.XTREAM) {
                Text(
                    source.expiresAt?.let { "Compte valable jusqu'au ${frenchDate(it)}" } ?: "Compte sans date d'expiration",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("source-expiry-${source.id}")
                )
            }
            Text(
                source.lastRefreshed?.let {
                    val counts = if (source.kind == SourceKind.XTREAM) "${source.channelCount} chaînes · ${source.movieCount} films · ${source.seriesCount} séries" else "${source.channelCount} chaînes"
                    "$counts · actualisé le ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
                } ?: "Jamais actualisé",
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
