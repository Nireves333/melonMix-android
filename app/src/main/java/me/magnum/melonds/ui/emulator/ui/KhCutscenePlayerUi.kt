package me.magnum.melonds.ui.emulator.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    val player = remember(state.videoPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(state.videoPath))))
            prepare()
            playWhenReady = true
        }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { SurfaceView(it) },
            update = { player.setVideoSurfaceView(it) },
            modifier = Modifier.aspectRatio(16f / 9f),
        )
    }
}
