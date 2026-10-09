package me.magnum.melonds.ui.emulator.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input

/**
 * [KHMM] Tap/hold split for a single touch button. A short tap pulses [tapInput]
 * just long enough for the core to sample it (well under the core plugin's own
 * 20-frame hold detection, so it lands as a tap there), while holding past
 * [HOLD_THRESHOLD_MS] presses [holdInput] instead and never emits the tap.
 * The tap fires on finger-up, matching the feel of the plugin's physical
 * tap/hold split on the same button.
 */
class KhTapHoldInputHandler(
    inputListener: IInputListener,
    private val tapInput: Input,
    private val holdInput: Input,
    enableHapticFeedback: Boolean,
    touchVibrator: TouchVibrator,
) : FeedbackInputHandler(inputListener, enableHapticFeedback, touchVibrator) {

    private val handler = Handler(Looper.getMainLooper())
    private var holdFired = false
    private var touchDown = false

    private val fireHold = Runnable {
        if (touchDown && !holdFired) {
            holdFired = true
            inputListener.onKeyPress(holdInput)
        }
    }
    private val releaseTapPulse = Runnable {
        inputListener.onKeyReleased(tapInput)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // flush a still-pending tap pulse so rapid taps stay distinct presses
                handler.removeCallbacks(releaseTapPulse)
                inputListener.onKeyReleased(tapInput)
                touchDown = true
                holdFired = false
                handler.postDelayed(fireHold, HOLD_THRESHOLD_MS)
                performHapticFeedback(v, HapticFeedbackType.KEY_PRESS)
            }
            MotionEvent.ACTION_UP -> {
                touchDown = false
                handler.removeCallbacks(fireHold)
                if (holdFired) {
                    inputListener.onKeyReleased(holdInput)
                } else {
                    inputListener.onKeyPress(tapInput)
                    handler.postDelayed(releaseTapPulse, TAP_PULSE_MS)
                }
                performHapticFeedback(v, HapticFeedbackType.KEY_RELEASE)
            }
            MotionEvent.ACTION_CANCEL -> {
                touchDown = false
                handler.removeCallbacks(fireHold)
                if (holdFired) {
                    inputListener.onKeyReleased(holdInput)
                }
            }
        }
        return true
    }

    companion object {
        private const val HOLD_THRESHOLD_MS = 330L // ~ the plugin's 20-frame physical hold
        private const val TAP_PULSE_MS = 80L       // ~5 frames: sampled for sure, far from a hold
    }
}
