package me.magnum.melonds.ui.layouteditor

import me.magnum.melonds.R
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.ui.common.LayoutComponentViewBuilder
import me.magnum.melonds.ui.common.LayoutComponentViewBuilderFactory
import me.magnum.melonds.ui.common.componentbuilders.*

class EditorLayoutComponentViewBuilderFactory : LayoutComponentViewBuilderFactory {
    private val layoutComponentViewBuilderCache = mutableMapOf<LayoutComponent, LayoutComponentViewBuilder>()

    override fun getLayoutComponentViewBuilder(layoutComponent: LayoutComponent): LayoutComponentViewBuilder {
        return layoutComponentViewBuilderCache.getOrElse(layoutComponent) {
            val builder = when (layoutComponent) {
                LayoutComponent.TOP_SCREEN -> TopScreenLayoutComponentViewBuilder()
                LayoutComponent.BOTTOM_SCREEN -> BottomScreenLayoutComponentViewBuilder()
                LayoutComponent.DPAD -> EditorBackgroundLayoutComponentViewBuilder(DpadLayoutComponentViewBuilder())
                LayoutComponent.BUTTONS -> EditorBackgroundLayoutComponentViewBuilder(ButtonsLayoutComponentViewBuilder())
                // [KHMM]
                LayoutComponent.KH_COMMAND_MENU -> EditorBackgroundLayoutComponentViewBuilder(DpadLayoutComponentViewBuilder(R.drawable.kh_command_menu))
                LayoutComponent.KH_CAMERA_STICK -> EditorBackgroundLayoutComponentViewBuilder(KhStickLayoutComponentViewBuilder(knobFilled = true))
                LayoutComponent.MOVEMENT_STICK -> EditorBackgroundLayoutComponentViewBuilder(KhStickLayoutComponentViewBuilder(knobFilled = false))
                else -> EditorBackgroundLayoutComponentViewBuilder(SingleButtonLayoutComponentViewBuilder(layoutComponent))
            }

            layoutComponentViewBuilderCache[layoutComponent] = builder
            builder
        }
    }
}