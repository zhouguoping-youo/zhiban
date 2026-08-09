package com.zhiban.rebuild.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
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
    background = DeepNavy,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Gray800,
    onSurfaceVariant = Gray400,
    outline = Gray600,
    outlineVariant = Gray700,
    error = ErrorRed,
    onError = SurfaceLight,
)

private val ZhiBanShapes = Shapes(
    extraSmall = RoundedCornerShape(ZhiBanRadius.ExtraSmall),
    small = RoundedCornerShape(ZhiBanRadius.Small),
    medium = RoundedCornerShape(ZhiBanRadius.Medium),
    large = RoundedCornerShape(ZhiBanRadius.Card),
    extraLarge = RoundedCornerShape(ZhiBanRadius.Dialog),
)

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
