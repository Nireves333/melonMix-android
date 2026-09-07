package me.magnum.melonds.domain.model.emulator

/**
 * [KHMM] Snapshot of the KH Melon Mix pause-menu overlay. The plugin hides the game's native
 * pause menu inside the single-screen composite and mirrors its state (labels, cursor) so the
 * frontend can draw a styled replacement — this is that state.
 */
data class KhPauseMenuState(
    val visible: Boolean,
    val selection: Int,
    val darkenBackground: Boolean,
    val sizeModifier: Float,
    val title: String,
    val subtitle: String,
    val buttonLabels: List<String>,
)
