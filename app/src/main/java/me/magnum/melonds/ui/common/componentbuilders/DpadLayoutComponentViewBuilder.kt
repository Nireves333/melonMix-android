package me.magnum.melonds.ui.common.componentbuilders

import android.content.Context
import android.view.View
import android.widget.ImageView
import me.magnum.melonds.R
import me.magnum.melonds.ui.common.LayoutComponentViewBuilder

class DpadLayoutComponentViewBuilder(private val drawableRes: Int = R.drawable.keypad) : LayoutComponentViewBuilder() {
    override fun build(context: Context): View {
        return ImageView(context).apply {
            setImageResource(drawableRes)
        }
    }

    override fun getAspectRatio() = 1f
}