#include <ctime>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <string>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <filesystem>
#include <GLES3/gl3.h>
#include "Args.h"
#include "GPU3D_Compute.h"
#include "GPU2D_Soft.h" // [KHMM] for GPU2D::SoftRenderer::setPlugin
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
#include "MelonLog.h" // [KHMM-DBG] perf instrumentation logging

using namespace std;
using namespace melonDS;
using namespace melonDS::Platform;

namespace MelonDSAndroid
{

const int kRewindBufferSize = 1024 * 1024 * 20; // Use 20MB per savestate

// [KHMM-DBG] perf instrumentation constants (temporary).
static const char* kDbgTag = "MelonMixPerf";
static const int kDbgReportFrames = 120; // log a timing window roughly every ~2s @60fps
static const int kDbgPollFrames = 60;    // re-read the debug control file ~once/second
const int kRewindScreenshotSize = 256 * 384 * 4;

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

    // [KHMM-DBG] Re-read runtime perf toggles (~once/second) and start the timing window.
    if (frame - khDbgLastPollFrame >= kDbgPollFrames)
    {
        khPollDebugControls();
        khDbgLastPollFrame = frame;
    }
    if (khStatFrames == 0)
    {
        khStatWallStart = std::chrono::steady_clock::now();
    }
    // [KHMM-DBG] per-frame work time (whole runFrame body) for the worst-case stats
    auto khFrameStart = std::chrono::steady_clock::now();

    // [KHMM] Single-screen presentation keystone. The KH plugin composites the whole
    // enhanced image into the DS *top-screen* region and expects the frontend to supply
    // the target display aspect ratio (the desktop calls Plugin::setAspectRatio from its
    // screen panel). Without it the plugin's AspectRatio stays 0, so the composite shader
    // computes 1.0/currentAspectRatio = inf and the output is corrupt. Push a widescreen
    // aspect each frame (also writes the game's internal widescreen RAM value). The
    // frontend must then present top-screen-only at this same aspect for the single-screen
    // look. TODO(Step C inc.2): source khAspectRatio from the real on-screen top-screen
    // viewport over JNI instead of hardcoding 16:9.
    if (khEnhancedGraphics && khDbgFovWiden && plugin != nullptr && plugin->isReady())
    {
        plugin->setAspectRatio(khAspectRatio); // [KHMM-DBG] gated by khDbgFovWiden
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
    if (khEnhancedGraphics && plugin != nullptr && plugin->isReady())
    {
        // [KHMM-DBG] time the two CPU-side plugin stages separately
        auto t0 = std::chrono::steady_clock::now();
        plugin->refreshGameScene();
        auto t1 = std::chrono::steady_clock::now();
        plugin->buildShapes();
        auto t2 = std::chrono::steady_clock::now();
        khStatRefreshNs += std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
        khStatBuildNs += std::chrono::duration_cast<std::chrono::nanoseconds>(t2 - t1).count();
    }

    // [KHMM-DBG] apply the runtime hook gate so the per-polygon rewrite can be A/B'd live
    if (plugin != nullptr)
    {
        plugin->debugSetApplyPolygonChanges(khDbgPolyHook);
    }

    // [KHMM-DBG] apply the runtime composite-FS gate (GL renderer only) so the plugin's
    // full-screen composite shader can be swapped for the stock nearest FS live.
    if (currentRenderer == Renderer::OpenGl)
    {
        static_cast<GLRenderer &>(nds->GPU.GetRenderer3D()).SetCompositeFSEnabled(khDbgCompositeFS);
    }

    // [KHMM-DBG] RunFrame carries the emulation + 3D render (incl. the polygon hook) + the
    // in-RunFrame GL composite, so this is the GPU-side cost we compare against the CPU stages.
    auto tRun0 = std::chrono::steady_clock::now();
    u32 nLines = nds->RunFrame();
    auto tRun1 = std::chrono::steady_clock::now();
    khStatRunFrameNs += std::chrono::duration_cast<std::chrono::nanoseconds>(tRun1 - tRun0).count();
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

    bool isSleeping = nds->CPUStop & CPUStop_Sleep;
    if (!isSleeping) [[likely]]
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

    // [KHMM-DBG] close out the timing window; track worst-case per-frame work time
    {
        uint64_t khFrameNs = (uint64_t) std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - khFrameStart).count();
        if (khFrameNs > khStatMaxFrameNs)
            khStatMaxFrameNs = khFrameNs;
        if (khFrameNs > 16900000ull) // 16.9ms = 60fps budget + slack
            khStatOverFrames++;
    }
    khStatFrames++;
    if (khStatFrames >= kDbgReportFrames)
    {
        khReportPerf();
    }

    return nLines;
}

// [KHMM-DBG] Read runtime perf toggles from <internalFilesDir>/melonmix_debug.txt so each A/B
// is a one-line echo on the (rooted) device instead of a rebuild. Format: one "key=value" per
// line; keys enhanced/fov/hook, values 1/0 (true/false/on/off/yes also accepted). Missing file
// or missing keys leave the current state untouched. Called ~once/second from runFrame.
void MelonInstance::khPollDebugControls()
{
    if (currentConfiguration == nullptr || currentConfiguration->internalFilesDir == nullptr)
        return;

    std::string path = std::string(currentConfiguration->internalFilesDir) + "/melonmix_debug.txt";
    std::ifstream file(path);
    if (!file.is_open())
        return;

    auto trim = [](std::string s) -> std::string {
        size_t a = s.find_first_not_of(" \t\r\n");
        size_t b = s.find_last_not_of(" \t\r\n");
        if (a == std::string::npos) return "";
        return s.substr(a, b - a + 1);
    };

    bool newEnhanced = khEnhancedGraphics;
    bool newFov = khDbgFovWiden;
    bool newHook = khDbgPolyHook;
    bool newFs = khDbgCompositeFS;

    std::string line;
    while (std::getline(file, line))
    {
        // tolerate a leading UTF-8 BOM (editors/echo often prepend EF BB BF)
        if (line.size() >= 3 && (unsigned char) line[0] == 0xEF &&
            (unsigned char) line[1] == 0xBB && (unsigned char) line[2] == 0xBF)
            line.erase(0, 3);
        size_t eq = line.find('=');
        if (eq == std::string::npos)
            continue;
        std::string key = trim(line.substr(0, eq));
        std::string val = trim(line.substr(eq + 1));
        bool on = (val == "1" || val == "true" || val == "on" || val == "yes");
        if (key == "enhanced") newEnhanced = on;
        else if (key == "fov") newFov = on;
        else if (key == "hook") newHook = on;
        else if (key == "fs") newFs = on;
    }

    if (newEnhanced != khEnhancedGraphics || newFov != khDbgFovWiden ||
        newHook != khDbgPolyHook || newFs != khDbgCompositeFS)
    {
        khEnhancedGraphics = newEnhanced;
        khDbgFovWiden = newFov;
        khDbgPolyHook = newHook;
        khDbgCompositeFS = newFs;
        LOG_INFO(kDbgTag, "controls updated: enhanced=%d fov=%d hook=%d fs=%d",
                 khEnhancedGraphics ? 1 : 0, khDbgFovWiden ? 1 : 0,
                 khDbgPolyHook ? 1 : 0, khDbgCompositeFS ? 1 : 0);
    }
}

// [KHMM-DBG] Log one per-stage timing window and reset the accumulators. fps/frame are measured
// wall-clock over the window; refresh/build are the CPU-side plugin stages; runframe carries the
// emulation + 3D render (incl. the polygon hook) + the in-RunFrame GL composite; polys/f is the
// number of polygons pushed through the rewrite hook per frame (geometry inflation probe).
void MelonInstance::khReportPerf()
{
    auto now = std::chrono::steady_clock::now();
    double wallMs = std::chrono::duration_cast<std::chrono::microseconds>(now - khStatWallStart).count() / 1000.0;
    int frames = khStatFrames > 0 ? khStatFrames : 1;
    double fps = wallMs > 0.0 ? (frames * 1000.0 / wallMs) : 0.0;
    double frameMs = wallMs / frames;
    double refreshMs = (khStatRefreshNs / 1.0e6) / frames;
    double buildMs = (khStatBuildNs / 1.0e6) / frames;
    double runMs = (khStatRunFrameNs / 1.0e6) / frames;

    uint32_t polyCalls = 0;
    uint32_t shapeCount = 0;
    if (plugin != nullptr)
    {
        polyCalls = plugin->debugTakePolyHookCalls();
        shapeCount = plugin->debugShapeCount3D();
    }
    double polysPerFrame = (double) polyCalls / frames;

    // [KHMM-DBG] GL render+composite time (runs inside RunFrame); the remainder of runframe is
    // ~emulation. Splits the mystery cost into CPU-emulation vs GL-render.
    uint64_t glNanos = g_khGlRenderNanos.exchange(0, std::memory_order_relaxed);
    double glRenderMs = (glNanos / 1.0e6) / frames;
    double emuMs = runMs - glRenderMs;

    // [KHMM-DBG] fine split of glrender (see KhGlDetail in GPU_OpenGL.h); residual = untimed
    // sections (clear pass, state setup). Locates the cost inside the GL frame.
    double glDetailMs[melonDS::KH_GL_DETAIL_COUNT];
    double glDetailSum = 0.0;
    for (int i = 0; i < melonDS::KH_GL_DETAIL_COUNT; i++)
    {
        glDetailMs[i] = (g_khGlDetailNanos[i].exchange(0, std::memory_order_relaxed) / 1.0e6) / frames;
        glDetailSum += glDetailMs[i];
    }
    int glScale = g_khGlScale.load(std::memory_order_relaxed);

    const char* rend = currentRenderer == Renderer::OpenGl ? "GL"
                     : currentRenderer == Renderer::Compute ? "CS" : "SW";

    // [KHMM-DBG] drain the audio-underrun probe (written on the audio thread). empty/partial
    // are counts over the window; buf is the average SPU ring fill at read time. If these
    // spike while fps<60, in-game distortion == SPU starvation from sub-full-speed emulation.
    uint32_t aReads = khAudioReads.exchange(0, std::memory_order_relaxed);
    uint32_t aEmpty = khAudioEmpty.exchange(0, std::memory_order_relaxed);
    uint32_t aPartial = khAudioPartial.exchange(0, std::memory_order_relaxed);
    uint64_t aBufAccum = khAudioBufAccum.exchange(0, std::memory_order_relaxed);
    double avgBuf = aReads > 0 ? (double) aBufAccum / aReads : 0.0;

    // [KHMM-DBG] worst-case per-frame stats (dip characterization): max = longest runFrame body
    // this window; over = frames whose body blew the 16.9ms budget.
    double maxFrameMs = khStatMaxFrameNs / 1.0e6;

    LOG_INFO(kDbgTag,
             "fps=%.1f frame=%.1fms max=%.1fms over=%d | runframe=%.2fms (emu=%.2f glrender=%.2f) refresh=%.2f build=%.2f | gl@%dx: ubo=%.2f vram=%.2f pal=%.2f poly=%.2f draw=%.2f comp=%.2f resid=%.2f | polys/f=%.0f shapes=%u | aud: reads=%u empty=%u partial=%u buf=%.0f | enh=%d fov=%d hook=%d fs=%d rend=%s",
             fps, frameMs, maxFrameMs, khStatOverFrames, runMs, emuMs, glRenderMs, refreshMs, buildMs,
             glScale,
             glDetailMs[melonDS::KH_GL_UBO], glDetailMs[melonDS::KH_GL_VRAMTEX],
             glDetailMs[melonDS::KH_GL_PAL], glDetailMs[melonDS::KH_GL_POLY],
             glDetailMs[melonDS::KH_GL_DRAW], glDetailMs[melonDS::KH_GL_COMP],
             glRenderMs - glDetailSum,
             polysPerFrame, shapeCount,
             aReads, aEmpty, aPartial, avgBuf,
             khEnhancedGraphics ? 1 : 0, khDbgFovWiden ? 1 : 0, khDbgPolyHook ? 1 : 0,
             khDbgCompositeFS ? 1 : 0, rend);

    khStatRefreshNs = 0;
    khStatBuildNs = 0;
    khStatRunFrameNs = 0;
    khStatFrames = 0;
    khStatMaxFrameNs = 0;
    khStatOverFrames = 0;
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
    // [KHMM-DBG] audio-underrun probe (audio thread). Sample the ring fill BEFORE draining,
    // then classify this read as empty / partial / full. khReportPerf drains these atomics.
    int bufBefore = nds->SPU.GetOutputSize();
    int got = nds->SPU.ReadOutput(buffer, length);
    khAudioReads.fetch_add(1, std::memory_order_relaxed);
    khAudioBufAccum.fetch_add((uint32_t) bufBefore, std::memory_order_relaxed);
    if (got < 1)
        khAudioEmpty.fetch_add(1, std::memory_order_relaxed);
    else if (got < length)
        khAudioPartial.fetch_add(1, std::memory_order_relaxed);
    return got;
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

// [KHMM] (Re)create the KH Melon Mix plugin for the given game code and wire it to
// the console. PluginManager::load never returns null (PluginDefault fallback), so
// `plugin` is non-null for the life of the instance once this has run.
void MelonInstance::loadPlugin(u32 gameCode)
{
    if (plugin != nullptr && plugin->getGameCode() == gameCode)
        return;

    Plugins::Plugin* oldPlugin = plugin;
    plugin = Plugins::PluginManager::load(gameCode);

    // Step B: the composite shader is ported to GLES 320es, so the enhanced-graphics /
    // single-screen composite path is enabled (khEnhancedGraphics defaults true). When
    // enabled, DisableEnhancedGraphics / DisableSingleScreenMode resolve to "not
    // disabled" so the plugin runs its single-screen compositor. All other config keys
    // resolve to safe defaults.
    bool enhanced = khEnhancedGraphics;
    plugin->loadConfigs(
        [enhanced](std::string path) -> bool {
            if (!enhanced &&
                (path.find(".DisableEnhancedGraphics") != std::string::npos ||
                 path.find(".DisableSingleScreenMode") != std::string::npos))
                return true;
            return false;
        },
        [](std::string) -> int { return 0; },
        [](std::string) -> std::string { return std::string(); }
    );

    // Keep the replacement-texture (HD cutscene / texture) path OFF regardless of the
    // enhanced-graphics toggle. loadConfigs always resets DisableReplacementTextures to
    // false, so force it back to true here. Texture replacement is a separate feature
    // chunk (its own memory/asset budget and toggle) and is not part of Step B.
    if (!plugin->areReplacementTexturesDisabled())
        plugin->replacementTexturesToggle();

    plugin->setNds(nds);

    // The software 2D renderer keeps its own plugin pointer.
    static_cast<GPU2D::SoftRenderer&>(nds->GPU.GetRenderer2D()).setPlugin(plugin);

    delete oldPlugin;
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