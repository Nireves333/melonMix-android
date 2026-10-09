package me.magnum.melonds.impl.input

import android.view.KeyEvent
import android.view.MotionEvent
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig

/**
 * [KHMM] Desktop KH Melon Mix's recommended controller layout
 * (KingdomHeartsHDCollection::applyJoystickMappings, SDL names → Android key/axis codes):
 * face buttons with the deliberate X↔Y swap, DS L on L1, lock-on on R1, switch target on
 * the triggers, command menu on the physical d-pad, HUD toggle on L3, map toggle on
 * Select, map/tree enlarge on R3, camera on the right stick — and DS movement MOVED to
 * the left stick, because keyToInput resolves DS buttons before KH inputs, so the d-pad
 * must be free for the command menu (outside KH gameplay the plugin passes those through
 * as a plain d-pad). DS R and Select are left unbound like desktop (their functions moved
 * to R1/Select). Rows not in this map (pause, fast-forward, save states...) are left as
 * they are.
 *
 * Used by the "KH layout" button in input setup AND as the fresh-install default
 * (DefaultControllerConfigurationFactory) — keep it the single source of truth.
 */
object KhRecommendedBindings {

    private fun key(keyCode: Int) = InputConfig.Assignment.Key(null, keyCode)
    private fun axis(axisCode: Int, direction: InputConfig.Assignment.Axis.Direction) =
        InputConfig.Assignment.Axis(null, axisCode, direction)

    private val pos = InputConfig.Assignment.Axis.Direction.POSITIVE
    private val neg = InputConfig.Assignment.Axis.Direction.NEGATIVE
    private val none = InputConfig.Assignment.None

    // Key primary + axis alternate where controllers report the control either way
    // (d-pads as DPAD keycodes vs. HAT axes, triggers as L2/R2 keycodes vs. trigger axes).
    private val recommended = mapOf(
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
        Input.KH_VIEW_ENLARGE_TOGGLE to (key(KeyEvent.KEYCODE_BUTTON_THUMBR) to none),
        Input.KH_CAMERA_UP to (axis(MotionEvent.AXIS_RZ, neg) to none),
        Input.KH_CAMERA_DOWN to (axis(MotionEvent.AXIS_RZ, pos) to none),
        Input.KH_CAMERA_LEFT to (axis(MotionEvent.AXIS_Z, neg) to none),
        Input.KH_CAMERA_RIGHT to (axis(MotionEvent.AXIS_Z, pos) to none),
    )

    fun applyTo(configs: List<InputConfig>): List<InputConfig> {
        return configs.map { current ->
            recommended[current.input]?.let { (primary, secondary) ->
                current.copy(assignment = primary, altAssignment = secondary)
            } ?: current
        }
    }
}
