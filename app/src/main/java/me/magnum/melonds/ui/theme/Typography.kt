package me.magnum.melonds.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.magnum.melonds.R

// [KHMM] KH reskin: top-bar/heading text (h6 is what TopAppBar titles use) wears the
// KH Gummi display font, matching the XML Toolbar.TitleText style.
val KhGummiFamily = FontFamily(Font(R.font.kh_gummi))

val MelonTypography @Composable get() = Typography(
    body1 = MaterialTheme.typography.body1.copy(lineHeight = 20.sp),
    button = MaterialTheme.typography.button.copy(fontWeight = FontWeight.Bold),
    h6 = MaterialTheme.typography.h6.copy(fontFamily = KhGummiFamily),
)