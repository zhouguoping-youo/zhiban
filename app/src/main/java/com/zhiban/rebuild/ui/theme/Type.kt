package com.zhiban.rebuild.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ChatGPT-style mobile type system.
 *
 * Android's sans-serif family resolves to Roboto for Latin and the device's
 * matching Noto Sans CJK face for Chinese, keeping both scripts visually
 * consistent without bundling a proprietary font file.
 */
private val ZhiBanFontFamily = FontFamily.SansSerif
private val ZhiBanPlatformStyle = PlatformTextStyle(includeFontPadding = false)

val ZhiBanTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.4).sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    displayMedium = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 38.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.2).sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    displaySmall = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    // 大标题
    headlineLarge = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    headlineMedium = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    headlineSmall = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    // 标题
    titleLarge = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    titleMedium = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    titleSmall = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    // 正文
    bodyLarge = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    bodyMedium = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    bodySmall = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    // 标签
    labelLarge = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    labelMedium = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
    labelSmall = TextStyle(
        fontFamily = ZhiBanFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        platformStyle = ZhiBanPlatformStyle,
    ),
)
