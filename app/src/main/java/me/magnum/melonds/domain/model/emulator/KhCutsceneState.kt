package me.magnum.melonds.domain.model.emulator

/**
 * [KHMM] An HD replacement cutscene the frontend should be playing. While the video plays, the
 * emulator keeps running muted and hidden behind it (fast-forwarding through its own prerendered
 * cutscene); the frontend must report back when the video ends or fails
 * ([me.magnum.melonds.MelonEmulator.onKhCutsceneEnded] / [me.magnum.melonds.MelonEmulator.onKhCutsceneFailed]).
 */
data class KhCutsceneState(
    val videoPath: String,
    val subtitlesPath: String,
)
