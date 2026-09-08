#ifndef MELONINSTANCE_H
#define MELONINSTANCE_H

#include <atomic>
#include <string>
#include "Args.h"
#include "Configuration.h"
#include "NDS.h"
#include "MelonDS.h"
#include "SaveManager.h"
#include "RewindManager.h"
#include "renderer/FrameQueue.h"
#include "renderer/Renderer.h"
#include "renderer/ScreenshotRenderer.h"
#include "retroachievements/RetroAchievementsManager.h"
#include "net/Net.h"
#include "plugins/PluginManager.h" // [KHMM] KH Melon Mix plugin system

using namespace melonDS;

namespace MelonDSAndroid
{

class MelonInstance
{

public:
    MelonInstance(int instanceId, std::shared_ptr<EmulatorConfiguration> configuration, std::unique_ptr<melonDS::NDSArgs> args, std::shared_ptr<Net> net, std::unique_ptr<ScreenshotRenderer> screenshotRenderer, int consoleType);
    ~MelonInstance();

    int getInstanceId() { return instanceId; };

    bool loadRom(std::string romPath, std::string sramPath);
    bool loadGbaRom(std::string romPath, std::string sramPath);
    void loadRumblePak();
    void loadGbaMemoryExpansion();
    void loadMotionPakHomebrew();
    void loadMotionPakRetail();
    bool bootFirmware();
    void start();
    void reset();
    melonDS::u32 runFrame();
    void stop();

    void updateMotionData(float ax, float ay, float az, float rx, float ry, float rz);
    float getMotionData(MotionQueryType type);
    void touchScreen(u16 x, u16 y);
    void releaseScreen();
    void pressKey(u32 key);
    void releaseKey(u32 key);
    int readAudioOutput(s16* buffer, int length);
    void setAudioOutputSkew(double skew);
    bool takeScreenshot();
    void loadCheats(std::list<Cheat> cheats);
    int sendNetPacket(u8* data, int length);
    int receiveNetPacket(u8* data);

    Frame* getPresentationFrame(std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline);

    void updateConfiguration(std::shared_ptr<EmulatorConfiguration> newConfiguration);
    // [KHMM] real aspect ratio of the on-screen top-screen viewport (UI thread -> emu thread)
    void setDisplayAspectRatio(float aspectRatio) { khAspectRatio.store(aspectRatio, std::memory_order_relaxed); }
    // [KHMM] HD-cutscene video player returns (called from the UI thread over JNI, mirroring
    // desktop where the Qt GUI thread calls straight into the plugin; see MelonDS.h)
    void khCutsceneEnded();
    void khCutsceneFailed(std::string error);
    // [KHMM] a save state was loaded while an HD replacement video plays — arm the plugin's
    // skip sequence so the video stops and the game drops straight into the loaded state
    void khStateLoadedDuringCutscene();
    // [KHMM] runs the plugin's cutscene-menu input processing while the emu loop is parked
    // waiting for the HD video to finish (khEmuHoldForCutscene) — without this the
    // Continue/Skip menu goes dead as soon as the hidden DS cutscene ends, because the
    // input hook normally only runs inside runFrame. Called from the emulate() hold branch
    // (same emu thread as runFrame, so no new concurrency).
    void khCutsceneHoldTick();
    void requestNdsSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestGbaSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestFirmwareSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    bool saveState(Savestate* state);
    bool loadState(Savestate* state);
    RewindWindow getRewindWindow();
    bool loadRewindState(RewindSaveState rewindSaveState);
    void setupAchievements(
        std::list<RetroAchievements::RAAchievement> achievements,
        std::list<RetroAchievements::RALeaderboard> leaderboards,
        std::optional<std::string> richPresenceScript
    );
    void unloadRetroAchievementsData();
    std::string getRichPresenceStatus();
    std::vector<RetroAchievements::RARuntimeAchievement> getRuntimeAchievements();

private:
    void updateRenderer();
    void setBatteryLevels();
    void setDateTime();
    void saveRewindState(RewindSaveState* rewindSaveState);
    void loadPlugin(u32 gameCode); // [KHMM] (re)create the KH plugin for the loaded game
    // [KHMM] pack the plugin's pause-menu overlay state (title/subtitle/labels/selection)
    // into an emulator event so the Kotlin frontend can draw the overlay. The desktop KHMM
    // frontend draws this menu as a Qt widget (PauseMenuOverlay); the composite deliberately
    // hides the game's own pause menu, so without a frontend overlay the menu is invisible.
    // cutsceneMenuSelection >= 0 means this is the cutscene skip menu (Continue/Skip over a
    // playing HD video, selection passed by the trio callbacks); -1 means the game pause menu.
    void khFirePauseMenuEvent(bool visible, int cutsceneMenuSelection = -1);
    // [KHMM] tell the frontend to start (playing=true, with file paths) or dismiss the HD
    // replacement cutscene video player (desktop: windowStartVideo / windowStopVideo)
    void khFireCutsceneEvent(bool playing, const std::string& videoPath = std::string(),
                             const std::string& subtitlesPath = std::string());

private:
    int instanceId;
    int consoleType;
    NDS* nds;
    std::shared_ptr<Net> net;

    // [KHMM] active game plugin. Never null after construction (PluginDefault fallback).
    // khEnhancedGraphics comes from the user's "enable_enhanced_graphics" setting (applied in
    // the constructor and in updateConfiguration); it gates the per-frame plugin driver, the
    // composite FS, and the polygon hook at runtime, so toggling mid-session works both ways.
    // The HD texture-replacement path stays forced off in loadPlugin (separate feature chunk).
    Plugins::Plugin* plugin = nullptr;
    bool khEnhancedGraphics = true;
    // [KHMM] whether the frontend pause-menu overlay is currently shown (last snapshot sent);
    // used to retract it when enhanced graphics is toggled off mid-menu
    bool khPauseMenuShown = false;
    // [KHMM-DBG] last cutscene-detection result, for transition logging in runFrame
    Plugins::CutsceneEntry* khDbgLastCutscene = nullptr;
    // [KHMM] target display aspect ratio pushed into the plugin each frame (single-screen
    // presentation). Set from the real on-screen top-screen viewport by the frontend
    // (EmulatorActivity.updateRendererScreenAreas -> JNI); written on the UI thread, read
    // on the emu thread -> atomic.
    std::atomic<float> khAspectRatio { 16.0f / 9.0f };

    std::atomic<float> motionData[6] = { 0.0f, 0.0f, 9.80665f, 0.0f, 0.0f, 0.0f };

    std::unique_ptr<RetroAchievements::RetroAchievementsManager> retroAchievementsManager;
    std::unique_ptr<SaveManager> ndsSave;
    std::unique_ptr<SaveManager> gbaSave;
    std::unique_ptr<SaveManager> firmwareSave;
    u32 inputMask;

    std::shared_ptr<EmulatorConfiguration> currentConfiguration;
    FrameQueue frameQueue;
    std::unique_ptr<ScreenshotRenderer> screenshotRenderer;
    RewindManager rewindManager;
    Renderer currentRenderer;
    bool isRenderConfigurationDirty;
    int frame;
};

}

#endif
