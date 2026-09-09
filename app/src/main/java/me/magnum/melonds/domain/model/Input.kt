package me.magnum.melonds.domain.model

/**
 * Input representation that is assigned to the given key code. If the input does not represent a
 * system input (i.e., it represents additional functionality offered by the emulator), a key code
 * of -1 must be used.
 *
 * @param keyCode The key code that the input represents in the system or -1 if it is not assigned
 * to any system input
 * @param khAddonAction [KHMM] KH Melon Mix addon-key action ordinal, or -1. The ordinal is the
 * contract with the native side (kKhAddonKeyNames in MelonInstance.cpp — same order); natively it
 * is translated to the loaded game's plugin addon-key bit, so these inputs do nothing outside the
 * KH games. Routed to MelonEmulator.onKhInputDown/Up instead of the DS key mask.
 * @param khCameraDirection [KHMM] KH camera-stick direction (0=right, 1=left, 2=down, 3=up), or
 * -1. Analog: an axis assignment feeds its magnitude, a key assignment counts as full deflection.
 * Handled entirely inside InputProcessor (MelonEmulator.setKhCameraAxes), never reaches the
 * input listeners. Unbound = the right stick (AXIS_Z/RZ) drives the camera as a default.
 */
enum class Input(val keyCode: Int, val khAddonAction: Int = -1, val khCameraDirection: Int = -1) {
    A(0),
    B(1),
    SELECT(2),
    START(3),
    RIGHT(4),
    LEFT(5),
    UP(6),
    DOWN(7),
    R(8),
    L(9),
    X(10),
    Y(11),
    DEBUG(16 + 3),
    TOUCHSCREEN(16 + 6),
    HINGE(16 + 7),
    PAUSE(-1),
    FAST_FORWARD(-1),
    MICROPHONE(-1),
    RESET(-1),
    TOGGLE_SOFT_INPUT(-1),
    SWAP_SCREENS(-1),
    QUICK_SAVE(-1),
    QUICK_LOAD(-1),
    REWIND(-1),
    KH_SWITCH_TARGET_LEFT(-1, 0),
    KH_SWITCH_TARGET_RIGHT(-1, 1),
    KH_LOCK_ON(-1, 2),
    KH_COMMAND_MENU_LEFT(-1, 3),
    KH_COMMAND_MENU_RIGHT(-1, 4),
    KH_COMMAND_MENU_UP(-1, 5),
    KH_COMMAND_MENU_DOWN(-1, 6),
    KH_HUD_TOGGLE(-1, 7),
    KH_FULLSCREEN_MAP_TOGGLE(-1, 8),
    KH_CAMERA_RIGHT(-1, khCameraDirection = 0),
    KH_CAMERA_LEFT(-1, khCameraDirection = 1),
    KH_CAMERA_DOWN(-1, khCameraDirection = 2),
    KH_CAMERA_UP(-1, khCameraDirection = 3);

    val isSystemInput: Boolean
        get() = keyCode != -1

    // [KHMM] see khAddonAction
    val isKhInput: Boolean
        get() = khAddonAction != -1

    // [KHMM] see khCameraDirection
    val isKhCameraInput: Boolean
        get() = khCameraDirection != -1

    companion object {
        val SYSTEM_BUTTONS = listOf(A, B, X, Y, L, R, START, SELECT, LEFT, RIGHT, UP, DOWN)
    }
}