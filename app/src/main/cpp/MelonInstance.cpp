#include <ctime>
#include <chrono>
#include <cstdint>
#include <string>
#include <algorithm> // [KHMM] std::min in khFirePauseMenuEvent
#include <vector>    // [KHMM] event payload packing
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <filesystem>
#include <GLES3/gl3.h>
#include "Args.h"
#include "GPU3D_Compute.h"
#include "GPU2D_Soft.h" // [KHMM] for GPU2D::SoftRenderer::setPlugin
#include "plugins/PluginDefault.h" // [KHMM] direct construction for the gameCode-0 placeholder
#include "KhBgmPlayer.h" // [KHMM] remastered-BGM replacement player
#include "Configuration.h"
#include "DSi.h"
#include "DSiSupport.h"
#include "DSi_I2C.h"
#include "GPU3D_OpenGL.h"
#include "MelonDS.h"
#include "MelonInstance.h"
#include "NDS.h"
#include "NDSCart.h"
#include "net/Net_Slirp.h"
#include "Platform.h"
#include "SDCardArgsBuilder.h"
#include "AndroidMelonEventMessenger.h" // [KHMM] EVENT_KH_* pause-menu overlay events
#include "EmulatorMessageQueueJNI.h"    // [KHMM] fireEmulatorEvent
#include "MelonLog.h"                   // [KHMM] plugin OSD messages -> logcat
#include "OboeCallback.h"               // [KHMM] khMuteDsAudio during HD cutscenes

using namespace std;
using namespace melonDS;
using namespace melonDS::Platform;

namespace MelonDSAndroid
{

const int kRewindBufferSize = 1024 * 1024 * 20; // Use 20MB per savestate
const int kRewindScreenshotSize = 256 * 384 * 4;

// [KHMM] Refined controls. The app-side action ordinals (bits in khAddonHeld) are defined
// by this table's order — it is the contract with Kotlin's Input enum (KH_* entries).
// The plugin bit for each action is game-specific (Days and Re:Coded number their addon
// keys differently), resolved at plugin load via customKeyIndexByName. Actions a game
// doesn't have (or a non-KH game) resolve to -1 and are dropped.
static const char* const kKhAddonKeyNames[] = {
    "HK_LSwitchTarget",       // 0 KH_SWITCH_TARGET_LEFT
    "HK_RSwitchTarget",       // 1 KH_SWITCH_TARGET_RIGHT
    "HK_RLockOn",             // 2 KH_LOCK_ON
    "HK_CommandMenuLeft",     // 3 KH_COMMAND_MENU_LEFT
    "HK_CommandMenuRight",    // 4 KH_COMMAND_MENU_RIGHT
    "HK_CommandMenuUp",       // 5 KH_COMMAND_MENU_UP
    "HK_CommandMenuDown",     // 6 KH_COMMAND_MENU_DOWN
    "HK_HUDToggle",           // 7 KH_HUD_TOGGLE
    "HK_FullscreenMapToggle", // 8 KH_FULLSCREEN_MAP_TOGGLE
};
static_assert(sizeof(kKhAddonKeyNames) / sizeof(kKhAddonKeyNames[0]) == MelonInstance::kKhAddonActionCount,
              "addon key name table must match the action count");

MelonInstance::MelonInstance(int instanceId, std::shared_ptr<EmulatorConfiguration> configuration, std::unique_ptr<melonDS::NDSArgs> args, std::shared_ptr<Net> net, std::unique_ptr<ScreenshotRenderer> screenshotRenderer, int consoleType) :
    instanceId(instanceId),
    currentConfiguration(configuration),
    net(net),
    screenshotRenderer(std::move(screenshotRenderer)),
    consoleType(consoleType),
    rewindManager(configuration->rewindEnabled, configuration->rewindLengthSeconds, configuration->rewindCaptureSpacingSeconds, kRewindBufferSize, kRewindScreenshotSize)
{
    // Software renderer is always used during initialisation. Actual renderer will be set of first frame run
    currentRenderer = Renderer::Software;
    isRenderConfigurationDirty = true;
    inputMask = 0xFFF;
    frame = 0;

    khEnhancedGraphics = configuration->enhancedGraphics; // [KHMM] user setting

    net->RegisterInstance(instanceId);

    if (consoleType == 1)
    {
        melonDS::DSiArgs &dsiArgs = static_cast<melonDS::DSiArgs &>(*args);
        nds = new DSi(std::move(dsiArgs), this);
    }
    else
    {
        nds = new NDS(std::move(*args), this);
    }

    if (configuration->userInternalFirmwareAndBios)
    {
        std::filesystem::path firmwarePath = MelonDSAndroid::internalFilesDir;
        firmwarePath /= "wfcsettings.bin";
        firmwareSave = std::make_unique<SaveManager>(firmwarePath);
    }
    else
    {
        std::string firmwarePathString;
        if (consoleType == 1)
            firmwarePathString = configuration->dsiFirmwarePath;
        else
            firmwarePathString = configuration->dsFirmwarePath;

        firmwareSave = std::make_unique<SaveManager>(firmwarePathString);
    }

    // All instances have a RetroAchievements manager, but only the first instance will actually load achievements
    retroAchievementsManager = std::make_unique<RetroAchievements::RetroAchievementsManager>(nds);

    nds->Reset();
    setBatteryLevels();
    setDateTime();

    // [KHMM] Ensure a plugin exists before the first frame/renderer creation, even
    // when booting firmware with no ROM. loadRom() replaces it with the game plugin.
    loadPlugin(0);
}

MelonInstance::~MelonInstance()
{
    frameQueue.clear();
    net->UnregisterInstance(instanceId);
    delete nds;
    delete plugin; // [KHMM]
}

bool MelonInstance::loadRom(std::string romPath, std::string sramPath)
{
    unique_ptr<u8[]> romData;
    unique_ptr<u8[]> sramData;
    u32 romFileLength = 0;
    u32 sramFileLength = 0;

    // ROM file loading
    Platform::FileHandle* romFile = Platform::OpenFile(romPath, FileMode::Read);
    if (!romFile)
        return false;

    u64 length = Platform::FileLength(romFile);
    if (length > 0x40000000)
    {
        Platform::CloseFile(romFile);
        return false;
    }

    romFileLength = (u32) length;
    Platform::FileRewind(romFile);
    romData = make_unique<u8[]>(romFileLength);
    size_t nread = Platform::FileRead(romData.get(), (size_t) romFileLength, 1, romFile);
    Platform::CloseFile(romFile);
    if (nread != 1)
    {
        return false;
    }

    // [KHMM] Read the NDS game code (4 bytes at header offset 0x0C) before romData is
    // moved into ParseROM; used to pick the matching game plugin.
    u32 gameCode = 0;
    if (romFileLength >= 0x10)
    {
        gameCode = (u32) romData[0x0C] | ((u32) romData[0x0D] << 8)
                 | ((u32) romData[0x0E] << 16) | ((u32) romData[0x0F] << 24);
    }

    // SRAM file loading
    FileHandle* sramFile = Platform::OpenFile(sramPath, FileMode::Read);
    if (!sramFile)
    {
        return false;
    }
    else if (!Platform::CheckFileWritable(sramPath))
    {
        return false;
    }

    sramFileLength = (u32) Platform::FileLength(sramFile);

    FileRewind(sramFile);
    sramData = std::make_unique<u8[]>(sramFileLength);
    FileRead(sramData.get(), sramFileLength, 1, sramFile);
    CloseFile(sramFile);

    NDSCart::NDSCartArgs cartargs{
        // Don't load the SD card itself yet, because we don't know if
        // the ROM is homebrew or not.
        // So this is the card we *would* load if the ROM were homebrew.
        .SDCard = std::nullopt, // getSDCardArgs("DLDI"), // TODO: Re-enable this
        .SRAM = std::move(sramData),
        .SRAMLength = sramFileLength,
    };

    auto cart = NDSCart::ParseROM(std::move(romData), romFileLength, this, std::move(cartargs));
    if (!cart)
    {
        return false;
    }

    nds->SetNDSCart(std::move(cart));
    ndsSave = std::make_unique<SaveManager>(sramPath);

    // [KHMM] Select and initialise the game plugin now that the cart is loaded.
    loadPlugin(gameCode);
    plugin->onLoadROM();

    return true;
}

bool MelonInstance::loadGbaRom(std::string romPath, std::string sramPath)
{
    unique_ptr<u8[]> romData;
    unique_ptr<u8[]> sramData = nullptr;
    u32 romFileLength = 0;
    u32 sramFileLength = 0;

    // ROM file loading
    Platform::FileHandle* romFile = Platform::OpenFile(romPath, FileMode::Read);
    if (!romFile)
        return false;

    u64 length = Platform::FileLength(romFile);
    if (length > 0x40000000)
    {
        Platform::CloseFile(romFile);
        return false;
    }

    romFileLength = length;
    Platform::FileRewind(romFile);
    romData = make_unique<u8[]>(romFileLength);
    size_t nread = Platform::FileRead(romData.get(), (size_t) romFileLength, 1, romFile);
    Platform::CloseFile(romFile);
    if (nread != 1)
    {
        return false;
    }

    FileHandle* saveFile = Platform::OpenFile(sramPath, FileMode::Read);
    if (!saveFile)
    {
        return false;
    }
    else if (!Platform::CheckFileWritable(sramPath))
    {
        return false;
    }

    sramFileLength = (u32) FileLength(saveFile);

    if (sramFileLength > 0)
    {
        FileRewind(saveFile);
        sramData = std::make_unique<u8[]>(sramFileLength);
        FileRead(sramData.get(), sramFileLength, 1, saveFile);
    }
    CloseFile(saveFile);

    auto cart = GBACart::ParseROM(std::move(romData), romFileLength, std::move(sramData), sramFileLength, this);
    if (!cart)
    {
        return false;
    }

    nds->SetGBACart(std::move(cart));
    gbaSave = std::make_unique<SaveManager>(sramPath);

    return true;
}

void MelonInstance::loadRumblePak()
{
    auto rumblePakCart = GBACart::LoadAddon(GBAAddon_RumblePak, this);
    nds->SetGBACart(std::move(rumblePakCart));
}

void MelonInstance::loadGbaMemoryExpansion()
{
    auto memoryExpansionCart = GBACart::LoadAddon(GBAAddon_RAMExpansion, this);
    nds->SetGBACart(std::move(memoryExpansionCart));
}

void MelonInstance::loadMotionPakHomebrew()
{
    auto motionPakCart = GBACart::LoadAddon(GBAAddon_MotionPakHomebrew, this);
    nds->SetGBACart(std::move(motionPakCart));
}

void MelonInstance::loadMotionPakRetail()
{
    auto motionPakCart = GBACart::LoadAddon(GBAAddon_MotionPakRetail, this);
    nds->SetGBACart(std::move(motionPakCart));
}

bool MelonInstance::bootFirmware()
{
    if (nds->NeedsDirectBoot())
        return false;

    return true;
}

void MelonInstance::start()
{
    auto cart = nds->NDSCartSlot.GetCart();
    if (nds->ConsoleType == 1 && cart != nullptr && cart->GetHeader().IsDSiWare() && !currentConfiguration->showBootScreen)
    {
        auto dsi = (DSi*) nds;
        DSiSupport::SetupDSiDirectBoot(dsi);
    }
    else if (!currentConfiguration->showBootScreen || nds->NeedsDirectBoot())
    {
        // This seems to be unused, but it's required
        std::string romName;
        nds->SetupDirectBoot(romName);
    }
    nds->Start();

    screenshotRenderer->init();
}

void MelonInstance::reset()
{
    nds->Reset();
    setBatteryLevels();
    setDateTime();

    // If there is a cart inserted, check if direct boot is required
    if (nds->GetNDSCart())
    {
        if (!currentConfiguration->showBootScreen || nds->NeedsDirectBoot())
        {
            // This seems to be unused, but it's required
            std::string romName;
            nds->SetupDirectBoot(romName);
        }
    }

    rewindManager.Reset();
    retroAchievementsManager->Reset();
    nds->Start();
}

u32 MelonInstance::runFrame()
{
    if (isRenderConfigurationDirty)
    {
        updateRenderer();
        isRenderConfigurationDirty = false;
    }

    // [KHMM] Single-screen presentation keystone. The KH plugin composites the whole
    // enhanced image into the DS *top-screen* region and expects the frontend to supply
    // the target display aspect ratio (the desktop calls Plugin::setAspectRatio from its
    // screen panel). Without it the plugin's AspectRatio stays 0, so the composite shader
    // computes 1.0/currentAspectRatio = inf and the output is corrupt. Push a widescreen
    // aspect each frame (also writes the game's internal widescreen RAM value). The
    // frontend must then present top-screen-only at this same aspect for the single-screen
    // look. TODO(Step C inc.2): source khAspectRatio from the real on-screen top-screen
    // viewport over JNI instead of hardcoding 16:9.
    // [KHMM] Enhanced graphics is OpenGL-only BY DESIGN: the composite lives in the GL
    // compositor, and running the plugin driver under the software renderer crashed in-game
    // (bug #3). The two KH games this port targets are always played on OpenGL; under any
    // other renderer the plugin stays loaded but inert (stock DS rendering).
    bool khPluginActive = khEnhancedGraphics && currentRenderer == Renderer::OpenGl;

    if (khPluginActive && plugin != nullptr && plugin->isReady())
    {
        plugin->setAspectRatio(khAspectRatio.load(std::memory_order_relaxed));
    }

    int screenWidth;
    int screenHeight;
    if (currentRenderer == Renderer::OpenGl)
    {
        int scale = static_cast<GLRenderer &>(nds->GPU.GetRenderer3D()).GetScaleFactor();
        screenWidth = 256 * scale;
        screenHeight = (192 + 1) * scale;
    }
    else if (currentRenderer == Renderer::Compute)
    {
        auto computeRenderSettings = static_cast<ComputeRenderSettings&>(*currentConfiguration->renderSettings);
        int scale = computeRenderSettings.scale;
        screenWidth = 256 * scale;
        screenHeight = (192 + 1) * scale;
    }
    else
    {
        screenWidth = 256;
        screenHeight = 192 + 1;
    }

    Frame* renderFrame = frameQueue.getRenderFrame();

    EGLDisplay currentDisplay = eglGetCurrentDisplay();
    // Delete old render fence
    if (renderFrame->renderFence)
    {
        eglDestroySyncKHR(currentDisplay, renderFrame->renderFence);
        renderFrame->renderFence = 0;
    }

    // Ensure presentation is finished
    if (renderFrame->presentFence)
    {
        eglWaitSyncKHR(currentDisplay, renderFrame->presentFence, 0);
    }

    // Validate frame after ensuring that the frame has finished presenting
    frameQueue.validateRenderFrame(renderFrame, screenWidth, screenHeight * 2);

    [[unlikely]] if (nds->GPU.GetRenderer3D().NeedsShaderCompile())
    {
        // Compile all required shaders at once
        do
        {
            int currentShader;
            int shadersCount;
            nds->GPU.GetRenderer3D().ShaderCompileStep(currentShader, shadersCount);
        }
        while (nds->GPU.GetRenderer3D().NeedsShaderCompile());
    }

    bool isRendererAccelerated = nds->GPU.GetRenderer3D().Accelerated;
    if (isRendererAccelerated)
    {
        int backBuffer = nds->GPU.FrontBuffer ? 0 : 1;
        nds->GPU.GetRenderer3D().SetOutputTexture(backBuffer, renderFrame->frameTexture);
    }

    // [KHMM] Per-frame plugin update. The desktop frontend does two things each frame that
    // our port was missing: refreshGameScene() (detect the current KH game scene by reading
    // DS RAM; EmuThread.cpp:466, before RunFrame) and buildShapes() (rebuild the 2D/3D
    // composite shape lists for that scene; EmuThread.cpp:534, after RunFrame). Both feed
    // the GL compositor (gpuOpenGL_FS_updateVariables) and the 3D polygon-rewrite hook.
    // Without them the scene stays undetected and the shape lists stay empty, so the
    // composite renders nothing useful (blank/garbled). The compositor runs *inside*
    // RunFrame here (GPU::Blit), so both must run just before it. Both are CPU-only.
    if (khPluginActive && plugin != nullptr && plugin->isReady())
    {
        plugin->refreshGameScene();
        plugin->buildShapes();

        // [KHMM] Per-frame plugin input processing, in desktop order (EmuThread.cpp:324-336):
        // camera touch-key mask first, then the hotkey hook, then the KH addon keys, then
        // the filtered mask reaches the DS. Runs on a COPY of inputMask so the raw user
        // input survives the gate toggling.
        //
        // Camera stick: the KH plugins ignore the touch pointers entirely and instead
        // ARM7-write the decoded axes into a RAM mailbox + inject an ARM code cave (the
        // shocoman analog hack) — pure core RAM calls, internally gated on a boot counter,
        // so calling it every frame like desktop is safe.
        // Config reload requested (e.g. the camera-sensitivity pref changed): mirror desktop
        // EmuThread.cpp:294-296 — clear the flag and re-run loadConfigs on the emu thread.
        if (plugin->shouldInvalidateConfigs)
        {
            plugin->shouldInvalidateConfigs = false;
            khLoadPluginConfigs();
        }

        u16 khDummyTouchX = 0, khDummyTouchY = 0;
        bool khDummyTouching = false;
        plugin->applyTouchKeyMaskToTouchControls(&khDummyTouchX, &khDummyTouchY, &khDummyTouching,
                                                 khTouchKeyMask.load(std::memory_order_relaxed));

        // Hotkey hook: mirrors the game's pause-menu cursor into the overlay (Plugin.cpp:401),
        // drives the cutscene skip menu, blocks input during unskippable cutscenes. The
        // hotkey masks stay dummies — KHMM adds no stock hotkeys; the plugins only WRITE
        // bit 4 (loading-screen fast-forward request), which we don't consume yet.
        u32 khFilteredInput = inputMask;

        // [KHMM] Pause-menu d-pad fix (an improvement over desktop, which has the same
        // latent bug): with the KH layout, the physical d-pad sends command-menu addon
        // keys, and the plugin only converts those into DS d-pad bits in the ADDON hook
        // (PluginKingdomHeartsDays.cpp, the non-ingame else branch) — AFTER the hotkey
        // hook, whose pause-menu reader (Plugin.cpp isPauseMenuGameScene block) therefore
        // never sees them. The mask is rebuilt from raw input every frame, so the addon
        // conversion never survives into the next frame's read either. Net effect: the
        // d-pad moved the game's hidden native pause menu but not the overlay, and A then
        // confirmed two menus pointing at different entries. Converting the held command-
        // menu keys into DS d-pad bits BEFORE the hotkey hook keeps the overlay and the
        // hidden native menu on the same cursor. The addon hook's own conversion then
        // re-clears the same bits (idempotent). Scoped to the game pause menu: the
        // cutscene skip menu already handles the d-pad via
        // _superApplyAddonKeysToCutsceneMenu, and converting there would double-step it.
        if (plugin->isPauseMenuGameScene())
        {
            u32 khHeld = khAddonHeld.load(std::memory_order_relaxed);
            if (khHeld & (1u << 3)) khFilteredInput &= ~(1u << 5); // KH_COMMAND_MENU_LEFT  -> DS left
            if (khHeld & (1u << 4)) khFilteredInput &= ~(1u << 4); // KH_COMMAND_MENU_RIGHT -> DS right
            if (khHeld & (1u << 5)) khFilteredInput &= ~(1u << 6); // KH_COMMAND_MENU_UP    -> DS up
            if (khHeld & (1u << 6)) khFilteredInput &= ~(1u << 7); // KH_COMMAND_MENU_DOWN  -> DS down
        }

        u32 khHotkeyMask = 0, khHotkeyPress = 0;
        plugin->applyHotkeyToInputMaskOrTouchControls(&khFilteredInput, &khDummyTouchX, &khDummyTouchY,
                                                      &khDummyTouching, &khHotkeyMask, &khHotkeyPress);

        // KH addon keys (lock-on, switch target, command menu, HUD toggle...): translate the
        // app-side held-action bits into the per-game plugin AddonMask and compute the
        // rising edge here on the emu thread (desktop: EmuInstanceInput.cpp:664-667). The
        // plugin mutates the mask copies (the cutscene menu zeroes them when it consumes
        // input), so edge state is kept from our built value, not the call results.
        u32 khAddonMask = khBuildAddonMask();
        u32 khAddonPress = khAddonMask & ~khLastAddonMask;
        khLastAddonMask = khAddonMask;
        plugin->applyAddonKeysToInputMaskOrTouchControls(&khFilteredInput, &khDummyTouchX, &khDummyTouchY,
                                                         &khDummyTouching, &khAddonMask, &khAddonPress);
        nds->SetKeyMask(khFilteredInput);

        // [KHMM] menu sound requests (1=enter, 2=move, 3=continue, 4=select), played by the
        // frontend (desktop: EmuThread.cpp:1017)
        if (int khMenuSound = plugin->CutsceneMenuSoundToPlay()) {
            int32_t soundId = khMenuSound;
            fireEmulatorEvent(AndroidMelonEventMessenger::EVENT_KH_MENU_SOUND, sizeof(soundId), &soundId);
        }

        // [KHMM] remastered-BGM replacement: refreshGameScene (above) ran the plugin's BGM
        // state machines (refreshBackgroundMusic/refreshStreamedMusic — they read the game's
        // sequencer state from RAM and mute the DS side), which raise polled flags for the
        // frontend player (desktop: EmuThread.cpp:960-1000).
        KhBgm::pollPlugin(plugin);
    }

    // [KHMM] While an HD replacement video plays, the hidden DS prerendered cutscene races
    // to its end with the frame limiter bypassed (desktop: pluginShouldFastForward,
    // EmuThread.cpp:555/896-899). Recomputed every frame so it can never stay latched.
    khCutsceneFastForward.store(
        khPluginActive && plugin != nullptr && plugin->isReady() &&
        plugin->IsIngamePrerenderedCutsceneRunning() && plugin->IsReplacementCutsceneRunning(),
        std::memory_order_relaxed);

    // [KHMM] apply the runtime enhanced-graphics gates. The polygon-rewrite hook and the
    // composite FS follow the user's enhanced-graphics setting, so switching the setting
    // mid-session cleanly falls back to stock rendering.
    if (plugin != nullptr)
    {
        plugin->setApplyPolygonChanges(khPluginActive);
    }

    if (currentRenderer == Renderer::OpenGl)
    {
        static_cast<GLRenderer &>(nds->GPU.GetRenderer3D()).SetCompositeFSEnabled(khPluginActive);
    }

    u32 nLines = nds->RunFrame();
    retroAchievementsManager->FrameUpdate();

    if (!isRendererAccelerated)
    {
        int frontbuf = nds->GPU.FrontBuffer;
        if (nds->GPU.Framebuffer[frontbuf][0] && nds->GPU.Framebuffer[frontbuf][1])
        {
            glBindTexture(GL_TEXTURE_2D, renderFrame->frameTexture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 192, GL_RGBA, GL_UNSIGNED_BYTE, nds->GPU.Framebuffer[frontbuf][0].get());
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 192 + 2, 256, 192, GL_RGBA, GL_UNSIGNED_BYTE, nds->GPU.Framebuffer[frontbuf][1].get());
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }
    else
    {
        // Do nothing. Emulator already renders into the texture, which was set-up above
    }

    // [KHMM] The plugin can veto presenting a frame (desktop EmuThread.cpp:467+532 skips
    // drawScreenGL when shouldRenderFrame() is false): false while an ingame/replacement
    // cutscene runs — hides the DS cutscene racing behind the HD video, including the
    // fast-forwarded frames between native detection and the video overlay appearing —
    // and for the off-screen half of Days' double-3D scenes. The frame still ran; only
    // its presentation is skipped, so the screen holds the last presented frame (by
    // detection time that is the cutscene scene's black composite).
    bool khVetoPresent = khPluginActive && plugin != nullptr && plugin->isReady() && !plugin->shouldRenderFrame();

    bool isSleeping = nds->CPUStop & CPUStop_Sleep;
    if (!isSleeping && !khVetoPresent) [[likely]]
    {
        renderFrame->renderFence = eglCreateSyncKHR(currentDisplay, EGL_SYNC_FENCE_KHR, nullptr);
        glFlush();
        frameQueue.pushRenderedFrame(renderFrame);
    }
    else
    {
        frameQueue.discardRenderedFrame(renderFrame);
    }

    if (ndsSave)
        ndsSave->CheckFlush();

    if (gbaSave)
        gbaSave->CheckFlush();

    if (firmwareSave)
        firmwareSave->CheckFlush();

    frame++;
    bool needsRewindCapture = rewindManager.ShouldCaptureState(frame);
    bool needsScreenshot = screenshotRenderer->isScreenshotPending();

    if (needsRewindCapture || needsScreenshot) [[unlikely]]
        screenshotRenderer->renderScreenshot(&nds->GPU, currentRenderer, renderFrame);

    if (needsRewindCapture)
    {
        auto nextRewindState = rewindManager.GetNextRewindSaveState(frame);
        saveRewindState(nextRewindState);
    }

    return nLines;
}

void MelonInstance::stop()
{
    retroAchievementsManager = nullptr;
    screenshotRenderer->cleanup();
}

void MelonInstance::updateMotionData(float ax, float ay, float az, float rx, float ry, float rz)
{
    motionData[MotionQueryType::MotionAccelerationX].store(ax, std::memory_order_relaxed);
    motionData[MotionQueryType::MotionAccelerationY].store(ay, std::memory_order_relaxed);
    motionData[MotionQueryType::MotionAccelerationZ].store(az, std::memory_order_relaxed);
    motionData[MotionQueryType::MotionRotationX].store(rx, std::memory_order_relaxed);
    motionData[MotionQueryType::MotionRotationY].store(ry, std::memory_order_relaxed);
    motionData[MotionQueryType::MotionRotationZ].store(rz, std::memory_order_relaxed);
}

float MelonInstance::getMotionData(MotionQueryType type)
{
    if (type < MotionQueryType::MotionAccelerationX || type > MotionQueryType::MotionRotationZ)
        return 0.0f;

    return motionData[type].load(std::memory_order_relaxed);
}

void MelonInstance::touchScreen(u16 x, u16 y)
{
    nds->TouchScreen(x, y);
}

void MelonInstance::releaseScreen()
{
    nds->ReleaseScreen();
}

void MelonInstance::pressKey(u32 key)
{
    // Special handling for Lid input
    if (key == 16 + 7)
    {
        nds->SetLidClosed(true);
    }
    else
    {
        inputMask &= ~(1 << key);
        nds->SetKeyMask(inputMask);
    }
}

void MelonInstance::releaseKey(u32 key)
{
    // Special handling for Lid input
    if (key == 16 + 7)
    {
        nds->SetLidClosed(false);
    }
    else
    {
        inputMask |= (1 << key);
        nds->SetKeyMask(inputMask);
    }
}

int MelonInstance::readAudioOutput(s16* buffer, int length)
{
    return nds->SPU.ReadOutput(buffer, length);
}

void MelonInstance::setAudioOutputSkew(double skew)
{
    nds->SPU.SetOutputSkew(skew);
}

bool MelonInstance::takeScreenshot()
{
    return screenshotRenderer->takeScreenshot();
}

void MelonInstance::loadCheats(std::list<Cheat> cheats)
{
    std::vector<ARCode> codeList;

    for (auto cheat : cheats)
    {
        ARCode arCode {
            .Enabled = true,
            .Code = cheat.code,
        };
        codeList.push_back(arCode);
    }

    nds->AREngine.Cheats = codeList;
}

int MelonInstance::sendNetPacket(u8* data, int length)
{
    return net->SendPacket(data, length, instanceId);
}

int MelonInstance::receiveNetPacket(u8* data)
{
    return net->RecvPacket(data, instanceId);
}

Frame* MelonInstance::getPresentationFrame(std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline)
{
    return frameQueue.getPresentFrame(deadline);
}

void MelonInstance::updateConfiguration(std::shared_ptr<EmulatorConfiguration> newConfiguration)
{
    if (nds)
    {
        nds->SPU.SetInterpolation(static_cast<AudioInterpolation>(newConfiguration->audioSettings.audioInterpolation));
        nds->SPU.SetDegrade10Bit(static_cast<AudioBitDepth>(newConfiguration->audioSettings.audioBitrate));
    }

    rewindManager.UpdateRewindSettings(newConfiguration->rewindEnabled, newConfiguration->rewindLengthSeconds, newConfiguration->rewindCaptureSpacingSeconds);

    khEnhancedGraphics = newConfiguration->enhancedGraphics; // [KHMM] user setting (runtime-gated)

    // [KHMM] enhanced graphics off (or a non-OpenGL renderer) = stock DS rendering, where
    // the game's native pause menu is visible again — retract the overlay if it is up (the
    // plugin driver stops running so it would never fire the hide itself)
    if ((!khEnhancedGraphics || newConfiguration->renderer != Renderer::OpenGl) && khPauseMenuShown && plugin != nullptr) {
        khFirePauseMenuEvent(false);
    }

    // [KHMM] same reasoning for an HD replacement cutscene: with the plugin driver inert,
    // refreshCutscene stops running, so nothing would ever dismiss the video, lift the DS
    // audio mute, or release a parked emu loop — clear all of it here.
    if ((!khEnhancedGraphics || newConfiguration->renderer != Renderer::OpenGl) &&
        plugin != nullptr && plugin->IsReplacementCutsceneRunning()) {
        khEmuHoldForCutscene = false;
        OboeCallback::khMuteDsAudio = false;
        khCutsceneFastForward = false;
        khFireCutsceneEvent(false);
    }

    // [KHMM] and for replacement BGM: with the plugin driver inert its stop flag would
    // never be polled again, leaving the music looping forever — fade it out here. The DS
    // side stays muted for the already-muted track (SSEQ bytes were erased in RAM), which
    // resolves at the next track change / area transition; acceptable for a mid-session
    // settings flip.
    if (!khEnhancedGraphics || newConfiguration->renderer != Renderer::OpenGl) {
        KhBgm::stopAll(500);
    }

    currentConfiguration = newConfiguration;
    isRenderConfigurationDirty = true;
}

void MelonInstance::requestNdsSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength)
{
    if (ndsSave)
        ndsSave->RequestFlush(saveData, saveLength, writeOffset, writeLength);
}

void MelonInstance::requestGbaSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength)
{
    if (gbaSave)
        gbaSave->RequestFlush(saveData, saveLength, writeOffset, writeLength);
}

void MelonInstance::requestFirmwareSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength)
{
    if (firmwareSave)
        firmwareSave->RequestFlush(saveData, saveLength, writeOffset, writeLength);
}

bool MelonInstance::saveState(Savestate* state)
{
    if (!retroAchievementsManager->DoSavestate(state))
        return false;

    return nds->DoSavestate(state);
}

bool MelonInstance::loadState(Savestate* state)
{
    if (!retroAchievementsManager->DoSavestate(state))
        return false;

    if (nds->DoSavestate(state))
    {
        setBatteryLevels();
        setDateTime();

        // [KHMM] the desktop calls plugin->onLoadState() after every state load
        // (EmuInstance.cpp:780): it stops the replacement BGM (raising the stop flag for
        // the frontend player) and resets the plugin's soundtrack state machine so the
        // loaded state's music re-detects on the next frame. Without this the old
        // replacement track keeps playing over the loaded state. Also covers rewind
        // (loadRewindState funnels through here).
        if (plugin != nullptr)
            plugin->onLoadState();

        return true;
    }
    else
    {
        return false;
    }
}

RewindWindow MelonInstance::getRewindWindow()
{
    return RewindWindow {
        .currentFrame = frame,
        .rewindStates = rewindManager.GetRewindWindow(),
    };
}

bool MelonInstance::loadRewindState(RewindSaveState rewindSaveState)
{
    Savestate* savestate = new Savestate(rewindSaveState.buffer, rewindSaveState.bufferContentSize, false);
    if (savestate->Error)
    {
        delete savestate;
        return false;
    }

    bool result = loadState(savestate);
    if (result)
    {
        frame = rewindSaveState.frame;
        rewindManager.OnRewindFromState(rewindSaveState);
    }

    delete savestate;

    return result;
}

void MelonInstance::setupAchievements(
    std::list<RetroAchievements::RAAchievement> achievements,
    std::list<RetroAchievements::RALeaderboard> leaderboards,
    std::optional<std::string> richPresenceScript
)
{
    if (instanceId == 0)
    {
        retroAchievementsManager->LoadAchievements(achievements);
        retroAchievementsManager->LoadLeaderboards(leaderboards);
        if (richPresenceScript)
            retroAchievementsManager->SetupRichPresence(*richPresenceScript);
    }
}

void MelonInstance::unloadRetroAchievementsData()
{
    retroAchievementsManager->UnloadEverything();
}

std::string MelonInstance::getRichPresenceStatus()
{
    if (instanceId == 0 && retroAchievementsManager)
        return retroAchievementsManager->GetRichPresenceStatus();
    else
        return "";
}

std::vector<RetroAchievements::RARuntimeAchievement> MelonInstance::getRuntimeAchievements()
{
    if (instanceId == 0 && retroAchievementsManager)
        return retroAchievementsManager->GetRuntimeAchievements();
    else
        return { };
}

// [KHMM] Serve the plugin's config keys and (re)load them. Called at plugin creation and
// again from runFrame whenever shouldInvalidateConfigs is raised (desktop parity: settings
// changes re-run loadConfigs on the emu thread, EmuThread.cpp:294-309) — currently the
// camera-sensitivity pref uses this to apply live.
//
// Enhanced graphics is ALWAYS loaded as enabled so the composite fragment shader is
// available to the GL compositor regardless of the user's current setting — the setting
// (khEnhancedGraphics) gates the per-frame driver, composite FS, and polygon hook at
// runtime instead, which lets the user toggle it mid-session in both directions without
// reloading the ROM. All other config keys resolve to safe defaults.
//
// The KH plugins read the DS firmware language ("Instance0.Firmware.Language",
// 0=JA 1=EN 2=FR 3=DE 4=IT 5=ES 6=ZH) to pick the pause/skip-menu string language —
// returning 0 for it would mean Japanese menus. "Instance0.Firmware.TrueLanguage" is
// desktop KHMM's language override setting (0 = follow the DS language), left at 0.
void MelonInstance::khLoadPluginConfigs()
{
    bool enhanced = true;
    int firmwareLanguage = currentConfiguration->firmwareConfiguration.language;
    plugin->loadConfigs(
        // "<root>.DisableSubtitles" gates the .srt path resolution at cutscene start
        // (PluginKingdomHeartsDays::replacementCutsceneSubtitlesFilePath); inverted here
        // from the app's "show subtitles" pref.
        [enhanced](std::string path) -> bool {
            if (!enhanced &&
                (path.find(".DisableEnhancedGraphics") != std::string::npos ||
                 path.find(".DisableSingleScreenMode") != std::string::npos))
                return true;
            if (path.size() > 17 && path.compare(path.size() - 17, 17, ".DisableSubtitles") == 0)
                return !khShowSubtitles.load(std::memory_order_relaxed);
            // "<root>.DisableSingleScreenMode" — off = dual-screen devices keep bottom-screen
            // content on the native bottom screen (the composite FS passes the bottom half
            // through untouched; the plugin returns screenLayout_Top + no shapes)
            if (path.size() > 24 && path.compare(path.size() - 24, 24, ".DisableSingleScreenMode") == 0)
                return !khSingleScreenMode.load(std::memory_order_relaxed);
            return false;
        },
        // "<root>.CameraSensitivity" is the camera-stick speed (a shift count on the 0-15
        // stick nibbles — each whole step DOUBLES the speed; desktop spinbox 1-4, default
        // 3). Our pref stores HALF-UNITS (2-8 = 1.0-4.0): the shift served here is
        // ceil(v/2), and half-steps (odd v) scale the stick range to 75% at quantization
        // (khQuantizeCameraAxis) — 0.75 * 2^(s+1) = 1.5 * 2^s, the linear midpoint.
        // 0 = unset, the plugin falls back to DefaultCameraSensitivity.
        [firmwareLanguage](std::string path) -> int {
            if (path == "Instance0.Firmware.Language")
                return firmwareLanguage;
            if (path.size() > 18 && path.compare(path.size() - 18, 18, ".CameraSensitivity") == 0)
                return (khCameraSensitivity.load(std::memory_order_relaxed) + 1) / 2;
            return 0;
        },
        // [KHMM] "<root>.AudioPack" is the remastered-BGM audio pack subfolder (root is the
        // per-region toml id, e.g. "KHDays_US.AudioPack" / "KHReCoded_EU.AudioPack"). The
        // Kotlin side pushes the user's pack choice per game before the ROM loads
        // (setKhAudioPacks); empty = no pack, which leaves BGM replacement inert unless
        // files sit at the audio/ root (desktop fallback behavior).
        [](std::string path) -> std::string {
            if (path.size() > 10 && path.compare(path.size() - 10, 10, ".AudioPack") == 0) {
                if (path.compare(0, 6, "KHDays") == 0)
                    return khBgmAudioPackDays;
                if (path.compare(0, 9, "KHReCoded") == 0)
                    return khBgmAudioPackRecoded;
            }
            return std::string();
        }
    );

    // Keep the replacement-TEXTURE path OFF regardless of the enhanced-graphics toggle
    // (this gates textures only; HD replacement cutscenes are gated separately and are
    // ported — see the cutscene callbacks in loadPlugin). loadConfigs always resets
    // DisableReplacementTextures to false, so force it back to true here — on every
    // (re)load. Texture replacement stays deferred: no pack in hand, and TextureEntry's
    // by-value scenes[1000] array needs a RAM refactor before it is safe on Android.
    if (!plugin->areReplacementTexturesDisabled())
        plugin->replacementTexturesToggle();
}

// [KHMM] (Re)create the KH Melon Mix plugin for the given game code and wire it to
// the console. PluginManager::load never returns null (PluginDefault fallback), so
// `plugin` is non-null for the life of the instance once this has run.
void MelonInstance::loadPlugin(u32 gameCode)
{
    if (plugin != nullptr && plugin->getGameCode() == gameCode)
        return;

    Plugins::Plugin* oldPlugin = plugin;
    // gameCode 0 is our pre-ROM placeholder (constructor / firmware boot) and must resolve
    // to the inert default plugin. It cannot go through PluginManager: upstream leaves
    // unfilled per-region gamecode constants at 0 (PluginHarvestMoonDsCute eu/jp,
    // PluginMetroidPrimeHunters us/jp) and isCart() is a plain equality check, so
    // PluginManager::load(0) would hand back the Harvest Moon plugin.
    // Non-US KH carts also resolve to the inert default plugin: their enhancement RAM
    // address tables are unconfirmed/wrong upstream and white-screened the game at boot
    // (see isEnhancedGameCode). They run as plain, unenhanced DS games.
    plugin = (gameCode == 0 || !MelonDSAndroid::isEnhancedGameCode(gameCode))
            ? new Plugins::PluginDefault(gameCode)
            : Plugins::PluginManager::load(gameCode);

    khLoadPluginConfigs();

    plugin->setNds(nds);

    // [KHMM] Refined controls: resolve the app-side action ordinals to this game's addon-key
    // bits. The bit is the index into the plugin's customKeyMappingNames vector and DIFFERS
    // between Days and Re:Coded (Re:Coded drops Days' first three entries), so bindings are
    // keyed by name, never by bit. -1 = this game has no such key (or non-KH plugin).
    for (int i = 0; i < kKhAddonActionCount; i++)
        khAddonBitByAction[i] = plugin->customKeyIndexByName(kKhAddonKeyNames[i]);
    khLastAddonMask = 0;

    // The software 2D renderer keeps its own plugin pointer.
    static_cast<GPU2D::SoftRenderer&>(nds->GPU.GetRenderer2D()).setPlugin(plugin);

    // [KHMM] Pause-menu overlay callbacks. KHMM hides the game's native pause menu inside
    // the composite (renderer_topScreen_2DShapes: "hidden because we got the new one on
    // overlay") and draws a replacement menu in the FRONTEND (desktop: PauseMenuOverlay Qt
    // widget, wired in EmuThread.cpp:243-280). Without these the menu exists (the plugin's
    // input mirror at Plugin.cpp:401 still runs) but nothing draws it. Every callback fires
    // a full snapshot event; the Kotlin side rebuilds the overlay from it each time.
    // The cutscene trio is the skip menu shown OVER a playing HD replacement video; its
    // selection lives in the plugin (_CutsceneSkipMenuSelection) and only reaches us through
    // the callback argument.
    plugin->showGamePauseMenuOverlay = [this]() { khFirePauseMenuEvent(true); };
    plugin->hideGamePauseMenuOverlay = [this]() { khFirePauseMenuEvent(false); };
    plugin->updateGamePauseMenuOverlaySelection = [this](int) { khFirePauseMenuEvent(true); };
    plugin->refreshGamePauseMenuOverlayContent = [this]() { khFirePauseMenuEvent(true); };
    plugin->showCutscenePauseMenuOverlay = [this](int selection) { khFirePauseMenuEvent(true, selection); };
    plugin->updateCutscenePauseMenuOverlaySelection = [this](int selection) { khFirePauseMenuEvent(true, selection); };
    plugin->hideCutscenePauseMenuOverlay = [this]() { khFirePauseMenuEvent(false); };

    // [KHMM] HD replacement cutscene lifecycle (desktop: EmuThread.cpp:240-266 + 910-956).
    // The frontend plays the video (ExoPlayer) while the emulator, muted and hidden behind
    // it, fast-forwards through its own prerendered cutscene — the plugin's end-of-cutscene
    // handshake (didMobiCutsceneEnded) needs the DS advancing. Once the DS side finishes
    // first, the emu loop parks (khEmuHoldForCutscene) until the video ends. The mute lifts
    // and the loop resumes normal pacing when the plugin declares both sides done.
    plugin->startReplacementCutscene = [this](std::string videoPath, std::string subtitlesPath) {
        OboeCallback::khMuteDsAudio = true;
        khFireCutsceneEvent(true, videoPath, subtitlesPath);
    };
    plugin->stopReplacementCutsceneAndResumeEmulator = [this]() {
        khEmuHoldForCutscene = false;
        khFireCutsceneEvent(false);
    };
    plugin->resumeHiddenEmulatorAfterReplacementCutsceneStopped = [this]() {
        khEmuHoldForCutscene = false;
        khFireCutsceneEvent(false);
    };
    plugin->pauseEmulatorAfterIngamePrerenderedCutsceneEndedBeforeReplacementCutscene = []() {
        khEmuHoldForCutscene = true;
    };
    plugin->resumeEmulatorAfterBothIngamePrerenderedCutsceneAndReplacementCutsceneEnded = [this]() {
        khEmuHoldForCutscene = false;
        OboeCallback::khMuteDsAudio = false;
        khFireCutsceneEvent(false);
    };
    plugin->postMessageToOsd = [](std::string message) {
        LOG_WARN("MelonMixKh", "%s", message.c_str());
    };

    delete oldPlugin;
}

// [KHMM] Shared payload helpers for the KH events (native byte order, length-prefixed UTF-8
// strings; layout must match AndroidMelonEventMessenger.h / EmulatorEventType.kt).
static void khAppendI32(std::vector<u8>& v, int32_t value)
{
    const u8* p = reinterpret_cast<const u8*>(&value);
    v.insert(v.end(), p, p + sizeof(value));
}

static void khAppendStr(std::vector<u8>& v, const std::string& s, size_t cap)
{
    int32_t length = (int32_t) std::min(s.size(), cap);
    khAppendI32(v, length);
    v.insert(v.end(), s.begin(), s.begin() + length);
}

// [KHMM] Pack the pause-menu overlay snapshot and fire it at the frontend. Runs on the emu
// thread (all callbacks fire from refreshGameScene / the input hook). Total size must stay
// under the Kotlin queue's data buffer (512 bytes). cutsceneMenuSelection >= 0 means this is
// the cutscene skip menu shown over a playing HD video (desktop PauseMenuOverlay's cutscene
// mode: Continue/Skip, no subtitle, no darkening — the video stays visible).
void MelonInstance::khFirePauseMenuEvent(bool visible, int cutsceneMenuSelection)
{
    khPauseMenuShown = visible;
    bool isCutsceneMenu = cutsceneMenuSelection >= 0;

    std::vector<u8> payload;
    payload.reserve(256);
    khAppendI32(payload, visible ? 1 : 0);
    khAppendI32(payload, isCutsceneMenu ? cutsceneMenuSelection : plugin->GamePauseMenuSelection());
    khAppendI32(payload, (!isCutsceneMenu && plugin->gamePauseMenuDarkensBackground()) ? 1 : 0);
    khAppendI32(payload, (int32_t) (plugin->getHudScale() / 8.0f * 1000.0f)); // desktop: setSizeModifier(getHudScale()/8.0)
    khAppendStr(payload, plugin->pauseMenuTitle(), 64);
    khAppendStr(payload, isCutsceneMenu ? std::string() : plugin->gamePauseMenuSubtitle(), 64);
    auto labels = isCutsceneMenu ? plugin->cutsceneMenuButtonLabels() : plugin->gamePauseMenuButtonLabels();
    int32_t labelCount = (int32_t) std::min(labels.size(), (size_t) 4);
    khAppendI32(payload, labelCount);
    for (int32_t i = 0; i < labelCount; i++) {
        khAppendStr(payload, labels[i], 64);
    }

    fireEmulatorEvent(AndroidMelonEventMessenger::EVENT_KH_PAUSE_MENU, (int) payload.size(), payload.data());
}

// [KHMM] Tell the frontend to start or dismiss the HD replacement cutscene video player
// (desktop: windowStartVideo / windowStopVideo signals out of EmuThread). Fired from plugin
// callbacks on the emu thread. Stop events carry no paths and may fire redundantly (the
// Kotlin side treats them idempotently).
void MelonInstance::khFireCutsceneEvent(bool playing, const std::string& videoPath, const std::string& subtitlesPath)
{
    std::vector<u8> payload;
    payload.reserve(512);
    khAppendI32(payload, playing ? 1 : 0);
    if (playing) {
        khAppendStr(payload, videoPath, 224);
        khAppendStr(payload, subtitlesPath, 224);
    }

    fireEmulatorEvent(AndroidMelonEventMessenger::EVENT_KH_CUTSCENE, (int) payload.size(), payload.data());
}

// [KHMM] Video player returns (UI thread over JNI; desktop calls these from the Qt GUI
// thread in MainWindowSettings::stopVideo/cancelVideo, so the threading model matches).
void MelonInstance::khCutsceneEnded()
{
    if (plugin != nullptr && plugin->isReady())
        plugin->skipIngamePrerenderedCutsceneAfterReplacementCutsceneFinishesNaturally();
}

void MelonInstance::khCutsceneFailed(std::string error)
{
    if (plugin != nullptr && plugin->isReady())
        plugin->resumeIngamePrerenderedCutsceneAfterReplacementCutsceneFailedToPlay(std::move(error));
}

// [KHMM] A save state was loaded while an HD replacement video is playing. The loaded state
// has nothing to do with the video anymore, so arm the plugin's skip sequence — the exact
// path the cutscene menu's Skip button takes. runFrame runs refreshGameScene (which updates
// the cutscene state machine from the loaded RAM) before the input hook consumes the flag,
// so the consumption either stops the video immediately (loaded state has no DS cutscene:
// the "DS already ended" branch) or runs the regular DS-cutscene skip feed first (state was
// saved mid-cutscene) — both end with the video dismissed and the game at the loaded state.
void MelonInstance::khStateLoadedDuringCutscene()
{
    if (plugin != nullptr && plugin->isReady() && plugin->IsReplacementCutsceneRunning())
        plugin->skipIngamePrerenderedCutsceneThroughPauseMenu();
}

void MelonInstance::khSetAddonKey(int action, bool down)
{
    if (action < 0 || action >= kKhAddonActionCount)
        return;

    u32 bit = 1u << action;
    if (down)
        khAddonHeld.fetch_or(bit, std::memory_order_relaxed);
    else
        khAddonHeld.fetch_and(~bit, std::memory_order_relaxed);
}

u32 MelonInstance::khBuildAddonMask()
{
    u32 held = khAddonHeld.load(std::memory_order_relaxed);
    u32 addonMask = 0;
    for (int i = 0; i < kKhAddonActionCount; i++)
    {
        if ((held & (1u << i)) && khAddonBitByAction[i] >= 0)
            addonMask |= 1u << khAddonBitByAction[i];
    }
    return addonMask;
}

// [KHMM] Quantize one axis magnitude into a 4-bit nibble (0-15) past a deadzone. 15 is the
// hard cap: the nibble slots in TouchKeyMask are 4 bits wide (desktop's joystick path feeds
// 0-31 and bleeds into the neighbouring direction — a known upstream overflow, not ported).
// Half-step speeds (odd half-unit sensitivity values) use the next plugin shift with the
// stick range scaled to 75% — see the .CameraSensitivity comment in khLoadPluginConfigs.
static u32 khQuantizeCameraAxis(float value)
{
    float magnitude = value < 0 ? -value : value;
    const float deadzone = 0.15f;
    if (magnitude <= deadzone)
        return 0;

    float scaled = (magnitude - deadzone) / (1.0f - deadzone);
    if (scaled > 1.0f)
        scaled = 1.0f;
    float range = (khCameraSensitivity.load(std::memory_order_relaxed) & 1) ? 11.25f : 15.0f;
    u32 quantized = (u32) (scaled * range + 0.5f);
    return quantized > 15 ? 15 : quantized;
}

void MelonInstance::khSetCameraAxes(float x, float y)
{
    u32 right = x > 0 ? khQuantizeCameraAxis(x) : 0;
    u32 left  = x < 0 ? khQuantizeCameraAxis(x) : 0;
    u32 down  = y > 0 ? khQuantizeCameraAxis(y) : 0;
    u32 up    = y < 0 ? khQuantizeCameraAxis(y) : 0;
    // Active-low: the plugin decodes direction magnitudes as (~mask >> shift) & 0xF
    u32 mask = 0xFFFFu & ~(right | (left << 4) | (down << 8) | (up << 12));
    khTouchKeyMask.store(mask, std::memory_order_relaxed);
}

// [KHMM] See the header. Mirrors the input-hook invocation in runFrame, minus SetKeyMask
// (the DS is not stepping while parked; the filtered mask has nowhere to go). This keeps
// the skip menu fully alive in the parked state: navigation, Continue (hide + resume video)
// and Skip (the hook's _SkipDsCutscene consumption takes the "DS already ended" branch at
// Plugin.cpp:422 and stops the video immediately).
void MelonInstance::khCutsceneHoldTick()
{
    if (plugin == nullptr || !plugin->isReady())
        return;

    u32 khFilteredInput = inputMask;
    u32 khHotkeyMask = 0, khHotkeyPress = 0;
    u16 khDummyTouchX = 0, khDummyTouchY = 0;
    bool khDummyTouching = false;
    plugin->applyHotkeyToInputMaskOrTouchControls(&khFilteredInput, &khDummyTouchX, &khDummyTouchY,
                                                  &khDummyTouching, &khHotkeyMask, &khHotkeyPress);

    // [KHMM] addon keys too: the skip menu is also navigable with the command-menu addon
    // keys (_superApplyAddonKeysToCutsceneMenu). No touch-key-mask call while parked — the
    // camera is meaningless under a video and desktop's parked state runs none of this.
    u32 khAddonMask = khBuildAddonMask();
    u32 khAddonPress = khAddonMask & ~khLastAddonMask;
    khLastAddonMask = khAddonMask;
    plugin->applyAddonKeysToInputMaskOrTouchControls(&khFilteredInput, &khDummyTouchX, &khDummyTouchY,
                                                     &khDummyTouching, &khAddonMask, &khAddonPress);

    if (int khMenuSound = plugin->CutsceneMenuSoundToPlay()) {
        int32_t soundId = khMenuSound;
        fireEmulatorEvent(AndroidMelonEventMessenger::EVENT_KH_MENU_SOUND, sizeof(soundId), &soundId);
    }

    // [KHMM] with runFrame parked, this is the only heartbeat left for the BGM player's
    // time-based work — most importantly a delayed BGM start scheduled against the video
    // (e.g. the end-credits "Dearly Beloved (Reprise)" cue fires long after the loop parks).
    KhBgm::tick();
}

void MelonInstance::updateRenderer()
{
    Renderer newRenderer = currentConfiguration->renderer;

    if (newRenderer != currentRenderer)
    {
        switch (newRenderer)
        {
            case Renderer::Software:
                nds->GPU.SetRenderer3D(std::make_unique<SoftRenderer>());
                break;
            case Renderer::OpenGl:
                nds->GPU.SetRenderer3D(GLRenderer::New(plugin)); // [KHMM] pass active plugin
                break;
            case Renderer::Compute:
                nds->GPU.SetRenderer3D(ComputeRenderer::New(plugin)); // [KHMM] pass active plugin
                break;
            default: __builtin_unreachable();
        }
        currentRenderer = newRenderer;
    }

    switch (newRenderer)
    {
        case Renderer::Software:
        {
            auto softwareRenderSettings = static_cast<SoftwareRenderSettings&>(*currentConfiguration->renderSettings);
            static_cast<SoftRenderer&>(nds->GPU.GetRenderer3D()).SetThreaded(softwareRenderSettings.threadedRendering, nds->GPU);
            break;
        }
        case Renderer::OpenGl:
        {
            auto glRenderSettings = static_cast<OpenGlRenderSettings&>(*currentConfiguration->renderSettings);
            static_cast<GLRenderer&>(nds->GPU.GetRenderer3D()).SetRenderSettings(glRenderSettings.betterPolygons, glRenderSettings.scale);
            break;
        }
        case Renderer::Compute:
        {
            auto computeRenderSettings = static_cast<ComputeRenderSettings&>(*currentConfiguration->renderSettings);
            static_cast<ComputeRenderer&>(nds->GPU.GetRenderer3D()).SetRenderSettings(computeRenderSettings.scale,computeRenderSettings.highResCoordinates);
            break;
        }
        default: __builtin_unreachable();
    }
}

void MelonInstance::setBatteryLevels()
{
    if (consoleType == 1)
    {
        auto dsi = static_cast<DSi*>(nds);
        dsi->I2C.GetBPTWL()->SetBatteryLevel(DSi_BPTWL::batteryLevel_Full);
        dsi->I2C.GetBPTWL()->SetBatteryCharging(false);
    }
    else
    {
        nds->SPI.GetPowerMan()->SetBatteryLevelOkay(true);
    }
}

void MelonInstance::setDateTime()
{
    std::time_t t = std::time(0);
    std::tm* now = std::localtime(&t);

    nds->RTC.SetDateTime(now->tm_year + 1900, now->tm_mon + 1, now->tm_mday, now->tm_hour, now->tm_min, now->tm_sec);
}

void MelonInstance::saveRewindState(RewindSaveState* rewindSaveState)
{
    Savestate* savestate = new Savestate(rewindSaveState->buffer, rewindSaveState->bufferSize, true);
    if (saveState(savestate))
    {
        rewindSaveState->bufferContentSize = savestate->Length();
        memcpy(rewindSaveState->screenshot, screenshotRenderer->getScreenshot(), rewindSaveState->screenshotSize);
    }

    delete savestate;
}

}