package com.billo.ledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB300)
private val Teal = Color(0xFF26A69A)
private val Ink = Color(0xFF10151C)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    secondary = Teal,
    background = Ink,
    surface = Color(0xFF181E27),
    onPrimary = Ink,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB97700),
    secondary = Color(0xFF00796B),
)

@Composable
fun BillOTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
