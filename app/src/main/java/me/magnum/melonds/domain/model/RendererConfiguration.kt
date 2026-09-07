package me.magnum.melonds.domain.model

import me.magnum.melonds.domain.model.render.RenderStrategy

data class RendererConfiguration(
    val renderer: VideoRenderer,
    val videoFiltering: VideoFiltering,
    val threadedRendering: Boolean,
    val renderStrategy: RenderStrategy,
    private val internalResolutionScaling: Int,
    // [KHMM] KH Melon Mix single-screen enhanced-graphics master toggle (read over JNI)
    val enhancedGraphics: Boolean,
) {

    val resolutionScaling get() = when (renderer) {
        VideoRenderer.SOFTWARE -> 1
        VideoRenderer.OPENGL -> internalResolutionScaling
        VideoRenderer.COMPUTE -> internalResolutionScaling
    }
}