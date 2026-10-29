package com.miptv

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** One ExoPlayer for one [item]. Status text and flags drive the UI; [retry] resolves and prepares again. */
class Playback(val item: PlayItem, val player: ExoPlayer) {
    var status by mutableStateOf("Chargement…")
    var failed by mutableStateOf(false)
    /** A film or episode reached its end. */
    var ended by mutableStateOf(false)
    /** Bumped by [retry]; the effect that resolves the URL and prepares the player keys on it. */
    var attempt by mutableIntStateOf(0)
        private set
    fun fail(message: String) {
        failed = true
        status = message
    }
    fun retry() {
        failed = false
        ended = false
        status = "Chargement…"
        attempt++
    }
}

/** How long a stream may keep loading, while playback is wanted, before the error and "Réessayer" show. */
const val LOADING_LIMIT_MS = 20_000L

const val LOADING_TIMEOUT_MESSAGE = "Le flux ne répond pas après 20 secondes."

const val CREDENTIALS_MESSAGE = "Identifiants de la source à ressaisir dans Sources."

/** Resume positions are written this often while a film or episode plays, and when the player closes. */
const val PROGRESS_INTERVAL_MS = 10_000L

/**
 * Measures how long the player has been loading while playback is wanted. Media3 retries network errors on
 * its own and a server that accepts the connection but sends nothing keeps it buffering for ever; this bounds
 * both, at start and when a live stream stalls. A pause (for instance in the background) stops the clock.
 */
class LoadingWatch(private val limitMs: Long = LOADING_LIMIT_MS) {
    private var since: Long? = null
    fun update(loading: Boolean, nowMs: Long) {
        since = if (loading) since ?: nowMs else null
    }
    fun expired(nowMs: Long): Boolean = since?.let { nowMs - it >= limitMs } ?: false
}

/** Live channel playback with the stored URL as is; kept for the TV tab and the timeout test. */
@Composable
fun rememberPlayback(channel: Channel?, loadingLimitMs: Long = LOADING_LIMIT_MS): Playback? =
    rememberPlayback(channel?.toPlayItem(), resolve = { it }, onProgress = null, loadingLimitMs = loadingLimitMs)

/**
 * Hoisted player state: created when an item is selected, released when the selection changes or disappears,
 * paused when the activity stops. Keeping it above the layout means entering fullscreen only re-attaches the
 * surface instead of recreating the player. [resolve] turns an `xtream://` reference into the playable URL when
 * playback starts (a null means the credentials are gone); [onProgress] receives the position of a film or
 * episode every [PROGRESS_INTERVAL_MS], at its end and when the player closes.
 */
@Composable
fun rememberPlayback(
    item: PlayItem?,
    resolve: suspend (String) -> String?,
    onProgress: ((itemId: String, positionMs: Long, durationMs: Long) -> Unit)?,
    loadingLimitMs: Long = LOADING_LIMIT_MS
): Playback? {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val playback = remember(item?.id) { item?.let { Playback(it, buildPlayer(context, it)) } }
    val report = rememberUpdatedState(onProgress)
    DisposableEffect(playback, owner) {
        if (playback == null) return@DisposableEffect onDispose { }
        val player = playback.player
        fun progress() {
            if (playback.item.live) return
            val duration = player.duration
            if (duration > 0) report.value?.invoke(playback.item.id, player.currentPosition.coerceIn(0, duration), duration)
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // After an error the player goes back to IDLE; keep the error message visible until a retry.
                if (playback.failed && state == Player.STATE_IDLE) return
                playback.status = when (state) {
                    Player.STATE_BUFFERING -> "Chargement…"
                    Player.STATE_READY -> "Utilisez les commandes tactiles pour lire ou mettre en pause."
                    Player.STATE_ENDED -> "Lecture terminée"
                    else -> "En attente"
                }
                if (state == Player.STATE_ENDED) {
                    playback.ended = true
                    progress()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    playback.status = "Flux en direct décalé, reprise…"
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                playback.fail(describePlaybackError(error))
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.pause(); progress() } }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            progress()
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    // Resolution and preparation, repeated on every retry. The URL with credentials lives only in the player.
    LaunchedEffect(playback, playback?.attempt) {
        if (playback == null) return@LaunchedEffect
        val player = playback.player
        val resumeAt = if (playback.attempt == 0) playback.item.resumeMs else player.currentPosition.takeIf { it > 0 } ?: playback.item.resumeMs
        val url = resolve(playback.item.streamUrl)
        if (url == null) {
            playback.fail(CREDENTIALS_MESSAGE)
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(url))
        if (!playback.item.live && resumeAt > 0) player.seekTo(resumeAt) else player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    LaunchedEffect(playback) {
        if (playback == null) return@LaunchedEffect
        val watch = LoadingWatch(loadingLimitMs)
        var sinceReport = 0L
        while (true) {
            val player = playback.player
            val now = System.currentTimeMillis()
            watch.update(!playback.failed && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING, now)
            if (watch.expired(now)) {
                // stop() abandons Media3's own retries; the listener keeps the message while the player is idle.
                playback.fail(LOADING_TIMEOUT_MESSAGE)
                player.stop()
                watch.update(false, now)
            }
            if (!playback.item.live && player.isPlaying) {
                sinceReport += 500
                if (sinceReport >= PROGRESS_INTERVAL_MS) {
                    sinceReport = 0
                    val duration = player.duration
                    if (duration > 0) report.value?.invoke(playback.item.id, player.currentPosition.coerceIn(0, duration), duration)
                }
            }
            delay(500)
        }
    }
    return playback
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, item: PlayItem): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(item.userAgent ?: DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
    item.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        // ±10 s on the controller's rewind and forward buttons (films and episodes).
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()
}

const val SEEK_STEP_MS = 10_000L

/** French, user-facing description; never includes the stream URL. */
@OptIn(UnstableApi::class)
fun describePlaybackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> "Connexion au flux impossible. Vérifiez votre réseau."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null) "Le serveur a refusé le flux (HTTP $code)." else "Le serveur a refusé le flux."
    }
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Flux introuvable (HTTP 404)."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION, PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Accès au flux refusé."
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Format du flux non reconnu."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "Ce flux ne peut pas être décodé sur cet appareil."
    else -> "Lecture indisponible. Vérifiez votre connexion."
}

private const val DEFAULT_USER_AGENT = "miptv/0.4 (Android; Media3)"

/** Media3 view bound to the hoisted player; the controller's own fullscreen button toggles [onFullscreen]. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(playback: Playback, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { onFullscreen(it) }
            }
        },
        update = { view ->
            view.player = playback.player
            view.setFullscreenButtonState(fullscreen)
        },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

/** Inline preview inside the TV tab. */
@Composable
fun Preview(playback: Playback, favorite: Boolean, onFullscreen: (Boolean) -> Unit, nowNext: NowNext? = null, nowMs: Long = 0L, onToggleFavorite: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(playback.item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FavoriteButton(favorite, Modifier.testTag("preview-favorite"), onToggleFavorite)
            TextButton(onClick = { onFullscreen(true) }, modifier = Modifier.testTag("enter-fullscreen")) { Text("Plein écran") }
        }
        PlayerSurface(playback, fullscreen = false, onFullscreen = onFullscreen, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        if (nowNext != null) NowLine(nowNext, nowMs, Modifier.testTag("preview-now"))
        Text(playback.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("player-status"))
        if (playback.failed) Button(onClick = playback::retry, modifier = Modifier.testTag("retry")) { Text("Réessayer") }
    }
}

/** Player of a film or episode inside its sheet: the controller carries the time bar and ±10 s; [onNext] plays the next episode. */
@Composable
fun VodPlayer(playback: Playback, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit, onNext: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("vod-player")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(playback.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                playback.item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            TextButton(onClick = { onFullscreen(true) }, modifier = Modifier.testTag("enter-fullscreen")) { Text("Plein écran") }
            IconButton(onClick = onClose, modifier = Modifier.testTag("close-player")) { Icon(Icons.Default.Close, contentDescription = "Fermer le lecteur") }
        }
        PlayerSurface(playback, fullscreen = false, onFullscreen = onFullscreen, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Text(playback.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("player-status"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (playback.failed) Button(onClick = playback::retry, modifier = Modifier.testTag("retry")) { Text("Réessayer") }
            if (onNext != null) {
                if (playback.ended) Button(onClick = onNext, modifier = Modifier.testTag("next-episode")) { Text("Épisode suivant") }
                else OutlinedButton(onClick = onNext, modifier = Modifier.testTag("next-episode")) { Text("Épisode suivant") }
            }
        }
    }
}

/** Landscape, system bars hidden by the activity; back or the close button returns to the tab. [onNext] plays the next episode. */
@Composable
fun FullscreenPlayer(playback: Playback, onExit: () -> Unit, onNext: (() -> Unit)? = null, nowNext: NowNext? = null) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen-player")) {
        PlayerSurface(playback, fullscreen = true, onFullscreen = { if (!it) onExit() }, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, modifier = Modifier.testTag("exit-fullscreen")) { Icon(Icons.Default.Close, contentDescription = "Quitter le plein écran", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(playback.item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (nowNext != null) Text(
                    "En ce moment · ${nowNext.now.title}" + (nowNext.next?.let { "   Ensuite · ${it.title}" } ?: ""),
                    color = Color(0xFFD0D4E0), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.testTag("fullscreen-now")
                )
            }
        }
        if (playback.failed || (playback.ended && onNext != null)) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(playback.status, color = Color.White)
                if (playback.failed) Button(onClick = playback::retry) { Text("Réessayer") }
                if (playback.ended && onNext != null) Button(onClick = onNext) { Text("Épisode suivant") }
            }
        }
    }
}
