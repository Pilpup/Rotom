package com.college.core.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Color palette tokens supporting light and dark themes
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val searchPillBg: Color,
    val searchPillBorder: Color,
    val primaryPurple: Color,
    val primaryPurpleBg: Color,
    val accentOrange: Color,
    val accentOrangeBg: Color,
    val successGreen: Color,
    val successGreenBg: Color,
    val dangerRed: Color,
    val dangerRedBg: Color,
    val warningAmber: Color,
    val warningAmberBg: Color,
    val infoBlue: Color,
    val infoBlueBg: Color,
    val isDark: Boolean
)

// Fixed Dark Mode color tokens
val DarkColors = AppColors(
    background = Color(0xFF0C0A17),
    surface = Color(0xFF161326),
    surfaceBorder = Color(0xFF2E274D),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFA5B4FC),
    textMuted = Color(0xFF6B7280),
    divider = Color(0xFF231F3D),
    searchPillBg = Color(0xFF161326),
    searchPillBorder = Color(0xFF2E274D),
    primaryPurple = Color(0xFF8B5CF6),
    primaryPurpleBg = Color(0x338B5CF6),
    accentOrange = Color(0xFFFF7A00),
    accentOrangeBg = Color(0x33FF7A00),
    successGreen = Color(0xFF10B981),
    successGreenBg = Color(0x3310B981),
    dangerRed = Color(0xFFEF4444),
    dangerRedBg = Color(0x33EF4444),
    warningAmber = Color(0xFFF59E0B),
    warningAmberBg = Color(0x33F59E0B),
    infoBlue = Color(0xFF3B82F6),
    infoBlueBg = Color(0x333B82F6),
    isDark = true
)

// CompositionLocal for theme color palette (locked to DarkColors)
val LocalAppColors = staticCompositionLocalOf { DarkColors }

// Global accessor for active theme colors
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Root theme wrapper locked to Dark Mode (matching DarkColors regardless of system theme)
@Composable
fun CollegeAppTheme(
    content: @Composable () -> Unit
) {
    val colors = DarkColors

    // Synchronize system status and navigation bar icons for dark background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(colors.background) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                // Match the window background to DarkColors so IME transitions never flash.
                window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                window.decorView.setBackgroundColor(colors.background.toArgb())
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose {}
        }
    }

    val materialColors = darkColorScheme(
        primary = colors.primaryPurple,
        secondary = colors.accentOrange,
        background = colors.background,
        surface = colors.surface
    )

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content
        )
    }
}
