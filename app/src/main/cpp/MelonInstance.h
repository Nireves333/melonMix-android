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
    void khPollDebugControls(); // [KHMM-DBG] read runtime perf toggles from the debug control file
    void khReportPerf();        // [KHMM-DBG] log the per-stage timing window and reset accumulators

private:
    int instanceId;
    int consoleType;
    NDS* nds;
    std::shared_ptr<Net> net;

    // [KHMM] active game plugin. Never null after construction (PluginDefault fallback).
    // Step B: the composite shader is now ported to GLES 320es, so enhanced graphics
    // (single-screen compositing) is enabled by default. A user-facing toggle
    // (config -> JNI -> Kotlin) is a follow-up; the HD texture-replacement path stays
    // forced off in loadPlugin as its own separate feature chunk.
    Plugins::Plugin* plugin = nullptr;
    bool khEnhancedGraphics = true;
    // [KHMM] target display aspect ratio pushed into the plugin each frame (single-screen
    // presentation). 16:9 for now; Step C inc.2 sources this from the on-screen viewport.
    float khAspectRatio = 16.0f / 9.0f;

    // [KHMM-DBG] runtime perf instrumentation (temporary; grep [KHMM-DBG] to remove).
    // Isolates the single-screen composite's cost so we can decide if the feature set is
    // realistic on the RG505. Live toggles are read from <internalFilesDir>/melonmix_debug.txt
    // (rooted device -> adb-writable) so each A/B is a one-line echo, not a rebuild. Timings
    // are logged every kDbgReportFrames frames via LOG_INFO(tag "MelonMixPerf").
    bool khDbgFovWiden = true;    // gate the widescreen FOV RAM write in setAspectRatio
    bool khDbgPolyHook = true;    // gate the per-polygon rewrite hook (plugin->ApplyPolygonChanges)
    bool khDbgCompositeFS = true; // gate the plugin composite FS vs stock nearest FS (GL only)
    long khDbgLastPollFrame = -1000;
    // accumulated timings over the current report window (nanoseconds)
    uint64_t khStatRefreshNs = 0;
    uint64_t khStatBuildNs = 0;
    uint64_t khStatRunFrameNs = 0;
    int khStatFrames = 0;
    std::chrono::steady_clock::time_point khStatWallStart{};

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
