package com.miptv

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text

@Composable
fun Preview(channel: Channel, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val owner = LocalLifecycleOwner.current
 val player = remember(context) { ExoPlayer.Builder(context).build() }
 var status by remember(channel.id) { mutableStateOf("Chargement…") }
 var failed by remember(channel.id) { mutableStateOf(false) }
 DisposableEffect(player, owner) {
  val listener = object : Player.Listener {
   override fun onPlaybackStateChanged(state: Int) { status = when (state) { Player.STATE_BUFFERING -> "Chargement…"; Player.STATE_READY -> "Lecture disponible"; Player.STATE_ENDED -> "Lecture terminée"; else -> "En attente" } }
   override fun onPlayerError(error: PlaybackException) { failed = true; status = "Lecture indisponible. Vérifiez la connexion puis réessayez." }
  }
  val observer = LifecycleEventObserver { _, event ->
   if (event == Lifecycle.Event.ON_STOP) player.pause()
   if (event == Lifecycle.Event.ON_START) player.play()
  }
  player.addListener(listener); owner.lifecycle.addObserver(observer)
  onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
 }
 LaunchedEffect(channel.streamUrl) { failed = false; player.setMediaItem(MediaItem.fromUri(channel.streamUrl)); player.prepare(); player.playWhenReady = true }
 Column {
  AndroidView(factory = { PlayerView(it).apply { useController = false; isFocusable = false; this.player = player } }, modifier = modifier)
  Text(status)
  Button(onClick = { if (failed) { failed = false; player.prepare(); player.play() } else if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }) { Text(if (failed) "Réessayer" else "Lecture / Pause") }
 }
}
