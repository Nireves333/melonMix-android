package me.magnum.melonds.domain.model.emulator

/**
 * [KHMM] Visual theme of the pause-menu overlay. Upstream desktop draws one KH2-style overlay
 * for every game; we skin it per loaded game instead, so Re:Coded's pause matches its own
 * menu dress (dark pills with white/red outlines) rather than Days' KH2 silver.
 */
enum class KhPauseMenuTheme {
    KH2,
    RECODED,
}
