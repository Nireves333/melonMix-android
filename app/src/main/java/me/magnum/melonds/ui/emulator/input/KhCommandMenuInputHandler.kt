package me.magnum.melonds.ui.emulator.input

import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input

/**
 * [KHMM] Touch handler for the command-menu strip (the touch counterpart of the physical
 * d-pad under the recommended KH bindings). The control is a vertical scroll pill with
 * back/enter side ears; the hit zones below mirror the kh_command_menu.png proportions
 * (pill x 0.30-0.70 split at half height, ears y 0.28-0.72) with a little slack. One zone
 * is active at a time — the command menu is never navigated in two directions at once.
 */
class KhCommandMenuInputHandler(inputListener: IInputListener, enableHapticFeedback: Boolean, touchVibrator: TouchVibrator) : FeedbackInputHandler(inputListener, enableHapticFeedback, touchVibrator) {

    private var currentInput: Input? = null

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (v.width > 0 && v.height > 0) {
                    setCurrentInput(v, zoneAt(event.x / v.width, event.y / v.height))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setCurrentInput(v, null)
        }
        return true
    }

    private fun zoneAt(x: Float, y: Float): Input? {
        return when {
            x < EAR_EDGE && y in EAR_TOP..EAR_BOTTOM -> Input.KH_COMMAND_MENU_LEFT
            x > 1f - EAR_EDGE && y in EAR_TOP..EAR_BOTTOM -> Input.KH_COMMAND_MENU_RIGHT
            x in PILL_LEFT..PILL_RIGHT -> if (y < 0.5f) Input.KH_COMMAND_MENU_UP else Input.KH_COMMAND_MENU_DOWN
            else -> null
        }
    }

    private fun setCurrentInput(view: View, input: Input?) {
        if (input == currentInput) {
            return
        }
        currentInput?.let {
            inputListener.onKeyReleased(it)
            performHapticFeedback(view, HapticFeedbackType.KEY_RELEASE)
        }
        input?.let {
            inputListener.onKeyPress(it)
            performHapticFeedback(view, HapticFeedbackType.KEY_PRESS)
        }
        currentInput = input
    }

    companion object {
        private const val EAR_EDGE = 0.32f
        private const val EAR_TOP = 0.24f
        private const val EAR_BOTTOM = 0.76f
        private const val PILL_LEFT = 0.28f
        private const val PILL_RIGHT = 0.72f
    }
}
