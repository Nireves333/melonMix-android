package me.magnum.melonds.ui.emulator.input

import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.ui.emulator.input.view.KhStickView

/**
 * [KHMM] Quantizes a virtual stick to 8-way d-pad presses (the DS has no analog input, and the
 * KH plugin's own left-stick handling on desktop is the same digital conversion). Per-direction
 * hysteresis avoids flicker at the threshold edge.
 */
class KhStickDpadAdapter(private val inputListener: IInputListener) : KhStickView.Listener {

    private val pressed = BooleanArray(4)

    override fun onStickChanged(x: Float, y: Float) {
        updateDirection(0, x >= PRESS_THRESHOLD, x >= RELEASE_THRESHOLD, Input.RIGHT)
        updateDirection(1, -x >= PRESS_THRESHOLD, -x >= RELEASE_THRESHOLD, Input.LEFT)
        updateDirection(2, y >= PRESS_THRESHOLD, y >= RELEASE_THRESHOLD, Input.DOWN)
        updateDirection(3, -y >= PRESS_THRESHOLD, -y >= RELEASE_THRESHOLD, Input.UP)
    }

    private fun updateDirection(index: Int, abovePress: Boolean, aboveRelease: Boolean, input: Input) {
        if (!pressed[index] && abovePress) {
            pressed[index] = true
            inputListener.onKeyPress(input)
        } else if (pressed[index] && !aboveRelease) {
            pressed[index] = false
            inputListener.onKeyReleased(input)
        }
    }

    companion object {
        private const val PRESS_THRESHOLD = 0.4f
        private const val RELEASE_THRESHOLD = 0.3f
    }
}
