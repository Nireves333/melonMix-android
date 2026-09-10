package me.magnum.melonds.ui.romlist.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.RomIconFiltering
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.rom.config.RomConfig
import me.magnum.melonds.ui.common.MelonPreviewSet
import me.magnum.melonds.ui.romlist.RomIcon
import me.magnum.melonds.ui.theme.MelonTheme

private val KhItemShape = RoundedCornerShape(12.dp)

/**
 * [KHMM] KH game-select row: a navy card with the game name in KH Gummi and a gold
 * glow that follows controller focus, echoing the KH menu cursor. The DS ROM icon is
 * kept as-is (user decision). Replaces ConfigurableRomItem on the ROM list screen only.
 */
@Composable
fun KhGameSelectItem(
    modifier: Modifier,
    rom: Rom,
    onClick: () -> Unit,
    onConfigClick: () -> Unit,
    onCursorMove: () -> Unit,
    retrieveTitleIcon: suspend () -> RomIcon,
) {
    val (mainFocusRequester, configFocusRequester) = remember { FocusRequester.createRefs() }
    var rowFocused by remember { mutableStateOf(false) }
    var configFocused by remember { mutableStateOf(false) }

    val gold = MaterialTheme.colors.secondary
    val glow by animateFloatAsState(
        targetValue = if (rowFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "khFocusGlow",
    )

    Box(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = (2 + 10 * glow).dp,
                    shape = KhItemShape,
                    ambientColor = gold,
                    spotColor = gold,
                )
                .background(MaterialTheme.colors.primary, KhItemShape)
                .border(2.dp, gold.copy(alpha = glow), KhItemShape)
                .clip(KhItemShape)
                .onFocusChanged {
                    rowFocused = it.isFocused
                    if (it.isFocused) onCursorMove()
                }
                .focusRequester(mainFocusRequester)
                .focusProperties {
                    end = configFocusRequester
                }
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var romIcon by remember { mutableStateOf<RomIcon?>(null) }
            LaunchedEffect(rom.hashCode()) {
                romIcon = retrieveTitleIcon()
            }

            Image(
                modifier = Modifier.size(56.dp),
                bitmap = romIcon?.bitmap?.asImageBitmap() ?: ImageBitmap(1, 1),
                contentDescription = null,
                filterQuality = when (romIcon?.filtering) {
                    RomIconFiltering.NONE -> FilterQuality.None
                    RomIconFiltering.LINEAR -> DrawScope.DefaultFilterQuality
                    null -> DrawScope.DefaultFilterQuality
                },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = rom.config.customName ?: rom.name,
                    style = MaterialTheme.typography.h6.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = MaterialTheme.colors.onPrimary,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
                Text(
                    text = rom.fileName,
                    style = MaterialTheme.typography.body2.copy(fontSize = 12.sp),
                    color = MaterialTheme.colors.onPrimary.copy(alpha = 0.5f),
                    overflow = TextOverflow.StartEllipsis,
                    maxLines = 1,
                )
            }

            IconButton(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (configFocused) gold.copy(alpha = 0.2f) else Color.Transparent)
                    .onFocusChanged {
                        configFocused = it.isFocused
                        if (it.isFocused) onCursorMove()
                    }
                    .focusRequester(configFocusRequester)
                    .focusProperties {
                        start = mainFocusRequester
                    },
                onClick = onConfigClick,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.rom_settings),
                    tint = if (configFocused) gold else MaterialTheme.colors.onPrimary.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@MelonPreviewSet
@Composable
private fun PreviewKhGameSelectItem() {
    val bitmap = createBitmap(1, 1).apply { this[0, 0] = 0xFF777777.toInt() }

    MelonTheme {
        KhGameSelectItem(
            modifier = Modifier.fillMaxWidth(),
            rom = Rom("Kingdom Hearts 358/2 Days", "Square Enix", "days.nds", Uri.EMPTY, Uri.EMPTY, RomConfig(), null, false, ""),
            onClick = {},
            onConfigClick = {},
            onCursorMove = {},
            retrieveTitleIcon = { RomIcon(bitmap, RomIconFiltering.NONE) },
        )
    }
}
