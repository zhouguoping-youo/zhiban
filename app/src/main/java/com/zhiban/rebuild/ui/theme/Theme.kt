package com.zhiban.rebuild.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = ZhiBanTerracotta,
    onPrimary = SurfaceLight,
    primaryContainer = ZhiBanTerracottaSoft,
    onPrimaryContainer = ZhiBanTerracotta,
    secondary = CloudBlue,
    onSecondary = SurfaceLight,
    secondaryContainer = CloudBlue.copy(alpha = 0.12f),
    onSecondaryContainer = CloudBlue,
    tertiary = DeepNavy,
    background = ZhiBanWarmBackground,
    onBackground = ZhiBanTextPrimary,
    surface = ZhiBanCard,
    onSurface = ZhiBanTextPrimary,
    surfaceVariant = ZhiBanWarmCanvas,
    onSurfaceVariant = ZhiBanTextSecondary,
    outline = ZhiBanDivider,
    outlineVariant = ZhiBanDivider,
    error = ErrorRed,
    onError = SurfaceLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = ZhiBanTerracotta,
    onPrimary = SurfaceLight,
    primaryContainer = ZhiBanTerracottaSoft,
    onPrimaryContainer = ZhiBanTerracotta,
    secondary = CloudBlue,
    onSecondary = SurfaceLight,
    secondaryContainer = CloudBlue.copy(alpha = 0.2f),
    onSecondaryContainer = CloudBlue,
    tertiary = Gray300,
    background = DarkBackground,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Gray400,
    outline = Gray600,
    outlineVariant = Gray700,
    error = ErrorRed,
    onError = SurfaceLight,
)

private val ZhiBanShapes = Shapes(
    // Material text fields consume extraSmall; keep every form field on the
    // same 18 dp product radius even when a page uses Material directly.
    extraSmall = RoundedCornerShape(ZhiBanRadius.Input),
    small = RoundedCornerShape(ZhiBanRadius.Small),
    // Cards consume medium in Material 3.
    medium = RoundedCornerShape(ZhiBanRadius.Card),
    large = RoundedCornerShape(ZhiBanRadius.Card),
    extraLarge = RoundedCornerShape(ZhiBanRadius.Dialog),
)

/** Quiet brand feedback shared by rows, cards, icons, tabs and buttons. */
@OptIn(ExperimentalMaterial3Api::class)
internal val ZhiBanRippleConfiguration = RippleConfiguration(
    color = ZhiBanTerracotta,
    rippleAlpha = RippleAlpha(
        pressedAlpha = 0.10f,
        focusedAlpha = 0.08f,
        draggedAlpha = 0.12f,
        hoveredAlpha = 0.04f,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhiBanTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZhiBanTypography,
        shapes = ZhiBanShapes,
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides ZhiBanRippleConfiguration,
            LocalRelationshipGraphColors provides if (darkTheme) {
                DarkRelationshipGraphColors
            } else {
                LightRelationshipGraphColors
            },
        ) {
            // Ensures Text calls that only override size/weight still inherit the
            // same sans-serif family instead of falling back to a page-local face.
            ProvideTextStyle(value = ZhiBanTypography.bodyLarge, content = content)
        }
    }
}
