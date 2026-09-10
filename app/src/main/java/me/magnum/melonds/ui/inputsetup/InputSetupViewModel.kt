package me.magnum.melonds.ui.inputsetup

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.utils.EventSharedFlow
import javax.inject.Inject

@HiltViewModel
class InputSetupViewModel @Inject constructor(private val settingsRepository: SettingsRepository) : ViewModel() {

    private val _inputConfig = MutableStateFlow(settingsRepository.getControllerConfiguration().inputMapper)
    val inputConfiguration = _inputConfig.asStateFlow()

    private val _inputUnderAssignment = MutableStateFlow<Input?>(null)
    val inputUnderAssignment = _inputUnderAssignment.asStateFlow()

    private val _onInputAssignedEvent = EventSharedFlow<Input>()
    val onInputAssignedEvent = _onInputAssignedEvent.asSharedFlow()

    fun startInputAssignment(input: Input) {
        _inputUnderAssignment.value = input
    }

    fun stopInputAssignment() {
        _inputUnderAssignment.value = null
    }

    fun updateInputAssignedKey(key: Int) {
        val inputUnderAssignment = _inputUnderAssignment.value ?: return
        val inputType = InputConfig.Assignment.Key(null, key)
        setInputAssignment(inputUnderAssignment, inputType)
        focusOnNextInput(inputUnderAssignment)
    }

    fun updateInputAssignedAxis(axis: Int, direction: InputConfig.Assignment.Axis.Direction) {
        val inputUnderAssignment = _inputUnderAssignment.value ?: return
        val inputType = InputConfig.Assignment.Axis(null, axis, direction)
        setInputAssignment(inputUnderAssignment, inputType)
        focusOnNextInput(inputUnderAssignment)
    }

    fun clearInputAssignment(input: Input) {
        setInputAssignment(input, InputConfig.Assignment.None)
        _inputUnderAssignment.value = null
    }

    private fun setInputAssignment(input: Input, assignment: InputConfig.Assignment) {
        val inputIndex = _inputConfig.value.indexOfFirst { it.input == input }
        if (inputIndex >= 0) {
            _inputConfig.update { config ->
                config.toMutableList().apply {
                    val current = this[inputIndex]
                    var primary = current.assignment
                    var secondary = current.altAssignment
                    when (assignment) {
                        InputConfig.Assignment.None -> {
                            primary = InputConfig.Assignment.None
                            secondary = InputConfig.Assignment.None
                        }
                        is InputConfig.Assignment.Key -> {
                            if (primary == InputConfig.Assignment.None || primary == assignment) {
                                primary = assignment
                            } else if (secondary == InputConfig.Assignment.None || secondary == assignment) {
                                secondary = assignment
                            } else {
                                secondary = assignment
                            }
                        }
                        is InputConfig.Assignment.Axis -> {
                            if (primary == InputConfig.Assignment.None|| primary == assignment) {
                                primary = assignment
                            } else if (secondary == InputConfig.Assignment.None || secondary == assignment) {
                                secondary = assignment
                            } else {
                                secondary = assignment
                            }
                        }
                    }
                    this[inputIndex] = current.copy(assignment = primary, altAssignment = secondary)
                }.also {
                    onConfigsChanged(it)
                }
            }
        }
        _inputUnderAssignment.value = null
    }

    // [KHMM] One-tap port of desktop KH Melon Mix's recommended controller layout
    // (KingdomHeartsHDCollection::applyJoystickMappings, SDL names → Android key/axis codes):
    // face buttons with the deliberate X↔Y swap, DS L on L1, lock-on on R1, switch target on
    // the triggers, command menu on the physical d-pad, HUD toggle on L3, map toggle on
    // Select, camera on the right stick — and DS movement MOVED to the left stick, because
    // keyToInput resolves DS buttons before KH inputs, so the d-pad must be free for the
    // command menu (outside KH gameplay the plugin passes those through as a plain d-pad).
    // DS R and Select are left unbound like desktop (their functions moved to R1/Select).
    // Rows not in this map (pause, fast-forward, save states...) keep the user's bindings.
    fun applyRecommendedKhBindings() {
        fun key(keyCode: Int) = InputConfig.Assignment.Key(null, keyCode)
        fun axis(axisCode: Int, direction: InputConfig.Assignment.Axis.Direction) =
            InputConfig.Assignment.Axis(null, axisCode, direction)
        val pos = InputConfig.Assignment.Axis.Direction.POSITIVE
        val neg = InputConfig.Assignment.Axis.Direction.NEGATIVE
        val none = InputConfig.Assignment.None

        // Key primary + axis alternate where controllers report the control either way
        // (d-pads as DPAD keycodes vs. HAT axes, triggers as L2/R2 keycodes vs. trigger axes).
        val recommended = mapOf(
            Input.A to (key(KeyEvent.KEYCODE_BUTTON_A) to none),
            Input.B to (key(KeyEvent.KEYCODE_BUTTON_B) to none),
            Input.Y to (key(KeyEvent.KEYCODE_BUTTON_X) to none),
            Input.X to (key(KeyEvent.KEYCODE_BUTTON_Y) to none),
            Input.L to (key(KeyEvent.KEYCODE_BUTTON_L1) to none),
            Input.R to (none to none),
            Input.SELECT to (none to none),
            Input.START to (key(KeyEvent.KEYCODE_BUTTON_START) to none),
            Input.UP to (axis(MotionEvent.AXIS_Y, neg) to none),
            Input.DOWN to (axis(MotionEvent.AXIS_Y, pos) to none),
            Input.LEFT to (axis(MotionEvent.AXIS_X, neg) to none),
            Input.RIGHT to (axis(MotionEvent.AXIS_X, pos) to none),
            Input.KH_LOCK_ON to (key(KeyEvent.KEYCODE_BUTTON_R1) to none),
            Input.KH_SWITCH_TARGET_LEFT to (key(KeyEvent.KEYCODE_BUTTON_L2) to axis(MotionEvent.AXIS_LTRIGGER, pos)),
            Input.KH_SWITCH_TARGET_RIGHT to (key(KeyEvent.KEYCODE_BUTTON_R2) to axis(MotionEvent.AXIS_RTRIGGER, pos)),
            Input.KH_COMMAND_MENU_UP to (key(KeyEvent.KEYCODE_DPAD_UP) to axis(MotionEvent.AXIS_HAT_Y, neg)),
            Input.KH_COMMAND_MENU_DOWN to (key(KeyEvent.KEYCODE_DPAD_DOWN) to axis(MotionEvent.AXIS_HAT_Y, pos)),
            Input.KH_COMMAND_MENU_LEFT to (key(KeyEvent.KEYCODE_DPAD_LEFT) to axis(MotionEvent.AXIS_HAT_X, neg)),
            Input.KH_COMMAND_MENU_RIGHT to (key(KeyEvent.KEYCODE_DPAD_RIGHT) to axis(MotionEvent.AXIS_HAT_X, pos)),
            Input.KH_HUD_TOGGLE to (key(KeyEvent.KEYCODE_BUTTON_THUMBL) to none),
            Input.KH_FULLSCREEN_MAP_TOGGLE to (key(KeyEvent.KEYCODE_BUTTON_SELECT) to none),
            Input.KH_CAMERA_UP to (axis(MotionEvent.AXIS_RZ, neg) to none),
            Input.KH_CAMERA_DOWN to (axis(MotionEvent.AXIS_RZ, pos) to none),
            Input.KH_CAMERA_LEFT to (axis(MotionEvent.AXIS_Z, neg) to none),
            Input.KH_CAMERA_RIGHT to (axis(MotionEvent.AXIS_Z, pos) to none),
        )

        _inputConfig.update { config ->
            config.map { current ->
                recommended[current.input]?.let { (primary, secondary) ->
                    current.copy(assignment = primary, altAssignment = secondary)
                } ?: current
            }.also {
                onConfigsChanged(it)
            }
        }
    }

    private fun onConfigsChanged(newConfig: List<InputConfig>) {
        val currentConfiguration = ControllerConfiguration(newConfig)
        settingsRepository.setControllerConfiguration(currentConfiguration)
    }

    private fun focusOnNextInput(currentInput: Input) {
        val currentInputIndex = _inputConfig.value.indexOfFirst { it.input == currentInput }
        val nextInput = _inputConfig.value.getOrNull(currentInputIndex + 1)
        if (nextInput != null) {
            _onInputAssignedEvent.tryEmit(nextInput.input)
        }
    }
}