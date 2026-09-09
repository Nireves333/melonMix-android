#ifndef KHBGMPLAYER_H
#define KHBGMPLAYER_H

// [KHMM] Remastered-BGM replacement player (desktop: MainWindowSettings BGM slots +
// melonMix::AudioPlayer/AudioSourceWav/AudioSourceFlac/AudioWavParser, all Qt classes —
// this is a Qt-free port of their behavior on top of oboe + dr_flac).
//
// The plugin core does ALL the detection work (Plugin::refreshBackgroundMusic reads the
// game's MIDI sequencer state from RAM and mutes the DS-side SSEQ, refreshStreamedMusic
// polls STRM headers and silences the stream) and communicates with the frontend through
// five polled flags (desktop: EmuThread.cpp:960-1000). This module is that frontend side:
// poll the flags once per frame on the emu thread and play the replacement files.
//
// Files are the KHMM audio packs: WAV (16-bit PCM with an optional `smpl` chunk holding
// sample-accurate loop points) and FLAC (LOOPSTART/LOOPEND vorbis comments). A track loops
// between its loop points forever until the game's state machine says stop; a file without
// loop metadata loops whole (desktop parity).

#include <cstdint>

namespace Plugins {
    class Plugin;
}

namespace KhBgm {

// Poll the plugin's five BGM flags and drive the player accordingly. Call once per frame
// from the emu thread right after Plugin::refreshGameScene (which is what raises the
// flags). Also runs tick().
void pollPlugin(Plugins::Plugin* plugin);

// Advance time-based work: the delayed-start countdown (a BGM start synced to a point
// inside an HD cutscene video) and reaping of voices whose fade-out finished. Called from
// pollPlugin, and must ALSO be called from the parked emu loop's hold tick — during an HD
// video the emu loop parks once the hidden DS cutscene ends, runFrame stops, and a delayed
// start (e.g. the 376s "Dearly Beloved (Reprise)" cue in the end credits) would never fire.
void tick();

// Emulator lifecycle. Pause/resume mirror the emulator's own pause choke point so the
// music never keeps playing behind the home screen or the pause menu; the plugin's own
// pause request (the in-game pause hotkey, desktop Plugin::togglePause) is tracked
// separately — a voice plays only when neither side holds it paused.
void pauseForEmulator();
void resumeForEmulator();

// Stop everything immediately (emu stop/reset) or with a fade (enhanced graphics turned
// off mid-session — the plugin goes inert and would otherwise never deliver a stop flag).
// Also clears the resume-position slot and any pending delayed start.
void stopAll(int fadeOutMs);

// User BGM volume, 0-100 (desktop: the Audio.BGMVolume config read by getBgmMusicVolume).
// Applied to running voices with a short ramp.
void setVolumePercent(int volumePercent);

// Current HD-cutscene video position, pushed from the Kotlin ExoPlayer while a video
// plays. Only used to schedule the two delayed starts (desktop measures its start delay
// "as offset from the beginning of the currently playing movie").
void setVideoPositionMs(int64_t positionMs);

} // namespace KhBgm

#endif // KHBGMPLAYER_H
