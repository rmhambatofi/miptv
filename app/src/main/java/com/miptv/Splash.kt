package com.miptv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** How long the opening screen stays before Accueil. */
const val SPLASH_MS = 2_000L

/** Opening screen: the logo, a small equaliser, the tagline and a loading line, with the opening sound. */
@Composable
fun Splash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.Default) { playOpeningSound() }
        progress.animateTo(1f, tween(SPLASH_MS.toInt(), easing = LinearEasing))
        onDone()
    }
    val beat = rememberInfiniteTransition(label = "equaliser")
    val swing by beat.animateFloat(0.55f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "swing")
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1B2130), Palette.Ink), radius = 1100f)).testTag("splash"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "miptv" }) {
                Text("m", style = TextStyle(color = Palette.Accent, fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp,
                    shadow = Shadow(Palette.Accent.copy(alpha = 0.6f), Offset(0f, 0f), 30f)))
                Text("IPTV", style = TextStyle(color = Palette.Text, fontSize = 68.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                    shadow = Shadow(Color(0xFF9AA0B4), Offset(0f, 8f), 0f)))
            }
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(8 to Palette.Dim, 16 to Palette.Muted, 22 to Palette.Accent, 14 to Palette.Muted, 6 to Palette.Dim).forEachIndexed { index, (height, color) ->
                    val scale = if (index % 2 == 0) swing else 1.55f - swing
                    Box(Modifier.width(4.dp).height((height * scale).coerceIn(4f, 22f).dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
            Text("Votre cinéma de poche", color = Palette.Muted, fontSize = 14.sp, letterSpacing = 1.sp)
        }
        ProgressLine(progress.value, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp).width(120.dp))
    }
}

/**
 * A short two-note chime, synthesised rather than shipped as a file: a rising fifth with a soft attack and
 * an exponential decay, at a moderate level on the media stream.
 */
private suspend fun playOpeningSound() {
    val rate = 44_100
    val notes = listOf(659.25 to 0.0, 987.77 to 0.16) // E5 then B5
    val length = (rate * 1.1).toInt()
    val pcm = ShortArray(length)
    for (i in 0 until length) {
        val t = i.toDouble() / rate
        var sample = 0.0
        for ((frequency, start) in notes) {
            val local = t - start
            if (local < 0) continue
            val envelope = minOf(1.0, local / 0.012) * exp(-local * 4.2)
            sample += envelope * (sin(2 * PI * frequency * local) + 0.25 * sin(4 * PI * frequency * local))
        }
        pcm[i] = (sample * 0.22 * Short.MAX_VALUE).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    val track = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
    }.getOrNull() ?: return
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        delay(length * 1000L / rate + 100)
    } catch (_: Exception) {
        // No sound is better than a crash on a device without an audio output.
    } finally {
        track.release()
    }
}
