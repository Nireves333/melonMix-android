package me.magnum.melonds.ui.common.componentbuilders

import android.content.Context
import android.view.View
import me.magnum.melonds.ui.common.LayoutComponentViewBuilder
import me.magnum.melonds.ui.emulator.input.view.KhStickView

// [KHMM] virtual stick (KH camera stick: gold knob; movement stick: navy knob).
// The view draws and tracks itself; RuntimeLayoutView attaches the value listener.
class KhStickLayoutComponentViewBuilder(private val knobFilled: Boolean) : LayoutComponentViewBuilder() {
    override fun build(context: Context): View {
        return KhStickView(context, knobFilled)
    }

    override fun getAspectRatio() = 1f
}
