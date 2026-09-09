package me.magnum.melonds.ui.emulator.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.InputConfig
import kotlin.math.absoluteValue

class InputProcessor(private val controllerConfiguration: ControllerConfiguration, private val systemInputListener: IInputListener, private val frontendInputListener: IInputListener) : INativeInputListener {

    private val axisStates: Map<Axis, AxisState>

    // [KHMM] camera-stick bindings: axis assignments feed their analog magnitude, key
    // assignments count as full deflection while held. Handled entirely here (pushed to
    // MelonEmulator.setKhCameraAxes) and never delivered to the input listeners. With no
    // camera input bound at all, the right stick (AXIS_Z/RZ, the standard Android mapping,
    // otherwise unused by the app) drives the camera as a default.
    private val khCameraAxisBindings: List<KhCameraAxisBinding>
    private val khCameraHasBindings: Boolean
    private val khCameraMagnitudes = FloatArray(4)

    init {
        val axis = controllerConfiguration.inputMapper.filterNot { inputConfig ->
            // [KHMM] camera inputs are analog — keep them out of the threshold-toggle states
            inputConfig.input.isKhCameraInput
        }.flatMap { inputConfig ->
            listOf(inputConfig.assignment, inputConfig.altAssignment)
        }.mapNotNull { assignment ->
            (assignment as? InputConfig.Assignment.Axis)?.let {
                Axis(it.deviceId, it.axisCode, it.direction)
            }
        }

        axisStates = axis.associateWith { AxisState(0f, false) }

        val cameraConfigs = controllerConfiguration.inputMapper.filter { it.input.isKhCameraInput }
        khCameraHasBindings = cameraConfigs.any { it.hasKeyAssigned() }
        khCameraAxisBindings = cameraConfigs.flatMap { inputConfig ->
            listOf(inputConfig.assignment, inputConfig.altAssignment).mapNotNull { assignment ->
                (assignment as? InputConfig.Assignment.Axis)?.let {
                    KhCameraAxisBinding(it.deviceId, it.axisCode, it.direction, inputConfig.input.khCameraDirection)
                }
            }
        }
    }

    override fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        val input = controllerConfiguration.keyToInput(keyEvent.keyCode) ?: return false
        // [KHMM] camera direction bound to a digital control: full deflection while held
        if (input.isKhCameraInput) {
            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> khSetCameraMagnitude(input.khCameraDirection, 1f)
                KeyEvent.ACTION_UP -> khSetCameraMagnitude(input.khCameraDirection, 0f)
                else -> return false
            }
            return true
        }
        // [KHMM] KH addon inputs need press AND release delivered to the emulator (they are
        // held-state masks natively), so they ride the system-input path; MelonTouchHandler
        // dispatches them to their own native channel
        if (input.isSystemInput || input.isKhInput) {
            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    systemInputListener.onKeyPress(input)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    systemInputListener.onKeyReleased(input)
                    return true
                }
            }
        } else {
            when (keyEvent.action) {
                KeyEvent.ACTION_DOWN -> {
                    frontendInputListener.onKeyPress(input)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    frontendInputListener.onKeyReleased(input)
                    return true
                }
            }
        }
        return false
    }

    override fun onMotionEvent(motionEvent: MotionEvent): Boolean {
        if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            // [KHMM] KH camera: bound camera inputs feed their axes' analog magnitudes;
            // unbound = right-stick default. Deadzone and quantization happen natively,
            // and the value is a no-op outside the KH games.
            if (khCameraHasBindings) {
                var khCameraChanged = false
                khCameraAxisBindings.forEach { binding ->
                    if (binding.deviceId == null || binding.deviceId == motionEvent.deviceId) {
                        val value = motionEvent.getAxisValue(binding.axisCode)
                        val clampedValue = when (binding.direction) {
                            InputConfig.Assignment.Axis.Direction.POSITIVE -> value.coerceAtLeast(0f)
                            InputConfig.Assignment.Axis.Direction.NEGATIVE -> value.coerceAtMost(0f)
                        }
                        khCameraMagnitudes[binding.cameraDirection] = clampedValue.absoluteValue
                        khCameraChanged = true
                    }
                }
                if (khCameraChanged) {
                    pushKhCameraAxes()
                }
            } else {
                MelonEmulator.setKhCameraAxes(
                    motionEvent.getAxisValue(MotionEvent.AXIS_Z),
                    motionEvent.getAxisValue(MotionEvent.AXIS_RZ),
                )
            }

            val deviceAxis = axisStates.filterKeys { it.deviceId == null || it.deviceId == motionEvent.deviceId }
            deviceAxis.forEach {
                val axis = it.key
                val axisState = it.value

                val newValue = motionEvent.getAxisValue(axis.axisCode)
                val clampedValue = when (axis.direction) {
                    InputConfig.Assignment.Axis.Direction.POSITIVE -> newValue.coerceAtLeast(0f)
                    InputConfig.Assignment.Axis.Direction.NEGATIVE -> newValue.coerceAtMost(0f)
                }

                if (axisState.shouldToggleFor(newValue = clampedValue)) {
                    controllerConfiguration.axisToInput(axis.axisCode, axis.direction)?.let { input ->
                        if (axisState.active) {
                            axisState.active = false
                            if (input.isSystemInput || input.isKhInput) {
                                systemInputListener.onKeyReleased(input)
                            } else {
                                frontendInputListener.onKeyReleased(input)
                            }
                        } else {
                            axisState.active = true
                            if (input.isSystemInput || input.isKhInput) {
                                systemInputListener.onKeyPress(input)
                            } else {
                                frontendInputListener.onKeyPress(input)
                            }
                        }
                    }
                }
                axisState.value = clampedValue
            }
            return deviceAxis.isNotEmpty()
        } else {
            return false
        }
    }

    // [KHMM] camera helpers: magnitudes indexed by Input.khCameraDirection (0=right, 1=left,
    // 2=down, 3=up); pushed as signed axes, y down-positive (the native JNI convention)
    private fun khSetCameraMagnitude(cameraDirection: Int, magnitude: Float) {
        khCameraMagnitudes[cameraDirection] = magnitude
        pushKhCameraAxes()
    }

    private fun pushKhCameraAxes() {
        MelonEmulator.setKhCameraAxes(
            khCameraMagnitudes[0] - khCameraMagnitudes[1],
            khCameraMagnitudes[2] - khCameraMagnitudes[3],
        )
    }

    private data class Axis(
        val deviceId: Int?,
        val axisCode: Int,
        val direction: InputConfig.Assignment.Axis.Direction,
    )

    // [KHMM]
    private data class KhCameraAxisBinding(
        val deviceId: Int?,
        val axisCode: Int,
        val direction: InputConfig.Assignment.Axis.Direction,
        val cameraDirection: Int,
    )

    private data class AxisState(
        var value: Float,
        var active: Boolean,
    ) {
        fun shouldToggleFor(newValue: Float): Boolean {
            return if (active) {
                newValue.absoluteValue < 0.5f
            } else {
                newValue.absoluteValue >= 0.5f
            }
        }
    }
}