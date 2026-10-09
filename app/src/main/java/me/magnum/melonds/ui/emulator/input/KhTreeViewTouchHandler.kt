package me.magnum.melonds.ui.emulator.input

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.MelonEmulator.onScreenRelease
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Point

/**
 * [KHMM] Touch passthrough for the Re:Coded single-screen ENLARGED tree view. While the
 * composite shows the whole DS bottom screen (the overclock tree) as a box in the
 * top-screen view, touches inside that box map straight to DS touchscreen coordinates,
 * so the tree scrolls natively and its tabs (including the MAP tab) are tappable. The
 * box geometry is NOT assumed here: the lib reports it with the tree-view event (as
 * fractions of the view), so lib-side shape tuning can never desync the touch mapping.
 * Inert while [enabled] is false — in the map view the DS touchscreen acts as a camera
 * control, and no touch may ever leak into it from here.
 */
class KhTreeViewTouchHandler(inputListener: IInputListener) : BaseInputHandler(inputListener) {
    private val touchPoint = Point()
    private var touching = false

    // The tree box as fractions of the view (left, top, width, height), from the lib
    private var boxFractionX = 0f
    private var boxFractionY = 0f
    private var boxFractionWidth = 0f
    private var boxFractionHeight = 0f

    var enabled = false
        set(value) {
            if (field && !value && touching) {
                // The tree closed under the finger (tab tapped, scene change): retract the
                // touch or the DS keeps seeing a pen-down over whatever replaced the tree
                touching = false
                inputListener.onKeyReleased(Input.TOUCHSCREEN)
                onScreenRelease()
            }
            field = value
        }

    fun setBox(x: Float, y: Float, width: Float, height: Float) {
        boxFractionX = x
        boxFractionY = y
        boxFractionWidth = width
        boxFractionHeight = height
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!enabled) {
            return false
        }

        val boxLeft = boxFractionX * v.width
        val boxTop = boxFractionY * v.height
        val boxWidth = boxFractionWidth * v.width
        val boxHeight = boxFractionHeight * v.height
        if (boxWidth <= 0f || boxHeight <= 0f) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < boxLeft || event.x > boxLeft + boxWidth ||
                    event.y < boxTop || event.y > boxTop + boxHeight) {
                    return false
                }
                touching = true
                inputListener.onKeyPress(Input.TOUCHSCREEN)
                inputListener.onTouch(mapToDsCoordinates(event, boxLeft, boxTop, boxWidth, boxHeight))
            }
            MotionEvent.ACTION_MOVE -> {
                if (touching) {
                    inputListener.onTouch(mapToDsCoordinates(event, boxLeft, boxTop, boxWidth, boxHeight))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touching) {
                    touching = false
                    inputListener.onKeyReleased(Input.TOUCHSCREEN)
                    onScreenRelease()
                }
            }
        }
        return true
    }

    private fun mapToDsCoordinates(event: MotionEvent, boxLeft: Float, boxTop: Float, boxWidth: Float, boxHeight: Float): Point {
        touchPoint.x = ((event.x - boxLeft) / boxWidth * 256).toInt().coerceIn(0, 255)
        touchPoint.y = ((event.y - boxTop) / boxHeight * 192).toInt().coerceIn(0, 191)
        return touchPoint
    }
}
