package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color.Black,
    primaryContainer = IndigoCard,
    onPrimaryContainer = CyanGlow,
    secondary = VioletSecondary,
    onSecondary = Color.White,
    tertiary = MagentaAccent,
    background = IndigoDark,
    onBackground = TextPrimary,
    surface = IndigoSurface,
    onSurface = TextPrimary,
    surfaceVariant = IndigoCard,
    onSurfaceVariant = TextSecondary,
    outline = IndigoBorder,
    error = RedError,
    onError = Color.White
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF006D77),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F7FA),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF7B1FA2),
    onSecondary = Color.White,
    tertiary = Color(0xFFC2185B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFB00020),
    onError = Color.White
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to futuristic dark mode for voice assistant
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

