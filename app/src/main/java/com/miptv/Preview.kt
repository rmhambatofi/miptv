package com.miptv

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Released when leaving TV or changing selection; playback never continues in the background. */
@Composable
fun Preview(channel: Channel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(context, channel.id) { ExoPlayer.Builder(context).build() }
    var status by remember(channel.id) { mutableStateOf("Chargement…") }
    var failed by remember(channel.id) { mutableStateOf(false) }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_BUFFERING -> "Chargement…"
                    Player.STATE_READY -> "Utilisez les commandes tactiles pour lire ou mettre en pause."
                    Player.STATE_ENDED -> "Lecture terminée"
                    else -> "En attente"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                status = "Lecture indisponible. Vérifiez votre connexion."
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        player.prepare()
        player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(channel.name, style = MaterialTheme.typography.titleMedium)
        AndroidView(
            factory = { PlayerView(it).apply { useController = true; this.player = player } },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (failed) Button(onClick = { failed = false; player.prepare(); player.play() }) { Text("Réessayer") }
    }
}
