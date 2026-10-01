package me.magnum.melonds.ui.emulator.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
 * Input passes through to the game, which runs its own menu logic natively; the entries are
 * also tappable ([onEntryTapped]) so a touch-only player can drive the menu — the caller
 * translates a tap into the equivalent DS key press.
 *
 * [menuBounds] confines the menu to the on-screen top-screen viewport (desktop parents the
 * overlay to the emulator panel, Screen.cpp:115) — needed when the layout is not the forced
 * full-window one, e.g. dual-screen layouts with single-screen mode off. Null = full window
 * (the cutscene skip menu over a full-window HD video). The darken layer always covers the
 * whole window either way.
 */
@Composable
fun KhPauseMenuUi(state: KhPauseMenuState?, menuBounds: Rect? = null, onEntryTapped: ((Int) -> Unit)? = null) {
    if (state == null) {
        return
    }

    val density = LocalDensity.current
    val context = LocalContext.current
    val khGummi = FontFamily(Font(R.font.kh_gummi))
    // The subtitle and button labels are drawn with a native Paint instead of a Text
    // composable: like desktop (PauseMenuOverlay.cpp "Center on the label's own ink box"),
    // they must be centered on the glyphs' measured ink box, because this font's vertical
    // metrics don't line up with its glyphs' visual center - metric-based centering sits
    // the text noticeably below the pill's middle (Discussion #16 screenshot).
    val khSogeiTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.kh_sogei) ?: Typeface.DEFAULT }

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
            val subtitlePaint = remember(khSogeiTypeface, subtitleFontPx) {
                Paint().apply {
                    isAntiAlias = true
                    typeface = khSogeiTypeface
                    textSize = subtitleFontPx
                }
            }
            val subtitleRowHeightPx = subtitleFontPx * 2f
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .offset { IntOffset(0, (subtitleCenterY - subtitleRowHeightPx / 2f).roundToInt()) }
                    .height(with(density) { subtitleRowHeightPx.toDp() }),
            ) {
                drawIntoCanvas { canvas ->
                    // Desktop: shadow offset = fontMetrics.height * 0.07 * buttonsSizeModifier
                    val shadowOffset = (subtitlePaint.fontMetrics.descent - subtitlePaint.fontMetrics.ascent) * 0.07f * buttonsSize
                    drawInkCenteredText(canvas.nativeCanvas, subtitlePaint, state.subtitle, size.width / 2f, size.height / 2f, shadowOffset)
                }
            }
        }

        // Buttons. Like desktop, wide localized labels (e.g. German "Überspringen") shrink to
        // fit the pill's flat area - the rounded caps eat ~buttonHeight of width.
        val buttonFontPx = remember(hPx, buttonsSize, buttonWidthPx, buttonHeightPx, khSogeiTypeface, state.buttonLabels) {
            val basePx = hPx * 0.056f * buttonsSize
            val measurePaint = Paint().apply {
                typeface = khSogeiTypeface
                textSize = basePx
            }
            val widestLabelPx = state.buttonLabels.maxOfOrNull { measurePaint.measureText(it) } ?: 0f
            val availableTextWidthPx = buttonWidthPx - buttonHeightPx
            if (widestLabelPx > availableTextWidthPx && availableTextWidthPx > 0f) {
                basePx * availableTextWidthPx / widestLabelPx
            } else {
                basePx
            }
        }
        val buttonPaint = remember(khSogeiTypeface, buttonFontPx) {
            Paint().apply {
                isAntiAlias = true
                typeface = khSogeiTypeface
                textSize = buttonFontPx
            }
        }
        state.buttonLabels.forEachIndexed { index, label ->
            val isSelected = index == state.selection
            val buttonTopPx = firstButtonY + index * (buttonHeightPx + buttonSpacingPx)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(buttonLeftPx.roundToInt(), buttonTopPx.roundToInt()) }
                    .size(with(density) { buttonWidthPx.toDp() }, with(density) { buttonHeightPx.toDp() })
                    .pointerInput(index) {
                        detectTapGestures { onEntryTapped?.invoke(index) }
                    },
            ) {
                Image(
                    painter = painterResource(if (isSelected) R.drawable.kh_button_selected else R.drawable.kh_button_unselected),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val shadowOffset = (buttonPaint.fontMetrics.descent - buttonPaint.fontMetrics.ascent) * 0.07f * buttonsSize
                        drawInkCenteredText(
                            canvas.nativeCanvas,
                            buttonPaint,
                            label,
                            size.width / 2f,
                            // Centered on the pill's OPTICAL center, not its geometric one: the
                            // art's top gloss puts its visual weight at ~45% of the height
                            // (luminance centroid), and the drop shadow hangs dark weight below
                            // the glyphs, so geometrically centered text reads low. Tune here.
                            size.height / 2f - size.height * BUTTON_LABEL_OPTICAL_RAISE,
                            shadowOffset,
                        )
                    }
                }
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
 * How far above the pill's geometric center the button label sits, as a fraction of the pill
 * height. Replaces desktop's 0.0025*h nudge (~3.3% of the pill), which was not enough on the
 * pill art: its luminance centroid is at ~45% of the height, so the optical center is ~5%
 * above geometric, plus the downward drop shadow's visual weight.
 */
private const val BUTTON_LABEL_OPTICAL_RAISE = 0.10f

/**
 * Draw [text] with its glyphs' INK BOX centered at ([centerX], [centerY]), black drop shadow
 * first, then white — the Compose-side twin of desktop PauseMenuOverlay.cpp's ink-box
 * centering ("the button font's vertical metrics don't line up with its glyphs' visual
 * center"). [Paint.getTextBounds] is Android's tightBoundingRect: relative to the baseline,
 * top negative.
 */
private fun drawInkCenteredText(
    canvas: android.graphics.Canvas,
    paint: Paint,
    text: String,
    centerX: Float,
    centerY: Float,
    shadowOffsetPx: Float,
) {
    val inkBox = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, inkBox)
    val textWidth = paint.measureText(text)
    val x = centerX - textWidth / 2f
    val baselineY = centerY - inkBox.top - inkBox.height() / 2f

    paint.color = android.graphics.Color.BLACK
    canvas.drawText(text, x + shadowOffsetPx, baselineY + shadowOffsetPx, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawText(text, x, baselineY, paint)
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
