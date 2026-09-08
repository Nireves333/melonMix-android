package me.magnum.melonds.ui.emulator

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import me.magnum.melonds.R

/**
 * [KHMM] Plays the KH cutscene skip menu sounds (desktop: MainWindowSettings::createMenuSounds
 * plays the same wavs through a dedicated SDL audio device). The DS audio is muted while an HD
 * replacement video plays, so these are the only feedback the skip menu has. Sound ids follow
 * the plugin's _CutsceneMenuSoundRequest: 1=enter, 2=move, 3=continue, 4=select.
 */
class KhMenuSoundPlayer(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedSamples = mutableSetOf<Int>()

    // Indexed by plugin sound id; 0 is unused
    private val samples = intArrayOf(
        0,
        soundPool.load(context, R.raw.kh_sfx_menu_enter, 1),
        soundPool.load(context, R.raw.kh_sfx_menu_move, 1),
        soundPool.load(context, R.raw.kh_sfx_menu_continue, 1),
        soundPool.load(context, R.raw.kh_sfx_menu_select, 1),
    )

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                synchronized(loadedSamples) {
                    loadedSamples.add(sampleId)
                }
            }
        }
    }

    fun play(soundId: Int) {
        val sample = samples.getOrNull(soundId)?.takeIf { it != 0 } ?: return
        val isLoaded = synchronized(loadedSamples) { sample in loadedSamples }
        if (isLoaded) {
            soundPool.play(sample, 1f, 1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}
