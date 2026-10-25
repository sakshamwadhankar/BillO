package com.billo.ledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * BillO design language (from the approved mockup):
 * pale mint/lime/peach cards, dark forest green primary, lime action buttons,
 * always-light (the mockup is a bright, friendly daylight UI).
 */
val Ink = Color(0xFF10291D)          // headings / numbers
val Cream = Color(0xFFF6FAF3)        // app background
val CardMint = Color(0xFFDFF0E4)     // pale green card
val CardLime = Color(0xFFE9F6C9)     // pale lime card
val CardPeach = Color(0xFFFCE9DC)    // pale peach card (warnings)
val CardSky = Color(0xFFDDEBEF)      // pale blue-grey card
val Forest = Color(0xFF123B2A)       // primary dark green (buttons, active nav)
val LimeAccent = Color(0xFFC9E86F)   // lime action button
val TealGreen = Color(0xFF2E8B6B)    // positive money
val PeachDeep = Color(0xFFC96F3B)    // warning accent
val MutedText = Color(0xFF5B7267)    // secondary text
val ChipOn = Color(0xFFD7EFE2)       // selected language chip

private val BillOScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = TealGreen,
    background = Cream,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    primaryContainer = CardMint,
    secondaryContainer = CardLime,
)

@Composable
fun BillOTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BillOScheme, content = content)
}
