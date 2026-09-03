package com.rayban.ai.core.common.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = MintContainer,
    onPrimaryContainer = Mint,
    secondary = Ice,
    onSecondary = Ink,
    tertiary = Mint,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = ErrorRed,
)

private val LightColorScheme = lightColorScheme(
    primary = ColorTokens.LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = ColorTokens.LightPrimaryContainer,
    onPrimaryContainer = ColorTokens.LightPrimary,
    secondary = ColorTokens.LightSecondary,
    onSecondary = LightSurface,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = ColorTokens.LightOutline,
    error = ColorTokens.LightError,
)

private object ColorTokens {
    val LightPrimary = androidx.compose.ui.graphics.Color(0xFF006B59)
    val LightPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFA5F2D9)
    val LightSecondary = androidx.compose.ui.graphics.Color(0xFF4B5E78)
    val LightOutline = androidx.compose.ui.graphics.Color(0xFF6F797D)
    val LightError = androidx.compose.ui.graphics.Color(0xFFBA1A1A)
}

@Composable
fun RayBanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RayBanTypography,
        content = content,
    )
}
