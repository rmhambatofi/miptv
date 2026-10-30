package com.miptv

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** The player has a picture to show (Media3 READY). */
    var ready by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var muted by mutableStateOf(false)
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
    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }
    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
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
                playback.ready = state == Player.STATE_READY
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playback.playing = isPlaying
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

/**
 * Media3 view bound to the hoisted player. With [controller] the Media3 controls (time bar, ±10 s, fullscreen
 * button toggling [onFullscreen]) drive a film or episode; live channels draw their own controls over it.
 * [onControls] follows the controller as it shows and hides; [onTap] receives whether a touch on the picture
 * (not on a control) is showing it, so a timeout hiding it is told apart from the viewer.
 */
@SuppressLint("ClickableViewAccessibility")
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    playback: Playback, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, modifier: Modifier = Modifier, controller: Boolean = true,
    onControls: (Boolean) -> Unit = {}, onTap: (Boolean) -> Unit = {}
) {
    val controls = rememberUpdatedState(onControls)
    val tap = rememberUpdatedState(onTap)
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = controller
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                if (controller) {
                    setFullscreenButtonClickListener { onFullscreen(it) }
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controls.value(it == View.VISIBLE) })
                    // Seen before the view toggles its controller on the release; touches on the controls never reach it.
                    setOnTouchListener { view, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) tap.value(!(view as PlayerView).isControllerFullyVisible)
                        false
                    }
                }
            }
        },
        update = { view ->
            view.player = playback.player
            if (controller) view.setFullscreenButtonState(fullscreen)
        },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

/** How long the live controls stay over a playing picture after the last touch. */
private const val CONTROLS_MS = 5_000L

/**
 * Live picture with its own controls, YouTube style: a touch shows or hides them, they fade out while the
 * channel plays. Top: [onMinimize], the « DIRECT » badge and [onClose]; bottom: pause, sound, [onGuide] and fullscreen.
 * Loading and errors show in the middle, with « Réessayer ». [onTap] receives whether a touch showed the controls.
 */
@Composable
fun LivePlayer(
    playback: Playback, onFullscreen: (Boolean) -> Unit, modifier: Modifier = Modifier,
    onMinimize: (() -> Unit)? = null, onGuide: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onTap: (Boolean) -> Unit = {}
) {
    var visible by remember(playback) { mutableStateOf(true) }
    LaunchedEffect(visible, playback.playing) {
        if (visible && playback.playing) { delay(CONTROLS_MS); visible = false }
    }
    Box(modifier.background(Color.Black)) {
        PlayerSurface(playback, fullscreen = false, onFullscreen = onFullscreen, modifier = Modifier.fillMaxSize(), controller = false)
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) { visible = !visible; onTap(visible) }.testTag("player-picture"))
        PlayerStatus(playback, Modifier.align(Alignment.Center))
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onMinimize != null) OverlayButton(MiptvIcons.ChevronDown, "Réduire le lecteur", onMinimize, Modifier.testTag("minimize-player"))
                    Spacer(Modifier.weight(1f))
                    LiveBadge(Modifier.padding(end = 8.dp))
                    if (onClose != null) OverlayButton(MiptvIcons.Close, "Fermer le lecteur", onClose, Modifier.testTag("close-player"))
                }
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OverlayButton(if (playback.playing) MiptvIcons.Pause else MiptvIcons.Play, if (playback.playing) "Pause" else "Lecture", playback::togglePlay, Modifier.testTag("play-pause"))
                    OverlayButton(if (playback.muted) MiptvIcons.Muted else MiptvIcons.Volume, if (playback.muted) "Rétablir le son" else "Couper le son", playback::toggleMute, Modifier.testTag("mute"))
                    Spacer(Modifier.weight(1f))
                    if (onGuide != null) OverlayButton(MiptvIcons.Calendar, "Guide des programmes", onGuide, Modifier.testTag("player-guide"))
                    OverlayButton(MiptvIcons.EnterFullscreen, "Plein écran", { onFullscreen(true) }, Modifier.testTag("enter-fullscreen"))
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, background: Color = Color.Transparent) {
    IconButton(onClick = onClick, modifier = modifier.size(size).clip(CircleShape).background(background)) {
        Icon(icon, contentDescription = label, tint = Palette.Text, modifier = Modifier.size(if (size > 56.dp) 32.dp else 24.dp))
    }
}

/** Loading, end or error over the picture; nothing once it plays. */
@Composable
private fun PlayerStatus(playback: Playback, modifier: Modifier = Modifier) {
    if (playback.ready && !playback.failed) return
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(playback.status, color = Palette.Text, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.testTag("player-status"))
        if (playback.failed) PillButton("Réessayer", playback::retry, Modifier.testTag("retry"), height = 40.dp)
    }
}

/**
 * The live player with the channel under it: logo, name, [subtitle] (category and slot), bookmark, then the
 * programme on air with its progress and what follows. Used by the TV tab and the guide sheet.
 */
@Composable
fun Preview(
    playback: Playback, favorite: Boolean, onFullscreen: (Boolean) -> Unit, nowNext: NowNext? = null, nowMs: Long = 0L,
    channel: Channel? = null, subtitle: String? = null, onMinimize: (() -> Unit)? = null, onGuide: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit
) {
    Column {
        LivePlayer(playback, onFullscreen, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onMinimize, onGuide)
        LiveInfo(playback.item.title, channel, subtitle, favorite, nowNext, nowMs, onToggleFavorite)
    }
}

@Composable
fun LiveInfo(title: String, channel: Channel?, subtitle: String?, favorite: Boolean, nowNext: NowNext?, nowMs: Long, onToggleFavorite: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (channel != null) ChannelLogo(channel, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(subtitle, nowNext?.now?.let(::formatSlot)).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FavoriteButton(favorite, Modifier.testTag("preview-favorite"), onToggleFavorite)
        }
        if (nowNext != null) Column(Modifier.testTag("preview-now"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(nowNext.now.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressLine(nowNext.now.progress(nowMs), Modifier.fillMaxWidth(), height = 4.dp)
            nextLabel(nowNext)?.let { Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Player of a film or episode inside its sheet: the Media3 controller carries the time bar and ±10 s; [onNext] plays the next episode. */
@Composable
fun VodPlayer(playback: Playback, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit, onNext: (() -> Unit)?) {
    Column(Modifier.testTag("vod-player")) {
        PlayerSurface(playback, fullscreen = false, onFullscreen = onFullscreen, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(playback.item.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    playback.item.subtitle?.let { Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = { onFullscreen(true) }, modifier = Modifier.testTag("enter-fullscreen")) { Icon(MiptvIcons.EnterFullscreen, contentDescription = "Plein écran") }
                IconButton(onClick = onClose, modifier = Modifier.testTag("close-player")) { Icon(MiptvIcons.Close, contentDescription = "Fermer le lecteur") }
            }
            if (!playback.ready || playback.failed || playback.ended) Text(playback.status, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.testTag("player-status"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playback.failed) PillButton("Réessayer", playback::retry, Modifier.testTag("retry"), height = 40.dp)
                if (onNext != null) PillButton("Épisode suivant", onNext, Modifier.testTag("next-episode"), icon = MiptvIcons.Next, primary = playback.ended, height = 40.dp)
            }
        }
    }
}

/**
 * A film or episode with the phone turned sideways: the picture fills the tab above the tab bar, as the TV player
 * does. The Media3 controller drives it; the title and the close button come and go with it, and [onTap]
 * receives whether a touch showed it. Errors and the next episode show in the middle, as in fullscreen.
 */
@Composable
fun SidewaysVodPlayer(playback: Playback, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit, onNext: (() -> Unit)?, onTap: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var controls by remember(playback) { mutableStateOf(true) }
    Box(modifier.background(Color.Black).testTag("vod-player")) {
        PlayerSurface(playback, fullscreen = false, onFullscreen = onFullscreen, modifier = Modifier.fillMaxSize(), onControls = { controls = it }, onTap = onTap)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playback.item.title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    playback.item.subtitle?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                OverlayButton(MiptvIcons.Close, "Fermer le lecteur", onClose, Modifier.testTag("close-player"))
            }
        }
        if (playback.failed || (playback.ended && onNext != null)) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(playback.status, color = Color.White, modifier = Modifier.testTag("player-status"))
                if (playback.failed) PillButton("Réessayer", playback::retry, Modifier.testTag("retry"), height = 40.dp)
                if (playback.ended && onNext != null) PillButton("Épisode suivant", onNext, Modifier.testTag("next-episode"), icon = MiptvIcons.Next, height = 40.dp)
            }
        }
    }
}

/**
 * Landscape, system bars hidden by the activity; back or the close button returns to the tab. A live channel
 * gets its own controls (previous and next channel of the list, pause, sound, guide); a film or episode keeps
 * the Media3 controller and [onNext] plays the next episode.
 */
@Composable
fun FullscreenPlayer(
    playback: Playback, onExit: () -> Unit, onNext: (() -> Unit)? = null, nowNext: NowNext? = null, nowMs: Long = 0L,
    onPreviousChannel: (() -> Unit)? = null, onNextChannel: (() -> Unit)? = null, onGuide: (() -> Unit)? = null
) {
    if (playback.item.live) { LiveFullscreen(playback, onExit, nowNext, nowMs, onPreviousChannel, onNextChannel, onGuide); return }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen-player")) {
        PlayerSurface(playback, fullscreen = true, onFullscreen = { if (!it) onExit() }, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, modifier = Modifier.testTag("exit-fullscreen")) { Icon(MiptvIcons.Back, contentDescription = "Quitter le plein écran", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(playback.item.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                playback.item.subtitle?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, maxLines = 1) }
            }
        }
        if (playback.failed || (playback.ended && onNext != null)) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(playback.status, color = Color.White)
                if (playback.failed) PillButton("Réessayer", playback::retry, height = 40.dp)
                if (playback.ended && onNext != null) PillButton("Épisode suivant", onNext, icon = MiptvIcons.Next, height = 40.dp)
            }
        }
    }
}

@Composable
private fun LiveFullscreen(
    playback: Playback, onExit: () -> Unit, nowNext: NowNext?, nowMs: Long,
    onPreviousChannel: (() -> Unit)?, onNextChannel: (() -> Unit)?, onGuide: (() -> Unit)?
) {
    var visible by remember(playback) { mutableStateOf(true) }
    LaunchedEffect(visible, playback.playing) {
        if (visible && playback.playing) { delay(CONTROLS_MS); visible = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen-player")) {
        PlayerSurface(playback, fullscreen = true, onFullscreen = { if (!it) onExit() }, modifier = Modifier.fillMaxSize(), controller = false)
        Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) { visible = !visible })
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OverlayButton(MiptvIcons.Back, "Quitter le plein écran", onExit, Modifier.testTag("exit-fullscreen"))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(playback.item.title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (nowNext != null) Text("${nowNext.now.title} · ${formatSlot(nowNext.now)}", color = Palette.Muted, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("fullscreen-now"))
                    }
                    LiveBadge()
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dim = Palette.Ink.copy(alpha = 0.6f)
                    // Missing neighbours keep their place so pause stays in the middle.
                    if (onPreviousChannel != null) OverlayButton(MiptvIcons.Previous, "Chaîne précédente", onPreviousChannel, Modifier.testTag("previous-channel"), 56.dp, dim)
                    else Spacer(Modifier.size(56.dp))
                    if (playback.ready || playback.playing) OverlayButton(if (playback.playing) MiptvIcons.Pause else MiptvIcons.Play, if (playback.playing) "Pause" else "Lecture", playback::togglePlay, size = 72.dp, background = dim)
                    else Spacer(Modifier.size(72.dp))
                    if (onNextChannel != null) OverlayButton(MiptvIcons.Next, "Chaîne suivante", onNextChannel, Modifier.testTag("next-channel"), 56.dp, dim)
                    else Spacer(Modifier.size(56.dp))
                }
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .padding(start = 20.dp, end = 12.dp, bottom = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (nowNext != null) {
                        ProgressLine(nowNext.now.progress(nowMs), Modifier.width(240.dp), height = 4.dp, track = Palette.Text.copy(alpha = 0.25f))
                        nextLabel(nowNext)?.let { Text(it, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                            ?: Spacer(Modifier.weight(1f))
                    } else Spacer(Modifier.weight(1f))
                    OverlayButton(if (playback.muted) MiptvIcons.Muted else MiptvIcons.Volume, if (playback.muted) "Rétablir le son" else "Couper le son", playback::toggleMute)
                    if (onGuide != null) OverlayButton(MiptvIcons.Calendar, "Guide des programmes", onGuide, Modifier.testTag("fullscreen-guide"))
                    OverlayButton(MiptvIcons.ExitFullscreen, "Quitter le plein écran", onExit)
                }
            }
        }
        PlayerStatus(playback, Modifier.align(Alignment.Center))
    }
}
