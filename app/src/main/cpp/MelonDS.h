#ifndef MELONDS_MELONDS_H
#define MELONDS_MELONDS_H

#include <atomic>
#include <list>
#include <vector>
#include "AndroidFileHandler.h"
#include "AndroidCameraHandler.h"
#include "Configuration.h"
#include "MelonEventMessenger.h"
#include "RewindManager.h"
#include "RomGbaSlotConfig.h"
#include "retroachievements/RAAchievement.h"
#include "retroachievements/RALeaderboard.h"
#include "renderer/FrameQueue.h"
#include "types.h"
#include "../GPU.h"
#include <android/asset_manager.h>

using namespace melonDS;

namespace MelonDSAndroid {
    typedef struct {
        std::vector<u32> code;
    } Cheat;

    typedef enum {
        ROM,
        FIRMWARE
    } RunMode;

    extern OpenGLContext *openGlContext;
    extern AndroidFileHandler* fileHandler;
    extern AndroidCameraHandler* cameraHandler;
    extern std::string internalFilesDir;
    extern std::shared_ptr<MelonEventMessenger> eventMessenger;

    extern void setConfiguration(EmulatorConfiguration emulatorConfiguration);
    extern void setup(AndroidCameraHandler* androidCameraHandler, std::shared_ptr<MelonEventMessenger> androidEventMessenger, u32* screenshotBufferPointer, int instanceId);
    extern void setCodeList(std::list<Cheat> cheats);
    extern void setupAchievements(std::list<RetroAchievements::RAAchievement> achievements, std::list<RetroAchievements::RALeaderboard> leaderboards, std::optional<std::string> richPresenceScript);
    extern void unloadRetroAchievementsData();
    extern std::string getRichPresenceStatus();
    extern std::vector<RetroAchievements::RARuntimeAchievement> getRuntimeAchievements();
    extern void updateEmulatorConfiguration(std::unique_ptr<EmulatorConfiguration> emulatorConfiguration);
    // [KHMM] real on-screen top-screen viewport aspect (single-screen composite); safe pre-boot
    extern void setDisplayAspectRatio(float aspectRatio);
    // [KHMM] does the KH Melon Mix plugin system support this gamecode? (static query, no instance)
    extern bool isEnhancedGameCode(u32 gameCode);

    // [KHMM] HD replacement cutscenes (desktop: EmuThread.cpp:896-956). While a replacement
    // video plays, the emulator fast-forwards through its own prerendered cutscene hidden
    // behind the video (khCutsceneFastForward bypasses the frame limiter — the plugin's
    // end-of-cutscene handshake needs the DS advancing), and once the DS cutscene finishes
    // before the video does, the emu loop parks (khEmuHoldForCutscene) until the video ends.
    // Both are written from plugin callbacks / the emu thread and read by the emulate() loop.
    extern std::atomic_bool khCutsceneFastForward;
    extern std::atomic_bool khEmuHoldForCutscene;
    // [KHMM] frontend -> plugin returns for the video player (desktop: MainWindowSettings
    // stopVideo/cancelVideo): natural end of the video, and playback failure.
    extern void khCutsceneEnded();
    extern void khCutsceneFailed(std::string error);
    // [KHMM] a save state was loaded while the video plays — skip the video (see MelonInstance)
    extern void khStateLoadedDuringCutscene();
    // [KHMM] cutscene-menu input processing for the parked emu loop (see MelonInstance)
    extern void khCutsceneHoldTick();
    // [KHMM] refined controls: KH addon key press state + camera stick axes (see MelonInstance)
    extern void khSetAddonKey(int action, bool down);
    extern void khSetCameraAxes(float x, float y);
    // [KHMM] user camera-stick speed in HALF-UNITS (2-8 = 1.0-4.0 in 0.5 steps; desktop's
    // <root>.CameraSensitivity is an integer SHIFT count 1-4, so half-steps are synthesized:
    // shift = ceil(v/2) served to loadConfigs, odd v scales the stick nibble range to 75%).
    // 0 = unset, keep the plugin's config/default value. A global (not per-instance) so the
    // pref observer can set it regardless of ROM-load ordering; loadConfigs is re-run on
    // the emu thread via shouldInvalidateConfigs when the setter fires.
    extern std::atomic_int khCameraSensitivity;
    extern void khSetCameraSensitivity(int sensitivity);

    // [KHMM] show subtitles over HD replacement cutscenes. Served to Plugin::loadConfigs
    // inverted as the "<root>.DisableSubtitles" bool config (desktop PluginSettingsDialog
    // checkbox); the plugin resolves the .srt path when a cutscene starts, so a change
    // applies from the next cutscene. Global for the same ordering reason as above.
    extern std::atomic_bool khShowSubtitles;
    extern void khSetShowSubtitles(bool show);

    // [KHMM] remastered-BGM audio pack names (subfolders of assets/<game>/audio/), served
    // to Plugin::loadConfigs as the ".AudioPack" string config at ROM load. Set from JNI
    // before the ROM loads; empty = no pack (files at the audio/ root still resolve,
    // desktop parity).
    extern std::string khBgmAudioPackDays;
    extern std::string khBgmAudioPackRecoded;

    /**
     * Loads the NDS ROM and, optionally, the GBA ROM.
     *
     * @param romPath The path to the NDS rom
     * @param sramPath The path to the rom's SRAM file
     * @param gbaSlotConfig The config to be used for the GBA slot
     * @return The load result. 0 if everything was loaded successfully, 1 if the NDS ROM was loaded but the GBA ROM
     * failed to load, 2 if the NDS ROM failed to load
     */
    extern int loadRom(std::string romPath, std::string sramPath, RomGbaSlotConfig* gbaSlotConfig);
    extern int bootFirmware();
    extern void touchScreen(u16 x, u16 y);
    extern void releaseScreen();
    extern void pressKey(u32 key);
    extern void releaseKey(u32 key);
    extern void updateMotionData(float ax, float ay, float az, float rx, float ry, float rz);
    extern void start();
    extern u32 loop();
    extern Frame* getPresentationFrame(std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline);
    extern void pause();
    extern void resume();
    extern void reset();
    extern bool saveState(const char* path);
    extern bool loadState(const char* path);
    extern bool loadRewindState(melonDS::RewindSaveState rewindSaveState);
    extern RewindWindow getRewindWindow();
    extern bool takeScreenshot();
    extern void stop();
    extern void cleanup();
}

#endif //MELONDS_MELONDS_H
