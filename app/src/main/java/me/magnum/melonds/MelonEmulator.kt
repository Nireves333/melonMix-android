package me.magnum.melonds

import android.net.Uri
import me.magnum.melonds.common.camera.DSiCameraSource
import me.magnum.melonds.domain.model.Cheat
import me.magnum.melonds.domain.model.EmulatorConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.retroachievements.RASimpleAchievement
import me.magnum.melonds.domain.model.retroachievements.RASimpleLeaderboard
import me.magnum.melonds.domain.model.retroachievements.RASimpleRuntimeAchievement
import me.magnum.melonds.ui.emulator.render.FrameRenderCallback
import me.magnum.melonds.ui.emulator.rewind.model.RewindSaveState
import me.magnum.melonds.ui.emulator.rewind.model.RewindWindow
import java.nio.ByteBuffer

object MelonEmulator {
    enum class LoadResult(val isTerminal: Boolean) {
        SUCCESS(false),
        SUCCESS_GBA_FAILED(false),
        NDS_FAILED(true),
        BIOS_FAILED(true)
    }

    enum class FirmwareLoadResult {
        SUCCESS,
        BIOS9_MISSING,
        BIOS9_BAD,
        BIOS7_MISSING,
        BIOS7_BAD,
        FIRMWARE_MISSING,
        FIRMWARE_BAD,
        FIRMWARE_NOT_BOOTABLE,
        DSI_BIOS9_MISSING,
        DSI_BIOS9_BAD,
        DSI_BIOS7_MISSING,
        DSI_BIOS7_BAD,
        DSI_NAND_MISSING,
        DSI_NAND_BAD
    }

    enum class GbaSlotType {
        NONE,
        GBA_ROM,
        RUMBLE_PAK,
        MEMORY_EXPANSION,
        MOTION_PAK_HOMEBREW,
        MOTION_PAK_RETAIL,
    }

	external fun setupEmulator(
        emulatorConfiguration: EmulatorConfiguration,
        dsiCameraSource: DSiCameraSource?,
        screenshotBuffer: ByteBuffer,
    )

    external fun setupCheats(cheats: Array<Cheat>)

    external fun setupAchievements(achievements: Array<RASimpleAchievement>, leaderboards: Array<RASimpleLeaderboard>, richPresenceScript: String?)

    external fun unloadRetroAchievementsData()

    external fun getRichPresenceStatus(): String?

    external fun getRuntimeAchievements(): Array<RASimpleRuntimeAchievement>

	fun loadRom(romUri: Uri, sramUri: Uri, gbaSlotType: GbaSlotType, gbaRomUri: Uri?, gbaSramUri: Uri?): LoadResult {
        val loadResult = loadRomInternal(romUri.toString(), sramUri.toString(), gbaSlotType.ordinal, gbaRomUri?.toString(), gbaSramUri?.toString())
        return when (loadResult) {
            0 -> LoadResult.SUCCESS
            1 -> LoadResult.SUCCESS_GBA_FAILED
            2 -> LoadResult.NDS_FAILED
            3 -> LoadResult.BIOS_FAILED
            else -> throw RuntimeException("Unknown load result")
        }
    }

    fun bootFirmware(): FirmwareLoadResult {
        val loadResult = bootFirmwareInternal()
        return FirmwareLoadResult.entries[loadResult]
    }

    private external fun loadRomInternal(romPath: String, sramPath: String, gbaSlotType: Int, gbaRomPath: String?, gbaSramPath: String?): Int

    private external fun bootFirmwareInternal(): Int

	external fun startEmulation()

    external fun presentFrame(deadlineNs: Long, frameRenderCallback: FrameRenderCallback)

	external fun getFPS(): Float

	external fun pauseEmulation()

	external fun resumeEmulation()

    external fun resetEmulation()

	external fun stopEmulation()

    fun saveState(path: Uri): Boolean {
        return saveStateInternal(path.toString())
    }

    private external fun saveStateInternal(path: String): Boolean

    fun loadState(path: Uri): Boolean {
        return loadStateInternal(path.toString())
    }

    private external fun loadStateInternal(path: String): Boolean

    external fun loadRewindState(rewindSaveState: RewindSaveState): Boolean

    external fun getRewindWindow(): RewindWindow

	external fun onScreenTouch(x: Int, y: Int)

	external fun onScreenRelease()

	fun onInputDown(input: Input) {
        onKeyPress(input.keyCode)
    }

	fun onInputUp(input: Input) {
        onKeyRelease(input.keyCode)
    }

    // [KHMM] KH Melon Mix addon keys (lock-on, switch target, command menu...) — a separate
    // channel from the DS key mask; the native side maps the action ordinal to the loaded
    // game's plugin addon-key bit (no-op outside the KH games)
    fun onKhInputDown(input: Input) {
        if (input.isKhInput) onKhAddonKey(input.khAddonAction, true)
    }

    fun onKhInputUp(input: Input) {
        if (input.isKhInput) onKhAddonKey(input.khAddonAction, false)
    }

    private external fun onKhAddonKey(action: Int, down: Boolean)

    // [KHMM] KH camera stick axes, x right-positive / y down-positive, each in [-1, 1]
    // (quantized natively; no-op outside the KH games)
    external fun setKhCameraAxes(x: Float, y: Float)

    private external fun onKeyPress(key: Int)

    private external fun onKeyRelease(key: Int)

    external fun takeScreenshot(): Boolean

    external fun setFastForwardEnabled(enabled: Boolean)

    external fun setMicrophoneEnabled(enabled: Boolean)

    external fun updateEmulatorConfiguration(emulatorConfiguration: EmulatorConfiguration)

    // [KHMM] real aspect ratio of the on-screen top-screen viewport (single-screen composite)
    external fun setDisplayAspectRatio(aspectRatio: Float)

    // [KHMM] true if the KH Melon Mix plugin supports this gamecode (little-endian packed)
    external fun isEnhancedGameCode(gameCode: Int): Boolean

    // [KHMM] directory containing the "assets" folder with the KH Melon Mix packs
    // (HD cutscene videos etc.) — the app-specific external files dir
    external fun setKhAssetsRoot(path: String)

    // [KHMM] HD replacement cutscene video player returns: the video finished playing
    // naturally / failed to play (the plugin then blacklists it and resumes the DS cutscene)
    external fun onKhCutsceneEnded()
    external fun onKhCutsceneFailed(error: String)

    // [KHMM] a save state was loaded while an HD replacement video plays — arms the plugin's
    // skip sequence so the video stops instead of playing out over the loaded state
    external fun onKhStateLoadedDuringCutscene()

    // [KHMM] remastered-BGM: audio pack subfolder per game (set before the ROM loads; "" =
    // none), user volume 0-100 (live), and the HD-cutscene video position used to schedule
    // video-synced BGM starts
    external fun setKhAudioPacks(daysPack: String, recodedPack: String)
    external fun setKhBgmVolume(volumePercent: Int)
    external fun setKhBgmVideoPosition(positionMs: Long)

    external fun updateMotionData(ax: Float, ay: Float, az: Float, rx: Float, ry: Float, rz: Float)
}