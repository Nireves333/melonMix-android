package me.magnum.melonds.ui.emulator

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.input.SoftInputBehaviour
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.ui.common.LayoutView
import me.magnum.melonds.ui.emulator.input.ButtonsInputHandler
import me.magnum.melonds.ui.emulator.input.DpadInputHandler
import me.magnum.melonds.ui.emulator.input.FrontendInputHandler
import me.magnum.melonds.ui.emulator.input.IInputListener
import me.magnum.melonds.ui.emulator.input.IKhCameraListener
import me.magnum.melonds.ui.emulator.input.KhCommandMenuInputHandler
import me.magnum.melonds.ui.emulator.input.KhStickDpadAdapter
import me.magnum.melonds.ui.emulator.input.view.KhStickView
import me.magnum.melonds.ui.emulator.input.SingleButtonInputHandler
import me.magnum.melonds.ui.emulator.input.TouchscreenInputHandler
import me.magnum.melonds.ui.emulator.input.view.ToggleableImageView
import me.magnum.melonds.ui.emulator.model.ConnectedControllersState
import me.magnum.melonds.ui.emulator.model.RuntimeInputLayoutConfiguration
import me.magnum.melonds.ui.layouteditor.model.LayoutTarget
import javax.inject.Inject
import kotlin.math.sqrt

@AndroidEntryPoint
class RuntimeLayoutView(context: Context, attrs: AttributeSet? = null) : LayoutView(context, attrs) {

    @Inject
    lateinit var touchVibrator: TouchVibrator

    private var currentRuntimeLayout: RuntimeInputLayoutConfiguration? = null
    private var frontendInputHandler: IInputListener? = null
    private var systemInputHandler: IInputListener? = null
    private var isSoftInputVisible = true
    private var areScreensSwapped = false
    private var connectedControllersState: ConnectedControllersState = ConnectedControllersState.NoControllers
    // [KHMM] whether the KH plugin drives the loaded game; KH touch components are hidden otherwise
    private var khControlsEnabled = false
    // [KHMM] whether the forced single-screen layout is active (swap-screens is meaningless then)
    private var khSingleScreenActive = false
    // [KHMM] touch-stick deadzone from the input settings
    private var khStickDeadzone = KhStickView.DEFAULT_DEADZONE

    fun setFrontendInputHandler(frontendInputHandler: FrontendInputHandler) {
        this.frontendInputHandler = frontendInputHandler
        updateInputs()
    }

    fun setSystemInputHandler(systemInputHandler: IInputListener) {
        this.systemInputHandler = systemInputHandler
        updateInputs()
    }

    fun setConnectedControllersState(state: ConnectedControllersState) {
        connectedControllersState = state
        updateVisibility()
    }

    // [KHMM]
    fun setKhControlsEnabled(enabled: Boolean) {
        if (khControlsEnabled != enabled) {
            khControlsEnabled = enabled
            updateVisibility()
        }
    }

    // [KHMM]
    fun setKhSingleScreenActive(active: Boolean) {
        if (khSingleScreenActive != active) {
            khSingleScreenActive = active
            updateVisibility()
        }
    }

    // [KHMM] applies live to already-instantiated sticks
    fun setKhStickDeadzone(deadzone: Float) {
        khStickDeadzone = deadzone
        (getLayoutComponentView(LayoutComponent.MOVEMENT_STICK)?.view as? KhStickView)?.deadzone = deadzone
        (getLayoutComponentView(LayoutComponent.KH_CAMERA_STICK)?.view as? KhStickView)?.deadzone = deadzone
    }

    fun toggleSoftInputVisibility() {
        isSoftInputVisible = !isSoftInputVisible
        setLayoutComponentToggleState(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT, isSoftInputVisible)
        updateVisibility()
    }

    fun swapScreens() {
        areScreensSwapped = !areScreensSwapped
        updateScreenInputs()
    }

    fun areScreensSwapped(): Boolean {
        return areScreensSwapped
    }

    fun setLayoutComponentToggleState(layoutComponent: LayoutComponent, isEnabled: Boolean) {
        val toggleableImageView = getLayoutComponentView(layoutComponent)?.view as? ToggleableImageView ?: return
        toggleableImageView.setToggleState(isEnabled)
    }

    fun instantiateLayout(runtimeLayout: RuntimeInputLayoutConfiguration, layoutTarget: LayoutTarget) {
        currentRuntimeLayout = runtimeLayout
        instantiateLayout(runtimeLayout.layout, layoutTarget)
        updateInputs()
        updateVisibility()
        setLayoutComponentToggleState(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT, isSoftInputVisible)
    }

    private fun updateInputs() {
        val currentRuntimeLayout = currentRuntimeLayout
        if (currentRuntimeLayout == null) {
            isGone = true
            return
        }

        isVisible = true
        val inputAlpha = currentRuntimeLayout.softInputOpacity / 100f

        val enableHapticFeedback = currentRuntimeLayout.isHapticFeedbackEnabled
        systemInputHandler?.let {
            getLayoutComponentView(LayoutComponent.DPAD)?.view?.setOnTouchListener(DpadInputHandler(it, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTONS)?.view?.setOnTouchListener(ButtonsInputHandler(it, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_L)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.L, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_R)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.R, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_SELECT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.SELECT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_START)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.START, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_HINGE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.HINGE, enableHapticFeedback, touchVibrator))
            // [KHMM] KH touch controls; MelonTouchHandler routes these to the addon-key channel
            getLayoutComponentView(LayoutComponent.KH_BUTTON_LOCK_ON)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.KH_LOCK_ON, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_BUTTON_SWITCH_TARGET_LEFT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.KH_SWITCH_TARGET_LEFT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_BUTTON_SWITCH_TARGET_RIGHT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.KH_SWITCH_TARGET_RIGHT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_COMMAND_MENU)?.view?.setOnTouchListener(KhCommandMenuInputHandler(it, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_BUTTON_HUD_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.KH_HUD_TOGGLE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_BUTTON_MAP_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.KH_FULLSCREEN_MAP_TOGGLE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.KH_BUTTON_SHORTCUT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.L, enableHapticFeedback, touchVibrator))
            // [KHMM] sticks track and draw themselves; only the value listeners attach here
            (getLayoutComponentView(LayoutComponent.MOVEMENT_STICK)?.view as? KhStickView)?.apply {
                deadzone = khStickDeadzone
                listener = KhStickDpadAdapter(it)
            }
            val khCameraListener = it as? IKhCameraListener
            (getLayoutComponentView(LayoutComponent.KH_CAMERA_STICK)?.view as? KhStickView)?.apply {
                deadzone = khStickDeadzone
                listener = KhStickView.Listener { x, y ->
                    val magnitude = sqrt(x * x + y * y)
                    if (magnitude <= 0f) {
                        khCameraListener?.onKhCameraAxes(0f, 0f)
                    } else {
                        // Squared response: a touch stick reaches full deflection far more
                        // easily than a physical one — fine control near center, same max.
                        // Then pre-compensate the native quantizer's own 0.15 deadzone
                        // (khQuantizeCameraAxis serves raw controller axes; this input is
                        // already deadzoned here, so it must not be deadzoned twice).
                        val outMagnitude = 0.15f + 0.85f * magnitude * magnitude
                        khCameraListener?.onKhCameraAxes(x / magnitude * outMagnitude, y / magnitude * outMagnitude)
                    }
                }
            }
        }
        frontendInputHandler?.let {
            getLayoutComponentView(LayoutComponent.BUTTON_RESET)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.RESET, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_PAUSE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.PAUSE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.FAST_FORWARD, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_MICROPHONE_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.MICROPHONE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.TOGGLE_SOFT_INPUT, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_SWAP_SCREENS)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.SWAP_SCREENS, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_QUICK_SAVE)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.QUICK_SAVE, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_QUICK_LOAD)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.QUICK_LOAD, enableHapticFeedback, touchVibrator))
            getLayoutComponentView(LayoutComponent.BUTTON_REWIND)?.view?.setOnTouchListener(SingleButtonInputHandler(it, Input.REWIND, enableHapticFeedback, touchVibrator))
        }

        getLayoutComponentViews().forEach {
            if (!it.component.isScreen()) {
                it.view.apply {
                    alpha = inputAlpha
                }
            }
        }

        updateScreenInputs()
    }

    private fun updateScreenInputs() {
        val (touchScreenComponent, nonTouchScreenComponent) = if (areScreensSwapped) {
            LayoutComponent.TOP_SCREEN to LayoutComponent.BOTTOM_SCREEN
        } else {
            LayoutComponent.BOTTOM_SCREEN to LayoutComponent.TOP_SCREEN
        }
        systemInputHandler?.let {
            getLayoutComponentView(touchScreenComponent)?.view?.setOnTouchListener(TouchscreenInputHandler(it))
        }
        getLayoutComponentView(nonTouchScreenComponent)?.view?.setOnTouchListener(null)
    }

    private fun updateVisibility() {
        val currentConnectedControllersState = connectedControllersState
        var hiddenComponents = when(currentRuntimeLayout?.softInputBehaviour) {
            SoftInputBehaviour.ALWAYS_VISIBLE -> emptyList()
            SoftInputBehaviour.HIDE_SYSTEM_BUTTONS_WHEN_CONTROLLERS_CONNECTED, null -> {
                when(currentConnectedControllersState) {
                    ConnectedControllersState.NoControllers -> emptyList()
                    is ConnectedControllersState.ControllersConnected -> listOf(
                        LayoutComponent.BUTTONS,
                        LayoutComponent.DPAD,
                        LayoutComponent.BUTTON_L,
                        LayoutComponent.BUTTON_R,
                        LayoutComponent.BUTTON_START,
                        LayoutComponent.BUTTON_SELECT,
                        LayoutComponent.MOVEMENT_STICK,
                        LayoutComponent.KH_BUTTON_SHORTCUT
                    ) + LayoutComponent.entries.filter { it.isKhComponent() } // [KHMM] KH actions live on the controller too
                }
            }
            SoftInputBehaviour.HIDE_ALL_BUTTONS_ASSIGNED_TO_CONNECTED_CONTROLLERS -> when(currentConnectedControllersState) {
                ConnectedControllersState.NoControllers -> emptyList()
                is ConnectedControllersState.ControllersConnected -> {
                    LayoutComponent.entries.filter {
                        // The component can be hidden if all matching inputs are assigned to connected controllers
                        it.matchingInputs.all {
                            currentConnectedControllersState.assignedInputs.contains(it)
                        }
                    }
                }
            }
            SoftInputBehaviour.ALWAYS_INVISIBLE -> LayoutComponent.entries.toList()
        }

        // [KHMM] each mode shows its own console's controls: with the KH plugin active the
        // movement stick replaces the d-pad and the stock controls it makes redundant go away
        // (DS R = lock-on, neither game uses the lid); without it, classic DS controls only.
        hiddenComponents = if (khControlsEnabled) {
            hiddenComponents + LayoutComponent.DPAD + LayoutComponent.BUTTON_R + LayoutComponent.BUTTON_HINGE +
                    LayoutComponent.BUTTON_L + // replaced by the KH shortcut button (same DS input)
                    if (khSingleScreenActive) listOf(LayoutComponent.BUTTON_SWAP_SCREENS) else emptyList()
        } else {
            hiddenComponents + LayoutComponent.entries.filter { it.isKhComponent() } +
                    LayoutComponent.MOVEMENT_STICK + LayoutComponent.KH_BUTTON_SHORTCUT
        }

        if (!isSoftInputVisible) {
            // Hide everything except the soft input toggle button if it was not already hidden by the soft input behaviour logic
            hiddenComponents = if (hiddenComponents.contains(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT)) {
                LayoutComponent.entries.toList()
            } else {
                LayoutComponent.entries.toList().filter { it != LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT}
            }
        }

        val visibleComponents = LayoutComponent.entries.filter { !hiddenComponents.contains(it) }

        hiddenComponents.forEach { component ->
            if (!component.isScreen()) {
                getLayoutComponentView(component)?.view?.isVisible = false
            }
        }
        visibleComponents.forEach { component ->
            if (!component.isScreen()) {
                getLayoutComponentView(component)?.view?.isVisible = true
            }
        }
    }
}