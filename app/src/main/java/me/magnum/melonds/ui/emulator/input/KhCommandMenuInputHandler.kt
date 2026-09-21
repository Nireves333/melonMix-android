package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input

// [KHMM] 4-way touch cluster for the KH command menu addon keys (the touch counterpart of the
// physical d-pad under the recommended KH bindings)
class KhCommandMenuInputHandler(inputListener: IInputListener, enableHapticFeedback: Boolean, touchVibrator: TouchVibrator) : MultiButtonInputHandler(inputListener, enableHapticFeedback, touchVibrator) {
    override fun getTopInput() = Input.KH_COMMAND_MENU_UP
    override fun getLeftInput() = Input.KH_COMMAND_MENU_LEFT
    override fun getBottomInput() = Input.KH_COMMAND_MENU_DOWN
    override fun getRightInput() = Input.KH_COMMAND_MENU_RIGHT
}
