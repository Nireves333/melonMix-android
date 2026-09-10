package me.magnum.melonds.ui.emulator.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.emulator.KhCutsceneState
import java.io.File

/**
 * [KHMM] HD replacement cutscene video player. Covers the whole emulator surface with the video
 * (desktop swaps the GL panel for a QMediaPlayer view; here the muted emulator keeps running
 * hidden behind this overlay, fast-forwarding through its own prerendered cutscene). The KHMM
 * cutscene videos are all 16:9 (desktop CutsceneVideoView hardcodes the same), so the video box
 * letterboxes inside whatever the screen gives us.
 *
 * [paused] pauses playback while the cutscene skip menu is open over the video (desktop:
 * windowPauseVideo / windowUnpauseVideo) or while the emulator core is paused (app pause menu,
 * save-state wraps — keeps video and game state from drifting apart). The video also pauses
 * while the activity is backgrounded; that path is driven by a lifecycle observer with direct
 * player calls because Compose's frame clock stops once the activity does, so a recomposition
 * can't be relied on to deliver the pause. [onEnded] / [onFailed] must be reported back to the
 * emulator so the plugin can resume the game (or blacklist the video and fall back to the DS
 * cutscene).
 */
@Composable
fun KhCutscenePlayerUi(
    state: KhCutsceneState?,
    paused: Boolean,
    onEnded: () -> Unit,
    onFailed: (String) -> Unit,
) {
    if (state == null) {
        return
    }

    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentPaused by rememberUpdatedState(paused)

    // [KHMM] subtitles: the plugin resolves the .srt for the firmware language (with English
    // fallback) and sends its path along with the video path; empty/missing = no subtitles
    // (desktop CutsceneVideoView::loadSubtitles). Sideloaded into ExoPlayer as a default-
    // selected SubRip track; the cues come back through Player.Listener.onCues.
    val player = remember(state.videoPath) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(File(state.videoPath)))
            val subtitlesFile = File(state.subtitlesPath)
            if (state.subtitlesPath.isNotEmpty() && subtitlesFile.isFile) {
                mediaItem.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitlesFile))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }
            setMediaItem(mediaItem.build())
            prepare()
            playWhenReady = true
        }
    }
    var subtitleText by remember(player) { mutableStateOf("") }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnFailed(error.errorCodeName)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleText = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString("\n")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember { mutableStateOf(true) }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleResumed = false
                    player.playWhenReady = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                    player.playWhenReady = !currentPaused
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(player, paused, lifecycleResumed) {
        player.playWhenReady = lifecycleResumed && !paused
    }

    // [KHMM] remastered-BGM: a couple of BGM cues are scheduled as an offset from the start
    // of the playing video (desktop: delayAtStart - player->position()), so keep the native
    // side's idea of the video position fresh while a video is up. Reset on teardown so a
    // later cue can't compute against a stale position.
    LaunchedEffect(player) {
        try {
            while (true) {
                MelonEmulator.setKhBgmVideoPosition(player.currentPosition)
                delay(250)
            }
        } finally {
            MelonEmulator.setKhBgmVideoPosition(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(modifier = Modifier.aspectRatio(16f / 9f)) {
            val videoHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            AndroidView(
                factory = { SurfaceView(it) },
                update = { player.setVideoSurfaceView(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (subtitleText.isNotEmpty()) {
                KhSubtitleOverlay(
                    text = subtitleText,
                    videoHeightPx = videoHeightPx,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * [KHMM] Active subtitle line(s), mirroring desktop CutsceneVideoView's paintSubtitle: white
 * fill over a rounded black outline in the KHMM Comic-Sans-style font, sized against the 16:9
 * video box, centered near its bottom with multi-line cues stacking upward. Glyphs the font
 * lacks (Japanese) fall through to the system font.
 */
@Composable
private fun KhSubtitleOverlay(text: String, videoHeightPx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontPx = videoHeightPx * 0.049f
    val style = TextStyle(
        fontFamily = FontFamily(Font(R.font.kh_comic_hearts)),
        fontSize = with(density) { fontPx.toSp() },
        letterSpacing = with(density) { (fontPx / 16f).toSp() },
        textAlign = TextAlign.Center,
    )
    // Desktop anchors the bottom line's baseline at 89% of the video height; the ~9%
    // bottom padding puts the glyph bottoms in the same place.
    Box(modifier = modifier.padding(bottom = with(density) { (videoHeightPx * 0.09f).toDp() })) {
        Text(
            text = text,
            style = style.copy(
                color = Color.Black,
                drawStyle = Stroke(width = fontPx * 0.15f, join = StrokeJoin.Round, cap = StrokeCap.Round),
            ),
        )
        Text(text = text, style = style.copy(color = Color.White))
    }
}
