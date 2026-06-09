package com.conduit.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary            = Terracotta,
    onPrimary          = TerracottaOn,
    primaryContainer   = AccentLight,
    onPrimaryContainer = WarmBlack,
    secondary          = TextSub,
    onSecondary        = CardWhite,
    secondaryContainer = Panel,
    onSecondaryContainer = WarmBlack,
    background         = Cream,
    onBackground       = WarmBlack,
    surface            = CardWhite,
    onSurface          = WarmBlack,
    surfaceVariant     = Panel,
    onSurfaceVariant   = TextSub,
    outline            = BorderMid,
    outlineVariant     = BorderLight,
    error              = Bad,
    onError            = CardWhite,
)

private val DarkScheme = darkColorScheme(
    primary            = DarkAccent,
    onPrimary          = DarkAccentOn,
    primaryContainer   = DarkAccentLt,
    onPrimaryContainer = DarkText,
    secondary          = DarkTextSub,
    onSecondary        = DarkBg,
    secondaryContainer = DarkPanel,
    onSecondaryContainer = DarkText,
    background         = DarkBg,
    onBackground       = DarkText,
    surface            = DarkCard,
    onSurface          = DarkText,
    surfaceVariant     = DarkPanel,
    onSurfaceVariant   = DarkTextSub,
    outline            = DarkBorderMid,
    outlineVariant     = DarkBorder,
    error              = DarkBad,
    onError            = DarkBg,
)

private val TetherTypography = Typography(
    // Serif display used for card section headers
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize   = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize   = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    // Body stays in system sans-serif
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize   = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize   = 34.sp,
        lineHeight = 44.sp,
        letterSpacing = 8.sp,
    ),
)

@Composable
fun ConduitTheme(content: @Composable () -> Unit) {
    val mode by ThemeStore.mode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        "dark"  -> true
        "light" -> false
        else    -> systemDark
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography  = TetherTypography,
        content     = content,
    )
}
