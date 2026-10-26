package com.miptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
 private val model: LiveViewModel by viewModels()
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MiptvTheme { val state by model.state.collectAsStateWithLifecycle(); Shell(state, model::category, model::select) } }
 }
}

@Composable
fun MiptvTheme(content: @Composable () -> Unit) {
 MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0D12), surface = Color(0xFF181C26), primary = Color(0xFFEC3852), onSurface = Color.White), content = content)
}

@Composable
private fun Shell(state: LiveState, category: (String) -> Unit, select: (Channel) -> Unit) {
 val destinations = listOf("Accueil", "Live TV", "Films", "Séries", "Recherche", "Paramètres")
 var page by remember { mutableStateOf("Live TV") }
 val nav = remember { List(6) { FocusRequester() } }
 val categories = remember { FocusRequester() }
 val channels = remember { FocusRequester() }
 val preview = remember { FocusRequester() }
 BackHandler(page != "Accueil") { page = "Accueil"; nav[0].requestFocus() }
 LaunchedEffect(Unit) { nav[1].requestFocus() }
 Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
  Text("miptv", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
   destinations.forEachIndexed { index, title ->
    Button(onClick = { page = title }, modifier = Modifier.focusRequester(nav[index]).focusProperties {
     if (page == "Live TV") down = categories
    }) { Text(if (page == title) "• $title" else title) }
   }
  }
  if (page == "Live TV") {
   Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(Modifier.weight(0.23f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
     Text("CATÉGORIES", style = MaterialTheme.typography.titleMedium)
     state.catalog.categories.forEachIndexed { index, item ->
      Button(onClick = { category(item.id) }, modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(categories) else Modifier).focusProperties {
       up = nav[1]; right = if (state.catalog.channelsIn(state.categoryId).isEmpty()) preview else channels
      }) { Text(if (state.categoryId == item.id) "• ${item.name}" else item.name) }
     }
    }
    Column(Modifier.weight(0.30f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
     Text("CHAÎNES · DÉMO", style = MaterialTheme.typography.titleMedium)
     val visible = state.catalog.channelsIn(state.categoryId)
     if (visible.isEmpty()) Text("Aucune chaîne dans cette catégorie.")
     LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
      itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
       Button(onClick = { select(item) }, modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(channels) else Modifier).focusProperties {
        left = categories; right = preview
        if (index == 0) up = nav[1]
       }) { Text(if (state.selected?.id == item.id) "▶ ${item.name}" else item.name) }
      }
     }
    }
    Column(Modifier.weight(0.47f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
     Text(state.selected?.name ?: "APERÇU", style = MaterialTheme.typography.titleMedium)
     if (state.selected != null) Preview(state.selected, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
     else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surface).padding(20.dp)) { Text("Choisissez une chaîne puis appuyez sur OK.") }
     Text("Films de démonstration à la demande — pas une diffusion en direct.", style = MaterialTheme.typography.bodySmall)
     Button(onClick = { if (state.catalog.channelsIn(state.categoryId).isNotEmpty()) channels.requestFocus() else categories.requestFocus() }, modifier = Modifier.focusRequester(preview).focusProperties { left = if (state.catalog.channelsIn(state.categoryId).isEmpty()) categories else channels; up = nav[1] }) { Text("Choisir une chaîne") }
    }
   }
  } else {
   Text(page, style = MaterialTheme.typography.headlineLarge)
   Text(when (page) {
    "Accueil" -> "Bienvenue dans miptv. Ouvrez Live TV pour découvrir le lecteur."
    "Paramètres" -> "Mode démonstration · Aucun fournisseur configuré · Aucun compte requis."
    else -> "Cette rubrique sera disponible dans un prochain sprint."
   })
  }
 }
}
