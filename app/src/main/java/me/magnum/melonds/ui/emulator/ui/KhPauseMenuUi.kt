package me.magnum.melonds.ui.emulator.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.emulator.KhPauseMenuState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.material.Text

/**
 * [KHMM] KH Melon Mix pause-menu overlay. The single-screen composite deliberately hides the
 * game's native pause menu ("hidden because we got the new one on overlay" in the plugin's
 * shape builders) and the plugin mirrors its content and cursor; this draws the replacement.
 * A Compose port of the desktop Qt PauseMenuOverlay (KHMelonMix
 * src/frontend/qt_sdl/MainWindow/PauseMenuOverlay.cpp) — all proportions are relative to the
 * overlay height scaled by the plugin's HUD-scale-derived size modifier, matching desktop.
 * Purely visual: input passes through to the game, which runs its own menu logic natively.
 *
 * [menuBounds] confines the menu to the on-screen top-screen viewport (desktop parents the
 * overlay to the emulator panel, Screen.cpp:115) — needed when the layout is not the forced
 * full-window one, e.g. dual-screen layouts with single-screen mode off. Null = full window
 * (the cutscene skip menu over a full-window HD video). The darken layer always covers the
 * whole window either way.
 */
@Composable
fun KhPauseMenuUi(state: KhPauseMenuState?, menuBounds: Rect? = null) {
    if (state == null) {
        return
    }

    val density = LocalDensity.current
    val khGummi = FontFamily(Font(R.font.kh_gummi))
    val khSogei = FontFamily(Font(R.font.kh_sogei))

    Box(modifier = Modifier.fillMaxSize()) {
    if (state.darkenBackground) {
        Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, 120f / 255f)))
    }

    val menuAreaModifier = if (menuBounds != null) {
        Modifier
            .offset { IntOffset(menuBounds.x, menuBounds.y) }
            .size(with(density) { menuBounds.width.toDp() }, with(density) { menuBounds.height.toDp() })
    } else {
        Modifier.fillMaxSize()
    }

    BoxWithConstraints(modifier = menuAreaModifier) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val m = state.sizeModifier
        val pauseSize = (15f / 13f) * m
        val buttonsSize = 1.25f * m
        val hasSubtitle = state.subtitle.isNotEmpty()

        // Vertical layout anchors (fractions of overlay height, from the desktop painter)
        val bottomMargin = (if (hasSubtitle) -0.01f else 0.03f) * m
        val titleCenterY = hPx * (0.5f - bottomMargin - 0.13f * m - (if (hasSubtitle) 0.045f * m else 0f))
        val subtitleCenterY = hPx * (0.5f - bottomMargin)
        val firstButtonY = hPx * (0.5f - bottomMargin + (if (hasSubtitle) 0.055f * m else 0f))

        val buttonHeightPx = hPx * 0.075f * buttonsSize
        val buttonWidthPx = buttonHeightPx * (1160f / 193f)
        val buttonSpacingPx = buttonHeightPx * 0.175f
        val buttonLeftPx = wPx / 2f - buttonWidthPx / 2f

        // Animation clock (seconds) for the hand bob and the selection glow
        val t by rememberInfiniteTransition(label = "khPauseMenu").animateFloat(
            initialValue = 0f,
            targetValue = 600f,
            animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing)),
            label = "clock",
        )

        // "PAUSE" ribbon graphic with the localized title text on top, squeezed 20% horizontally
        val titleImageHeightPx = hPx * 0.15f * pauseSize
        val titleImageWidthPx = titleImageHeightPx * 4f // 1024x256 source
        val titleFontPx = hPx * 0.064f * pauseSize
        Image(
            painter = painterResource(R.drawable.kh_pause_label),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset((wPx / 2f - titleImageWidthPx / 2f).roundToInt(), (titleCenterY - titleImageHeightPx / 2f).roundToInt()) }
                .size(with(density) { titleImageWidthPx.toDp() }, with(density) { titleImageHeightPx.toDp() }),
        )
        CenteredTextRow(
            centerYpx = titleCenterY,
            heightPx = titleFontPx * 2f,
            modifier = Modifier.graphicsLayer { scaleX = 0.8f },
        ) {
            val titleStyle = TextStyle(
                fontFamily = khGummi,
                fontSize = with(density) { titleFontPx.toSp() },
            )
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = state.title,
                    style = titleStyle.copy(
                        color = Color.Black,
                        drawStyle = Stroke(width = titleFontPx * 0.30f * pauseSize, join = StrokeJoin.Round, cap = StrokeCap.Round),
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(text = state.title, style = titleStyle.copy(color = Color.White), maxLines = 1, softWrap = false)
            }
        }

        // Subtitle (confirmation prompt), white over a soft black drop shadow
        if (hasSubtitle) {
            val subtitleFontPx = hPx * 0.058f * buttonsSize
            CenteredTextRow(centerYpx = subtitleCenterY, heightPx = subtitleFontPx * 2f) {
                Text(
                    text = state.subtitle,
                    style = TextStyle(
                        fontFamily = khSogei,
                        fontSize = with(density) { subtitleFontPx.toSp() },
                        color = Color.White,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(subtitleFontPx * 0.1f, subtitleFontPx * 0.1f),
                        ),
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Buttons
        val buttonFontPx = hPx * 0.056f * buttonsSize
        state.buttonLabels.forEachIndexed { index, label ->
            val isSelected = index == state.selection
            val buttonTopPx = firstButtonY + index * (buttonHeightPx + buttonSpacingPx)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(buttonLeftPx.roundToInt(), buttonTopPx.roundToInt()) }
                    .size(with(density) { buttonWidthPx.toDp() }, with(density) { buttonHeightPx.toDp() }),
            ) {
                Image(
                    painter = painterResource(if (isSelected) R.drawable.kh_button_selected else R.drawable.kh_button_unselected),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = khSogei,
                        fontSize = with(density) { buttonFontPx.toSp() },
                        color = Color.White,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(buttonFontPx * 0.1f, buttonFontPx * 0.1f),
                        ),
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }

            if (isSelected) {
                // Wandering glow accent inside the button's right rounded cap (desktop: a ~4:5
                // Lissajous figure measured from the KH2 reference footage)
                val capRadius = buttonHeightPx / 2f
                val capCenterX = buttonLeftPx + buttonWidthPx - capRadius
                val capCenterY = buttonTopPx + buttonHeightPx / 2f
                val glowSpeed = 0.5f
                val glowX = capCenterX + 0.19f * capRadius + 0.55f * capRadius * cos(2f * PI.toFloat() * (1.012f * glowSpeed) * t - 2.39f)
                val glowY = capCenterY - 0.56f * capRadius + 0.50f * capRadius * cos(2f * PI.toFloat() * (1.265f * glowSpeed) * t - 1.57f)
                val glowSizePx = buttonHeightPx * 0.5f
                Image(
                    painter = painterResource(R.drawable.kh_menu_light),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((glowX - glowSizePx / 2f).roundToInt(), (glowY - glowSizePx / 2f).roundToInt()) }
                        .size(with(density) { glowSizePx.toDp() }),
                )

                // Pointing-hand cursor beckoning at the selected entry with a small bob
                val handHeightPx = buttonHeightPx * 1.15f
                val handWidthPx = handHeightPx // 128x128 source
                val handBobPx = buttonHeightPx * 0.15f * sin(t * (2f * PI.toFloat() / 1.2f))
                Image(
                    painter = painterResource(R.drawable.kh_menu_hand),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (buttonLeftPx - handWidthPx + buttonHeightPx * 0.20f + handBobPx).roundToInt(),
                                (buttonTopPx + buttonHeightPx / 2f - handHeightPx / 2f).roundToInt(),
                            )
                        }
                        .size(with(density) { handWidthPx.toDp() }, with(density) { handHeightPx.toDp() }),
                )
            }
        }
    }
    }
}

/**
 * Full-width row of a fixed height whose vertical CENTER sits at [centerYpx], with its content
 * centered — lets text be positioned by center point without knowing its measured size.
 */
@Composable
private fun CenteredTextRow(
    centerYpx: Float,
    heightPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, (centerYpx - heightPx / 2f).roundToInt()) }
            .height(with(density) { heightPx.toDp() })
            .then(modifier),
    ) {
        content()
    }
}
