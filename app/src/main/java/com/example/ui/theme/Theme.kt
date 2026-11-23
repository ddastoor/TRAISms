package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Strictly Light Mode ONLY per TRAISms architectural specification
private val TraiSmsLightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = OnCobaltPrimary,
    primaryContainer = CobaltPrimaryContainer,
    onPrimaryContainer = OnCobaltPrimaryContainer,
    secondary = RoyalSecondary,
    onSecondary = OnRoyalSecondary,
    secondaryContainer = RoyalSecondaryContainer,
    onSecondaryContainer = OnRoyalSecondaryContainer,
    tertiary = EmeraldTertiary,
    onTertiary = OnEmeraldTertiary,
    tertiaryContainer = EmeraldTertiaryContainer,
    onTertiaryContainer = OnEmeraldTertiaryContainer,
    background = PureWhiteBackground,
    onBackground = InkOnSurface,
    surface = SlateSurface,
    onSurface = InkOnSurface,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = MutedOnSurfaceVariant,
    surfaceContainerLowest = PureWhiteBackground,
    surfaceContainerLow = SlateSurfaceContainerLow,
    surfaceContainer = SlateSurfaceContainer,
    surfaceContainerHigh = SlateSurfaceContainerHigh,
    surfaceContainerHighest = SubtleOutlineVariant,
    outline = CrispOutline,
    outlineVariant = SubtleOutlineVariant,
    error = ErrorCrimson,
    onError = OnErrorCrimson,
    errorContainer = ErrorContainerRose,
    onErrorContainer = OnErrorContainerRose
)

val TraiSmsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun TraiSmsTheme(
    content: @Composable () -> Unit
) {
    // Hardcode lightColorScheme() and ignore system dark mode completely (darkTheme = false always)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = true
                insetsController.isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = TraiSmsLightColorScheme,
        typography = TraiSmsTypography,
        shapes = TraiSmsShapes,
        content = content
    )
}

// Alias kept for compatibility with existing starter test references
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    TraiSmsTheme(content = content)
}
