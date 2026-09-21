package me.magnum.melonds.domain.model.layout

import me.magnum.melonds.domain.model.Input

enum class LayoutComponent(val matchingInputs: List<Input>) {
    TOP_SCREEN(emptyList()),
    BOTTOM_SCREEN(emptyList()),
    DPAD(listOf(Input.UP, Input.DOWN, Input.LEFT, Input.RIGHT)),
    BUTTONS(listOf(Input.A, Input.B, Input.X, Input.Y)),
    BUTTON_START(listOf(Input.START)),
    BUTTON_SELECT(listOf(Input.SELECT)),
    BUTTON_L(listOf(Input.L)),
    BUTTON_R(listOf(Input.R)),
    BUTTON_HINGE(listOf(Input.HINGE)),
    BUTTON_FAST_FORWARD_TOGGLE(listOf(Input.FAST_FORWARD)),
    BUTTON_TOGGLE_SOFT_INPUT(listOf(Input.TOGGLE_SOFT_INPUT)),
    BUTTON_RESET(listOf(Input.RESET)),
    BUTTON_PAUSE(listOf(Input.PAUSE)),
    BUTTON_SWAP_SCREENS(listOf(Input.SWAP_SCREENS)),
    BUTTON_QUICK_SAVE(listOf(Input.QUICK_SAVE)),
    BUTTON_QUICK_LOAD(listOf(Input.QUICK_LOAD)),
    BUTTON_REWIND(listOf(Input.REWIND)),
    BUTTON_MICROPHONE_TOGGLE(listOf(Input.MICROPHONE)),
    // [KHMM] KH Melon Mix touch controls. Only functional while the KH plugin drives the loaded
    // game; RuntimeLayoutView hides them otherwise (see setKhControlsEnabled)
    KH_BUTTON_LOCK_ON(listOf(Input.KH_LOCK_ON)),
    KH_BUTTON_SWITCH_TARGET_LEFT(listOf(Input.KH_SWITCH_TARGET_LEFT)),
    KH_BUTTON_SWITCH_TARGET_RIGHT(listOf(Input.KH_SWITCH_TARGET_RIGHT)),
    KH_COMMAND_MENU(listOf(Input.KH_COMMAND_MENU_UP, Input.KH_COMMAND_MENU_DOWN, Input.KH_COMMAND_MENU_LEFT, Input.KH_COMMAND_MENU_RIGHT)),
    KH_BUTTON_HUD_TOGGLE(listOf(Input.KH_HUD_TOGGLE)),
    KH_BUTTON_MAP_TOGGLE(listOf(Input.KH_FULLSCREEN_MAP_TOGGLE));

    fun isScreen(): Boolean {
        return this == TOP_SCREEN || this == BOTTOM_SCREEN
    }

    // [KHMM]
    fun isKhComponent(): Boolean {
        return matchingInputs.any { it.isKhInput || it.isKhCameraInput }
    }
}