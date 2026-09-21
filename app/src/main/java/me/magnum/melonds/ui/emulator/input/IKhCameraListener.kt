package me.magnum.melonds.ui.emulator.input

/**
 * [KHMM] Receiver for KH camera-stick axes (x right-positive, y down-positive, unit circle).
 */
interface IKhCameraListener {
    fun onKhCameraAxes(x: Float, y: Float)
}
