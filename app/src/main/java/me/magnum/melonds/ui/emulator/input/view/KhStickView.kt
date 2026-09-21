package me.magnum.melonds.ui.emulator.input.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * [KHMM] Virtual analog stick for the KH touch controls (camera stick and movement stick),
 * drawn in the app's gold-on-navy style. Tracks a single pointer from the moment it lands on
 * the view, applies a radial deadzone with rescaling (no jump at the deadzone edge) and clamps
 * to the unit circle. Emits normalized values through [listener]: x right-positive,
 * y down-positive (the KH camera JNI convention; also matches Android touch coordinates).
 */
class KhStickView(context: Context, private val knobFilled: Boolean) : View(context) {

    fun interface Listener {
        fun onStickChanged(x: Float, y: Float)
    }

    var listener: Listener? = null

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var stickX = 0f
    private var stickY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_NAVY
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GOLD
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (knobFilled) COLOR_GOLD else COLOR_NAVY
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GOLD
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val index = event.actionIndex
                    activePointerId = event.getPointerId(index)
                    updateStick(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index != -1) {
                    updateStick(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    resetStick()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetStick()
        }
        return true
    }

    private fun updateStick(touchX: Float, touchY: Float) {
        val radius = min(width, height) / 2f
        if (radius <= 0f) return

        var x = (touchX - width / 2f) / radius
        var y = (touchY - height / 2f) / radius

        val magnitude = sqrt(x * x + y * y)
        if (magnitude > 1f) {
            x /= magnitude
            y /= magnitude
        }

        stickX = x
        stickY = y
        invalidate()

        // Radial deadzone, rescaled so output ramps from 0 at the deadzone edge
        val clampedMagnitude = min(magnitude, 1f)
        if (clampedMagnitude < DEADZONE) {
            listener?.onStickChanged(0f, 0f)
        } else {
            val scale = (clampedMagnitude - DEADZONE) / (1f - DEADZONE) / clampedMagnitude
            listener?.onStickChanged(x * scale, y * scale)
        }
    }

    private fun resetStick() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        stickX = 0f
        stickY = 0f
        invalidate()
        listener?.onStickChanged(0f, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        val ringWidth = radius * 0.06f
        val knobRadius = radius * KNOB_RADIUS_FRACTION
        val travelRadius = radius - knobRadius - ringWidth

        ringPaint.strokeWidth = ringWidth
        knobRingPaint.strokeWidth = ringWidth

        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, basePaint)
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, ringPaint)

        val knobX = cx + stickX * travelRadius
        val knobY = cy + stickY * travelRadius
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius - ringWidth / 2f, knobRingPaint)
    }

    companion object {
        private const val COLOR_NAVY = 0xFF101A3C.toInt()
        private const val COLOR_GOLD = 0xFFC9A036.toInt()
        private const val DEADZONE = 0.15f
        private const val KNOB_RADIUS_FRACTION = 0.34f
    }
}
