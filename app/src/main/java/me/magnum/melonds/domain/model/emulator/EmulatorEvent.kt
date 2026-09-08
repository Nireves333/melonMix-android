package me.magnum.melonds.domain.model.emulator

sealed class EmulatorEvent {
    data class RumbleStart(val duration: Int) : EmulatorEvent()
    data object RumbleStop : EmulatorEvent()
    // [KHMM] KH pause-menu overlay snapshot (see KhPauseMenuState)
    data class KhPauseMenu(val state: KhPauseMenuState) : EmulatorEvent()
    // [KHMM] KH HD replacement cutscene video player start/dismiss (see KhCutsceneState)
    data class KhCutscene(val state: KhCutsceneState?) : EmulatorEvent()
    // [KHMM] KH cutscene skip menu sound (1=enter, 2=move, 3=continue, 4=select)
    data class KhMenuSound(val soundId: Int) : EmulatorEvent()
    data class Stop(val reason: Reason) : EmulatorEvent() {
        enum class Reason {
            GBAModeNotSupported,
            BadExceptionRegion,
            PowerOff,
        }
    }
}